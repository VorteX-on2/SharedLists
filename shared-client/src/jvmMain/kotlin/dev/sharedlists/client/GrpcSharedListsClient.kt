package dev.sharedlists.client

import dev.sharedlists.protocol.AppliedThrough
import dev.sharedlists.protocol.ChallengeRequest
import dev.sharedlists.protocol.Live
import dev.sharedlists.protocol.OpenSync
import dev.sharedlists.protocol.SharedListsGrpcKt
import dev.sharedlists.protocol.SyncRequest
import dev.sharedlists.protocol.SyncResponse
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.netty.handler.ssl.util.SimpleTrustManagerFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Properties
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class ServerEndpoint(
    val host: String,
    val port: Int,
    val certificatePin: String,
)

interface ClientStateStore {
    fun loadCursor(): SynchronizationCursor?

    fun save(cursor: SynchronizationCursor)
}

class FileClientStateStore(
    private val file: File,
) : ClientStateStore {
    override fun loadCursor(): SynchronizationCursor? {
        if (!file.exists()) {
            return null
        }
        return Properties().also { properties -> file.inputStream().use(properties::load) }
            .let { properties ->
                SynchronizationCursor(
                    generation = properties.getProperty("generation"),
                    lastAppliedRevision = properties.getProperty("lastAppliedRevision").toLong(),
                )
            }
    }

    override fun save(cursor: SynchronizationCursor) {
        val parent = requireNotNull(file.parentFile) { "Client state file must have a parent directory." }
        parent.mkdirs()
        val temporaryFile = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp")
        FileOutputStream(temporaryFile.toFile()).use { stream ->
            Properties().also { properties ->
                properties.setProperty("generation", cursor.generation)
                properties.setProperty("lastAppliedRevision", cursor.lastAppliedRevision.toString())
                properties.store(stream, null)
            }
            stream.fd.sync()
        }
        Files.move(
            temporaryFile,
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

class GrpcSharedListsClient(
    private val deviceSigner: DeviceSigner,
    private val endpoint: ServerEndpoint,
    private val stateStore: ClientStateStore,
) : SharedListsClient {
    override suspend fun synchronize(): ClientState {
        val channel = NettyChannelBuilder.forAddress(endpoint.host, endpoint.port)
            .sslContext(GrpcSslContexts.forClient().trustManager(PinnedTrustManager(endpoint.certificatePin)).build())
            .build()
        try {
            return coroutineScope {
                val unauthenticatedStub = SharedListsGrpcKt.SharedListsCoroutineStub(channel)
                val challenge = unauthenticatedStub.getChallenge(
                    ChallengeRequest.newBuilder().setKeyFingerprint(deviceSigner.keyFingerprint).build(),
                )
                val token = StreamJwt.create(
                    deviceSigner,
                    StreamChallenge(
                        audience = challenge.audience,
                        expiresAt = Instant.ofEpochSecond(challenge.expiresAtEpochSeconds),
                        issuedAt = Instant.ofEpochSecond(challenge.issuedAtEpochSeconds),
                        nonce = challenge.nonce.toByteArray(),
                    ),
                )
                val stub = unauthenticatedStub.withInterceptors(BearerTokenInterceptor(token))
                val requests = CoroutineChannel<SyncRequest>()
                val initialResponse = CompletableDeferred<SyncResponse>()
                val live = CompletableDeferred<Live>()
                val responseJob = launch {
                    stub.sync(requests.receiveAsFlow()).collect { response ->
                        initialResponse.complete(response)
                        if (response.hasLive()) live.complete(response.live)
                    }
                }
                requests.send(
                    SyncRequest.newBuilder().setOpen(
                        OpenSync.newBuilder().also { open ->
                            stateStore.loadCursor()?.let { cursor ->
                                open.cursorBuilder
                                    .setGeneration(cursor.generation)
                                    .setLastAppliedRevision(cursor.lastAppliedRevision)
                            }
                        }.build(),
                    ).build(),
                )
                val initial = initialResponse.await()
                val (cursor, receivedLive) = if (initial.hasEmptySnapshot()) {
                    val receivedSnapshot = initial.emptySnapshot
                    val receivedCursor = SynchronizationCursor(receivedSnapshot.generation, receivedSnapshot.revision)
                    stateStore.save(receivedCursor)
                    requests.send(
                        SyncRequest.newBuilder().setAppliedThrough(
                            AppliedThrough.newBuilder().setRevision(receivedSnapshot.revision).build(),
                        ).build(),
                    )
                    receivedCursor to live.await()
                } else {
                    check(initial.hasLive()) { "Server did not provide synchronization state." }
                    val receivedLive = initial.live
                    SynchronizationCursor(receivedLive.generation, receivedLive.revision)
                        .also(stateStore::save) to receivedLive
                }
                check(receivedLive.generation == cursor.generation && receivedLive.revision == cursor.lastAppliedRevision) {
                    "Server declared an inconsistent live cursor."
                }
                requests.close()
                responseJob.cancelAndJoin()
                ClientState.Ready(
                    enrollment = EnrollmentState.ENROLLED,
                    connectivity = ConnectivityState.LIVE,
                    canonicalState = CanonicalState(),
                    cursor = cursor,
                )
            }
        } finally {
            channel.shutdownNow()
        }
    }

    override suspend fun submit(command: EditCommand): ClientState =
        error("Edit synchronization is not established by the acceptance harness.")

}

private class PinnedTrustManager(
    pin: String,
) : SimpleTrustManagerFactory() {
    private val trustManager = CertificatePinTrustManager(pin)

    override fun engineGetTrustManagers(): Array<TrustManager> = arrayOf(trustManager)

    override fun engineInit(managerFactoryParameters: ManagerFactoryParameters?) = Unit

    override fun engineInit(keyStore: KeyStore?) = Unit
}

interface DeviceSigner {
    val keyFingerprint: String

    fun signEs256(signingInput: ByteArray): ByteArray
}

data class StreamChallenge(
    val audience: String,
    val expiresAt: Instant,
    val issuedAt: Instant,
    val nonce: ByteArray,
)

object StreamJwt {
    const val TYPE = "sharedlists-stream+jwt"

    fun create(deviceSigner: DeviceSigner, challenge: StreamChallenge): String {
        require(challenge.nonce.size == 32) { "Authentication challenge must contain 256 bits of entropy." }
        require(challenge.expiresAt.epochSecond - challenge.issuedAt.epochSecond == 60L) {
            "Authentication challenge must be valid for 60 seconds."
        }
        val keyUrn = "urn:sharedlists:key:${deviceSigner.keyFingerprint}"
        val header = """{"alg":"ES256","typ":"$TYPE","kid":"${deviceSigner.keyFingerprint}"}"""
        val payload =
            """{"iss":"$keyUrn","sub":"$keyUrn","aud":"${challenge.audience}","iat":${challenge.issuedAt.epochSecond},"exp":${challenge.expiresAt.epochSecond},"nonce":"${challenge.nonce.base64Url()}"}"""
        val signingInput = "${header.encodeToByteArray().base64Url()}.${payload.encodeToByteArray().base64Url()}"
        val signature = deviceSigner.signEs256(signingInput.encodeToByteArray())
        require(signature.size == 64) { "DeviceSigner must return a 64-byte JOSE ES256 signature." }
        return "$signingInput.${signature.base64Url()}"
    }
}

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private class BearerTokenInterceptor(
    private val token: String,
) : ClientInterceptor {
    override fun <RequestT : Any, ResponseT : Any> interceptCall(
        method: MethodDescriptor<RequestT, ResponseT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<RequestT, ResponseT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<RequestT, ResponseT>(
            next.newCall(method, callOptions),
        ) {
            override fun start(responseListener: Listener<ResponseT>, headers: Metadata) {
                headers.put(AUTHORIZATION, "$BEARER_PREFIX$token")
                super.start(responseListener, headers)
            }
        }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private val AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

private class CertificatePinTrustManager(
    pin: String,
) : X509TrustManager {
    private val expectedPin = pin.replace(":", "").uppercase()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        require(chain.isNotEmpty()) { "Server did not provide a certificate." }
        val actualPin = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            .joinToString("") { "%02X".format(it) }
        require(actualPin == expectedPin) { "Server certificate pin did not match." }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

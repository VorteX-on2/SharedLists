package dev.sharedlists.server

import com.google.protobuf.ByteString
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import dev.sharedlists.protocol.AuthenticationChallenge
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

internal const val STREAM_TOKEN_TYPE = "sharedlists-stream+jwt"

internal object AuthContext {
    val keyFingerprint: Context.Key<String> = Context.key("authenticated-key-fingerprint")
}

internal enum class AuthFailure(val wireName: String) {
    CHALLENGE_EXPIRED_OR_CONSUMED("challenge expired/consumed"),
    DEVICE_NOT_ENROLLED("device not enrolled"),
    INVALID_AUTHENTICATION("invalid authentication"),
}

internal class AuthenticationException(
    val failure: AuthFailure,
) : RuntimeException(failure.wireName)

internal fun AuthenticationException.asStatusException() =
    failure.status().asRuntimeException()

internal data class Challenge(
    val audience: String,
    val expiresAt: Instant,
    val issuedAt: Instant,
    val nonce: ByteArray,
) {
    fun toProto(): AuthenticationChallenge =
        AuthenticationChallenge.newBuilder()
            .setAudience(audience)
            .setExpiresAtEpochSeconds(expiresAt.epochSecond)
            .setIssuedAtEpochSeconds(issuedAt.epochSecond)
            .setNonce(ByteString.copyFrom(nonce))
            .build()
}

internal class ChallengeAuthenticator(
    private val audience: String,
    private val authorizedKeys: Map<String, ECPublicKey>,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val challenges = ConcurrentHashMap<String, Challenge>()

    fun authenticate(compactJwt: String): String {
        val jwt = try {
            SignedJWT.parse(compactJwt)
        } catch (_: Exception) {
            throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
        }
        val header = jwt.header
        val fingerprint = header.keyID ?: throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
        val publicKey = authorizedKeys[fingerprint]
            ?: throw AuthenticationException(AuthFailure.DEVICE_NOT_ENROLLED)
        if (
            header.algorithm != JWSAlgorithm.ES256 ||
            header.type != JOSEObjectType(STREAM_TOKEN_TYPE) ||
            header.includedParams != setOf("alg", "typ", "kid")
        ) {
            throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
        }
        synchronized(challenges) {
            val challenge = challenges[fingerprint]
                ?: throw AuthenticationException(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED)
            if (!clock.instant().isBefore(challenge.expiresAt)) {
                challenges.remove(fingerprint, challenge)
                throw AuthenticationException(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED)
            }
            val claims = try {
                jwt.jwtClaimsSet
            } catch (_: Exception) {
                throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
            }
            val keyUrn = "urn:sharedlists:key:$fingerprint"
            if (
                claims.issuer != keyUrn ||
                claims.subject != keyUrn ||
                claims.audience != listOf(audience) ||
                claims.issueTime?.toInstant() != challenge.issuedAt ||
                claims.expirationTime?.toInstant() != challenge.expiresAt ||
                claims.getStringClaim("nonce") != challenge.nonce.base64Url() ||
                !jwt.verify(ECDSAVerifier(publicKey))
            ) {
                throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
            }
            if (!challenges.remove(fingerprint, challenge)) {
                throw AuthenticationException(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED)
            }
        }
        return fingerprint
    }

    fun issue(fingerprint: String): Challenge {
        if (!authorizedKeys.containsKey(fingerprint)) {
            throw AuthenticationException(AuthFailure.DEVICE_NOT_ENROLLED)
        }
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return Challenge(
            audience = audience,
            expiresAt = issuedAt.plusSeconds(60),
            issuedAt = issuedAt,
            nonce = ByteArray(32).also(secureRandom::nextBytes),
        ).also { challenges[fingerprint] = it }
    }
}

internal class AuthenticationInterceptor(
    private val authenticator: ChallengeAuthenticator,
    private val streams: ActiveStreamRegistry,
) : ServerInterceptor {
    override fun <RequestT : Any, ResponseT : Any> interceptCall(
        call: ServerCall<RequestT, ResponseT>,
        headers: Metadata,
        next: ServerCallHandler<RequestT, ResponseT>,
    ): ServerCall.Listener<RequestT> {
        if (!call.methodDescriptor.fullMethodName.endsWith("/Sync")) {
            return next.startCall(call, headers)
        }
        val token = headers.get(AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?: return close(call, AuthFailure.INVALID_AUTHENTICATION)
        return try {
            val fingerprint = authenticator.authenticate(token)
            streams.register(fingerprint, call)
            val listener = Contexts.interceptCall(
                Context.current().withValue(AuthContext.keyFingerprint, fingerprint),
                call,
                headers,
                next,
            )
            object : ForwardingServerCallListener.SimpleForwardingServerCallListener<RequestT>(listener) {
                override fun onCancel() {
                    streams.unregister(fingerprint, call)
                    super.onCancel()
                }

                override fun onComplete() {
                    streams.unregister(fingerprint, call)
                    super.onComplete()
                }
            }
        } catch (exception: AuthenticationException) {
            close(call, exception.failure)
        }
    }

    private fun <RequestT : Any, ResponseT : Any> close(
        call: ServerCall<RequestT, ResponseT>,
        failure: AuthFailure,
    ): ServerCall.Listener<RequestT> {
        call.close(failure.status(), Metadata())
        return object : ServerCall.Listener<RequestT>() {}
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

internal class ActiveStreamRegistry {
    private val calls = mutableMapOf<String, ServerCall<*, *>>()

    fun <RequestT : Any, ResponseT : Any> register(keyFingerprint: String, call: ServerCall<RequestT, ResponseT>) {
        synchronized(calls) {
            calls.put(keyFingerprint, call)?.close(Status.ABORTED.withDescription("session superseded"), Metadata())
        }
    }

    fun <RequestT : Any, ResponseT : Any> unregister(keyFingerprint: String, call: ServerCall<RequestT, ResponseT>) {
        synchronized(calls) {
            if (calls[keyFingerprint] === call) {
                calls.remove(keyFingerprint)
            }
        }
    }
}

private fun AuthFailure.status(): Status =
    when (this) {
        AuthFailure.DEVICE_NOT_ENROLLED -> Status.PERMISSION_DENIED
        AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED,
        AuthFailure.INVALID_AUTHENTICATION,
        -> Status.UNAUTHENTICATED
    }.withDescription(wireName)

internal object AuthorizedDeviceKeys {
    fun load(directory: Path): Map<String, ECPublicKey> {
        require(Files.isDirectory(directory) && Files.isReadable(directory)) {
            "Authorized devices directory is unreadable: $directory"
        }
        val keys = linkedMapOf<String, ECPublicKey>()
        Files.list(directory).use { files ->
            files.filter(Files::isRegularFile).sorted().forEach { file ->
                val publicKey = readPublicKey(file)
                val fingerprint = publicKey.encoded.sha256Fingerprint()
                require(keys.put(fingerprint, publicKey) == null) {
                    "Duplicate authorized device key: $fingerprint"
                }
            }
        }
        return keys
    }

    private fun readPublicKey(file: Path): ECPublicKey =
        InputStreamReader(Files.newInputStream(file)).use { reader ->
            val subjectPublicKeyInfo = PEMParser(reader).use { parser ->
                parser.readObject().also { require(parser.readObject() == null) { "Invalid PEM allowlist entry: $file" } }
                    as? SubjectPublicKeyInfo ?: error("Allowlist entry must be a public-key PEM: $file")
            }
            require(
                ASN1ObjectIdentifier.getInstance(subjectPublicKeyInfo.algorithm.parameters) == X9ObjectIdentifiers.prime256v1,
            ) { "Allowlist entry must contain a P-256 public key: $file" }
            JcaPEMKeyConverter().getPublicKey(subjectPublicKeyInfo) as? ECPublicKey
                ?: error("Allowlist entry must contain an EC public key: $file")
        }
}

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun ByteArray.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(this).base64Url()

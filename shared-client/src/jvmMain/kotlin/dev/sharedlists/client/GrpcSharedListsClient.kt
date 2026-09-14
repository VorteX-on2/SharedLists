package dev.sharedlists.client

import dev.sharedlists.protocol.AppliedThrough
import dev.sharedlists.protocol.ChallengeRequest
import dev.sharedlists.protocol.ClientOperation
import dev.sharedlists.protocol.CreateItem as ProtoCreateItem
import dev.sharedlists.protocol.CreateList as ProtoCreateList
import dev.sharedlists.protocol.DeleteItem as ProtoDeleteItem
import dev.sharedlists.protocol.DeleteList as ProtoDeleteList
import dev.sharedlists.protocol.EditItemText as ProtoEditItemText
import dev.sharedlists.protocol.JournalEntry
import dev.sharedlists.protocol.Live
import dev.sharedlists.protocol.OpenSync
import dev.sharedlists.protocol.OperationOutcome as ProtoOperationOutcome
import dev.sharedlists.protocol.RenameList as ProtoRenameList
import dev.sharedlists.protocol.SharedListsGrpcKt
import dev.sharedlists.protocol.SubmitOperation
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class ServerEndpoint(
    val host: String,
    val port: Int,
    val certificatePin: String,
)

interface ClientStateStore {
    fun hasCanonicalState(): Boolean = false

    fun loadCursor(): SynchronizationCursor?

    fun loadCanonicalState(): CanonicalState = CanonicalState()

    fun save(cursor: SynchronizationCursor)

    fun save(canonicalState: CanonicalState, cursor: SynchronizationCursor) {
        save(cursor)
    }
}

class FileClientStateStore(
    private val file: File,
) : ClientStateStore {
    override fun hasCanonicalState(): Boolean =
        file.exists() && Properties().also { properties -> file.inputStream().use(properties::load) }
            .containsKey("list.count")

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

    override fun loadCanonicalState(): CanonicalState {
        if (!file.exists()) {
            return CanonicalState()
        }
        return Properties().also { properties -> file.inputStream().use(properties::load) }
            .let { properties ->
                CanonicalState(
                    (0 until properties.getProperty("list.count", "0").toInt()).map { index ->
                        SharedList(
                            id = SharedListId.parse(requireNotNull(properties.getProperty("list.$index.id"))),
                            items = (0 until properties.getProperty("list.$index.item.count", "0").toInt()).map { itemIndex ->
                                ListItem(
                                    id = ListItemId.parse(requireNotNull(properties.getProperty("list.$index.item.$itemIndex.id"))),
                                    marked = requireNotNull(properties.getProperty("list.$index.item.$itemIndex.marked")).toBoolean(),
                                    text = requireNotNull(properties.getProperty("list.$index.item.$itemIndex.text")),
                                )
                            },
                            name = requireNotNull(properties.getProperty("list.$index.name")),
                        )
                    },
                )
            }
    }

    override fun save(cursor: SynchronizationCursor) {
        save(CanonicalState(), cursor)
    }

    override fun save(canonicalState: CanonicalState, cursor: SynchronizationCursor) {
        val parent = requireNotNull(file.parentFile) { "Client state file must have a parent directory." }
        parent.mkdirs()
        val temporaryFile = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp")
        FileOutputStream(temporaryFile.toFile()).use { stream ->
            Properties().also { properties ->
                properties.setProperty("generation", cursor.generation)
                properties.setProperty("lastAppliedRevision", cursor.lastAppliedRevision.toString())
                properties.setProperty("list.count", canonicalState.lists.size.toString())
                canonicalState.lists.forEachIndexed { index, list ->
                    properties.setProperty("list.$index.id", list.id.value)
                    properties.setProperty("list.$index.item.count", list.items.size.toString())
                    list.items.forEachIndexed { itemIndex, item ->
                        properties.setProperty("list.$index.item.$itemIndex.id", item.id.value)
                        properties.setProperty("list.$index.item.$itemIndex.marked", item.marked.toString())
                        properties.setProperty("list.$index.item.$itemIndex.text", item.text)
                    }
                    properties.setProperty("list.$index.name", list.name)
                }
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
    private var activeSession: ActiveSession? = null
    private var cachedState = stateStore.loadCanonicalState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun synchronize(): ClientState {
        activeSession?.close()
        val channel = NettyChannelBuilder.forAddress(endpoint.host, endpoint.port)
            .sslContext(GrpcSslContexts.forClient().trustManager(PinnedTrustManager(endpoint.certificatePin)).build())
            .build()
        try {
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
            val session = ActiveSession(
                channel,
                unauthenticatedStub.withInterceptors(BearerTokenInterceptor(token)),
                cachedState,
            )
            activeSession = session
            session.open(stateStore.loadCursor()?.takeIf { stateStore.hasCanonicalState() })
            val live = session.live.await()
            check(live.generation == session.cursor.generation && live.revision == session.cursor.lastAppliedRevision) {
                "Server declared an inconsistent live cursor."
            }
            return session.ready()
        } catch (exception: Exception) {
            channel.shutdownNow()
            activeSession = null
            throw exception
        }
    }

    override suspend fun submit(command: EditCommand): ClientState {
        val session = requireNotNull(activeSession) { "Synchronization is not live." }
        return session.submit(command)
    }

    private inner class ActiveSession(
        private val channel: io.grpc.ManagedChannel,
        private val stub: SharedListsGrpcKt.SharedListsCoroutineStub,
        initialState: CanonicalState,
    ) {
        private var canonicalState = initialState
        private var unconfirmedOperationId: String? = null
        private var unconfirmedOutcome: CompletableDeferred<DurableOperationOutcome>? = null
        private val requests = CoroutineChannel<SyncRequest>()
        private val responseJob = scope.launch {
            try {
                stub.sync(requests.receiveAsFlow()).collect(::receive)
            } catch (exception: CancellationException) {
                fail(exception)
                throw exception
            } catch (exception: Exception) {
                fail(exception)
            }
        }

        lateinit var cursor: SynchronizationCursor
            private set
        val live = CompletableDeferred<Live>()

        suspend fun open(cursor: SynchronizationCursor?) {
            requests.send(
                SyncRequest.newBuilder().setOpen(
                    OpenSync.newBuilder().also { open ->
                        cursor?.let {
                            open.cursorBuilder.setGeneration(it.generation).setLastAppliedRevision(it.lastAppliedRevision)
                        }
                    }.build(),
                ).build(),
            )
        }

        suspend fun submit(command: EditCommand): ClientState {
            check(unconfirmedOutcome == null) { "An edit is already awaiting confirmation." }
            val outcome = CompletableDeferred<DurableOperationOutcome>()
            unconfirmedOperationId = command.operationId.value
            unconfirmedOutcome = outcome
            requests.send(
                SyncRequest.newBuilder().setSubmitOperation(
                    SubmitOperation.newBuilder().setOperation(command.toProto()).build(),
                ).build(),
            )
            return ready(outcome.await()).also {
                unconfirmedOperationId = null
                unconfirmedOutcome = null
            }
        }

        suspend fun close() {
            requests.close()
            responseJob.cancelAndJoin()
            channel.shutdownNow()
        }

        fun ready(outcome: DurableOperationOutcome? = null): ClientState.Ready =
            ClientState.Ready(
                enrollment = EnrollmentState.ENROLLED,
                connectivity = ConnectivityState.LIVE,
                canonicalState = canonicalState,
                cursor = cursor,
                lastOperationOutcome = outcome,
            )

        private suspend fun receive(response: SyncResponse) {
            when {
                response.hasEmptySnapshot() -> acceptSnapshot(
                    response.emptySnapshot.generation,
                    response.emptySnapshot.revision,
                    CanonicalState(),
                )

                response.hasSnapshot() -> acceptSnapshot(
                    response.snapshot.generation,
                    response.snapshot.revision,
                    CanonicalState(
                        response.snapshot.listsList.map { list ->
                            SharedList(
                                id = SharedListId.parse(list.id),
                                items = list.itemsList.sortedBy { it.position }.map { item ->
                                    ListItem(ListItemId.parse(item.id), item.marked, item.text)
                                },
                                name = list.name,
                            )
                        },
                    ),
                )

                response.hasJournalBatch() -> {
                    if (!::cursor.isInitialized) {
                        cursor = SynchronizationCursor(response.journalBatch.generation, 0)
                    }
                    response.journalBatch.entriesList.forEach { entry -> apply(entry) }
                }
                response.hasLive() -> {
                    if (!::cursor.isInitialized) {
                        cursor = SynchronizationCursor(response.live.generation, response.live.revision)
                        stateStore.save(canonicalState, cursor)
                    }
                    live.complete(response.live)
                }
                else -> error("Server sent an unknown synchronization response.")
            }
        }

        private suspend fun acceptSnapshot(generation: String, revision: Long, state: CanonicalState) {
            canonicalState = state
            cachedState = state
            cursor = SynchronizationCursor(generation, revision)
            persistAndAcknowledge()
        }

        private suspend fun apply(entry: JournalEntry) {
            val operation = entry.operation
            if (entry.revision <= cursor.lastAppliedRevision) {
                if (unconfirmedOutcome != null && operation.operationId == unconfirmedOperationId) {
                    unconfirmedOutcome?.complete(DurableOperationOutcome(OperationId.parse(operation.operationId), entry.outcome.toClientOutcome()))
                }
                return
            }
            check(entry.revision == cursor.lastAppliedRevision + 1) {
                "Server journal revisions must be contiguous."
            }
            val lists = canonicalState.lists.toMutableList()
            if (entry.outcome == ProtoOperationOutcome.OPERATION_OUTCOME_APPLIED) {
                when (operation.operationCase) {
                    ClientOperation.OperationCase.CREATE_LIST -> lists += SharedList(
                        id = SharedListId.parse(operation.createList.listId),
                        name = operation.createList.name,
                    )
                    ClientOperation.OperationCase.CREATE_ITEM -> {
                        val index = lists.indexOfFirst { it.id.value == operation.createItem.listId }
                        if (index >= 0) {
                            lists[index] = lists[index].copy(
                                items = lists[index].items + ListItem(
                                    id = ListItemId.parse(operation.createItem.itemId),
                                    text = operation.createItem.text,
                                ),
                            )
                        }
                    }
                    ClientOperation.OperationCase.DELETE_ITEM -> {
                        val index = lists.indexOfFirst { it.id.value == operation.deleteItem.listId }
                        if (index >= 0) {
                            lists[index] = lists[index].copy(
                                items = lists[index].items.filterNot { it.id.value == operation.deleteItem.itemId },
                            )
                        }
                    }
                    ClientOperation.OperationCase.RENAME_LIST -> {
                        val index = lists.indexOfFirst { it.id.value == operation.renameList.listId }
                        if (index >= 0) lists[index] = lists[index].copy(name = operation.renameList.name)
                    }
                    ClientOperation.OperationCase.EDIT_ITEM_TEXT -> {
                        val index = lists.indexOfFirst { it.id.value == operation.editItemText.listId }
                        if (index >= 0) {
                            lists[index] = lists[index].copy(
                                items = lists[index].items.map { item ->
                                    if (item.id.value == operation.editItemText.itemId) {
                                        item.copy(text = operation.editItemText.text)
                                    } else {
                                        item
                                    }
                                },
                            )
                        }
                    }
                    ClientOperation.OperationCase.DELETE_LIST -> lists.removeAll { it.id.value == operation.deleteList.listId }
                    ClientOperation.OperationCase.OPERATION_NOT_SET -> error("Journal entry has no operation.")
                }
                canonicalState = CanonicalState(lists.sortedBy { it.name.lowercase() })
                cachedState = canonicalState
            }
            cursor = SynchronizationCursor(cursor.generation, entry.revision)
            persistAndAcknowledge()
            if (unconfirmedOutcome != null && operation.operationId == unconfirmedOperationId) {
                unconfirmedOutcome?.complete(DurableOperationOutcome(OperationId.parse(operation.operationId), entry.outcome.toClientOutcome()))
            }
        }

        private suspend fun persistAndAcknowledge() {
            stateStore.save(canonicalState, cursor)
            requests.send(
                SyncRequest.newBuilder().setAppliedThrough(
                    AppliedThrough.newBuilder().setRevision(cursor.lastAppliedRevision).build(),
                ).build(),
            )
        }

        private fun fail(exception: Exception) {
            live.completeExceptionally(exception)
            unconfirmedOutcome?.completeExceptionally(exception)
        }

        private fun EditCommand.toProto(): ClientOperation =
            ClientOperation.newBuilder().setOperationId(operationId.value).apply {
                when (this@toProto) {
                    is CreateList -> setCreateList(ProtoCreateList.newBuilder().setListId(listId.value).setName(name))
                    is CreateItem -> setCreateItem(
                        ProtoCreateItem.newBuilder()
                            .setItemId(itemId.value)
                            .setListId(listId.value)
                            .setText(text),
                    )
                    is DeleteItem -> setDeleteItem(
                        ProtoDeleteItem.newBuilder()
                            .setItemId(itemId.value)
                            .setListId(listId.value),
                    )
                    is DeleteList -> setDeleteList(ProtoDeleteList.newBuilder().setListId(listId.value))
                    is EditItemText -> setEditItemText(
                        ProtoEditItemText.newBuilder()
                            .setItemId(itemId.value)
                            .setListId(listId.value)
                            .setText(text),
                    )
                    is RenameList -> setRenameList(ProtoRenameList.newBuilder().setListId(listId.value).setName(name))
                }
            }.build()
    }
}

private fun ProtoOperationOutcome.toClientOutcome(): OperationOutcome =
    when (this) {
        ProtoOperationOutcome.OPERATION_OUTCOME_APPLIED -> OperationOutcome.APPLIED
        ProtoOperationOutcome.OPERATION_OUTCOME_IGNORED -> OperationOutcome.IGNORED
        ProtoOperationOutcome.OPERATION_OUTCOME_REJECTED -> OperationOutcome.REJECTED
        ProtoOperationOutcome.OPERATION_OUTCOME_UNSPECIFIED,
        ProtoOperationOutcome.UNRECOGNIZED,
        -> error("Journal entry has no valid outcome.")
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

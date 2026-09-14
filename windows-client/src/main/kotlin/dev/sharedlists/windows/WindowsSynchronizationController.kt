package dev.sharedlists.windows

import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.CreateItem
import dev.sharedlists.client.CreateList
import dev.sharedlists.client.DeleteItem
import dev.sharedlists.client.DeleteList
import dev.sharedlists.client.DeviceSigner
import dev.sharedlists.client.EditItemText
import dev.sharedlists.client.EditCommand
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.FileClientStateStore
import dev.sharedlists.client.GrpcSharedListsClient
import dev.sharedlists.client.ListItemId
import dev.sharedlists.client.OperationId
import dev.sharedlists.client.OperationOutcome
import dev.sharedlists.client.RenameList
import dev.sharedlists.client.ServerEndpoint
import dev.sharedlists.client.SetMarked
import dev.sharedlists.client.SharedListsClient
import dev.sharedlists.client.SharedListId
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

data class ServerConfiguration(
    val host: String,
    val port: Int,
    val certificateFingerprint: String,
)

interface WindowsClientFactory {
    fun create(configuration: ServerConfiguration): SharedListsClient
}

data class WindowsListRow(
    val id: SharedListId,
    val name: String,
) {
    override fun toString(): String = name
}

interface WindowsDeviceSignerProvider {
    fun load(): DeviceSigner
}

fun interface SynchronizationRunner {
    fun run(block: () -> Unit)
}

object BackgroundSynchronizationRunner : SynchronizationRunner {
    override fun run(block: () -> Unit) {
        Thread(block).start()
    }
}

interface ServerConfigurationStore {
    fun load(): ServerConfiguration?

    fun save(configuration: ServerConfiguration)
}

class PreferencesServerConfigurationStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(PreferencesServerConfigurationStore::class.java),
) : ServerConfigurationStore {
    override fun load(): ServerConfiguration? =
        preferences.get(HOST, null)
            ?.let { host ->
                val port = preferences.getInt(PORT, INVALID_PORT)
                val fingerprint = preferences.get(FINGERPRINT, null)
                if (port in 1..65535 && fingerprint != null) {
                    ServerConfiguration(host, port, fingerprint)
                } else {
                    null
                }
            }

    override fun save(configuration: ServerConfiguration) {
        preferences.put(HOST, configuration.host)
        preferences.putInt(PORT, configuration.port)
        preferences.put(FINGERPRINT, configuration.certificateFingerprint)
    }

    private companion object {
        const val FINGERPRINT = "certificateFingerprint"
        const val HOST = "host"
        const val INVALID_PORT = -1
        const val PORT = "port"
    }
}

class WindowsGrpcClientFactory(
    private val deviceSigner: () -> DeviceSigner?,
    private val stateStore: File = File(System.getProperty("user.home"), ".sharedlists/client-state.properties"),
) : WindowsClientFactory {
    constructor(
        deviceSigner: DeviceSigner?,
        stateStore: File = File(System.getProperty("user.home"), ".sharedlists/client-state.properties"),
    ) : this({ deviceSigner }, stateStore)

    override fun create(configuration: ServerConfiguration): SharedListsClient =
        deviceSigner()?.let { signer ->
            GrpcSharedListsClient(
                deviceSigner = signer,
                endpoint = ServerEndpoint(
                    host = configuration.host,
                    port = configuration.port,
                    certificatePin = configuration.certificateFingerprint,
                ),
                stateStore = FileClientStateStore(stateStore),
            )
        } ?: UnconfiguredSharedListsClient
}

data class WindowsClientPresentation(
    val connectionActive: Boolean,
    val configuration: ServerConfiguration?,
    val deviceKeyFingerprint: String?,
    val editability: Editability,
    val emptyStateMessage: String?,
    val exportRequired: Boolean = false,
    val lists: List<String>,
    val sharedLists: List<WindowsListRow> = emptyList(),
    val statusMessage: String?,
    val setupRequired: Boolean,
    val unreadableDeviceKey: Boolean,
) {
    val editingEnabled: Boolean
        get() = editability == Editability.LIVE
}

enum class Editability {
    LIVE,
    READ_ONLY,
}

class WindowsSynchronizationController(
    private val clientFactory: WindowsClientFactory,
    private val configurationStore: ServerConfigurationStore = PreferencesServerConfigurationStore(),
    private val deviceEnrollment: WindowsDeviceEnrollment? = null,
    private val synchronizationRunner: SynchronizationRunner = BackgroundSynchronizationRunner,
) {
    private var cachedState = CanonicalState()
    private var connectionAttempt = 0L
    private var deviceKeyUnreadable = false
    private var liveClient: SharedListsClient? = null
    private var stateChanged: (WindowsClientPresentation) -> Unit = {}
    private val storedConfiguration = configurationStore.load()
    private var presentation = WindowsClientPresentation(
        connectionActive = false,
        configuration = storedConfiguration,
        deviceKeyFingerprint = deviceKeyFingerprint(),
        editability = Editability.READ_ONLY,
        emptyStateMessage = if (deviceKeyUnreadable) {
            "Device setup cannot be read. Retry after Windows key storage is available."
        } else if (!setupRequired()) {
            storedConfiguration?.let { "Connect to synchronize shared lists." } ?: "Configure a server to begin synchronization."
        } else {
            "Device setup is required before connecting."
        },
        lists = emptyList(),
        statusMessage = if (deviceKeyUnreadable) {
            "Device setup unavailable"
        } else if (!setupRequired()) {
            storedConfiguration?.let { "Ready to connect" } ?: "Not configured"
        } else {
            "Device setup required"
        },
        setupRequired = setupRequired(),
        unreadableDeviceKey = deviceKeyUnreadable,
    )

    fun createDeviceKey(host: String, port: String, certificateFingerprint: String) {
        val enrollment = requireNotNull(deviceEnrollment) { "Windows device enrollment is unavailable." }
        val configuration = parseConfiguration(host, port, certificateFingerprint)
        if (configuration == null) {
            update(
                presentation.copy(
                    emptyStateMessage = "Enter a server address, port, and SHA-256 fingerprint before device setup.",
                    statusMessage = "Configuration incomplete",
                ),
            )
            return
        }
        configurationStore.save(configuration)
        try {
            val signer = enrollment.create()
            update(
                presentation.copy(
                    configuration = configuration,
                    deviceKeyFingerprint = signer.keyFingerprint,
                    emptyStateMessage = "Export this device public key for the server administrator.",
                    statusMessage = "Device key created — export public key",
                    exportRequired = true,
                    setupRequired = false,
                ),
            )
        } catch (exception: IllegalStateException) {
            update(
                presentation.copy(
                    emptyStateMessage = "Device setup could not be completed. Retry after Windows key storage is available.",
                    statusMessage = "Device setup failed",
                ),
            )
        }
    }

    fun connect(host: String, port: String, certificateFingerprint: String) {
        val parsedConfiguration = parseConfiguration(host, port, certificateFingerprint)
        if (parsedConfiguration == null) {
            update(
                presentation.copy(
                    emptyStateMessage = "Enter a server address, port, and SHA-256 fingerprint.",
                    statusMessage = "Configuration incomplete",
                ),
            )
            return
        }
        configurationStore.save(parsedConfiguration)
        val attempt = nextConnectionAttempt()
        update(
            presentation.copy(
                connectionActive = true,
                configuration = parsedConfiguration,
                editability = Editability.READ_ONLY,
                emptyStateMessage = if (cachedState.lists.isEmpty()) "Connecting to synchronized lists…" else null,
                lists = cachedState.lists.map { list -> list.name },
                sharedLists = cachedState.lists.map { list -> WindowsListRow(list.id, list.name) },
                statusMessage = "Connecting…",
            ),
        )
        val client = clientFactory.create(parsedConfiguration)
        synchronizationRunner.run {
            suspend {
                client.synchronize()
            }.startCoroutine(
                object : Continuation<ClientState> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<ClientState>) {
                        if (!isCurrentConnectionAttempt(attempt)) {
                            return
                        }
                        result.fold(
                            onSuccess = {
                                liveClient = client
                                showClientState(it)
                            },
                            onFailure = { showConnectionFailure() },
                        )
                    }
                },
            )
        }
    }

    fun createList(name: String) {
        submit(name) { operationId -> CreateList(operationId, newListId(), name) }
    }

    fun createItem(listId: SharedListId, text: String) {
        val list = cachedState.lists.firstOrNull { it.id == listId } ?: return
        if (list.items.any { it.text == text.trim() }) {
            update(presentation.copy(statusMessage = "A matching item already exists; saving duplicate…"))
        }
        submit(text, ITEM_TEXT_LIMIT) { operationId ->
            CreateItem(
                itemId = newItemId(),
                listId = list.id,
                operationId = operationId,
                text = text,
            )
        }
    }

    fun deleteList(name: String) {
        val list = cachedState.lists.firstOrNull { it.name == name } ?: return
        submit(null) { operationId -> DeleteList(operationId, list.id) }
    }

    fun deleteItem(listId: SharedListId, itemId: ListItemId) {
        val list = cachedState.lists.firstOrNull { it.id == listId } ?: return
        if (list.items.none { it.id == itemId }) {
            return
        }
        submit(null) { operationId -> DeleteItem(itemId, list.id, operationId) }
    }

    fun editItemText(listId: SharedListId, itemId: ListItemId, newText: String) {
        val list = cachedState.lists.firstOrNull { it.id == listId } ?: return
        if (list.items.none { it.id == itemId }) {
            return
        }
        submit(newText, ITEM_TEXT_LIMIT) { operationId -> EditItemText(itemId, list.id, operationId, newText) }
    }

    fun setItemMarked(listId: SharedListId, itemId: ListItemId, value: Boolean) {
        val list = cachedState.lists.firstOrNull { it.id == listId } ?: return
        if (list.items.none { it.id == itemId }) {
            return
        }
        submit(null) { operationId -> SetMarked(itemId, list.id, operationId, value) }
    }

    fun items(listId: SharedListId) = cachedState.lists.firstOrNull { it.id == listId }?.items.orEmpty()

    fun observePresentation(observer: (WindowsClientPresentation) -> Unit) {
        stateChanged = observer
        observer(presentation)
    }

    fun presentation(): WindowsClientPresentation = presentation

    fun renameList(currentName: String, newName: String) {
        val list = cachedState.lists.firstOrNull { it.name == currentName } ?: return
        submit(newName) { operationId -> RenameList(operationId, list.id, newName) }
    }

    fun retryDeviceKey() {
        deviceKeyUnreadable = false
        val fingerprint = deviceKeyFingerprint()
        update(
            presentation.copy(
                deviceKeyFingerprint = fingerprint,
                emptyStateMessage = if (deviceKeyUnreadable) {
                    "Device setup cannot be read. Retry after Windows key storage is available."
                } else {
                    "Connect to synchronize shared lists."
                },
                statusMessage = if (deviceKeyUnreadable) "Device setup unavailable" else "Ready to connect",
                setupRequired = setupRequired(),
                unreadableDeviceKey = deviceKeyUnreadable,
            ),
        )
    }

    fun exportDevicePublicKey(file: File) {
        val enrollment = requireNotNull(deviceEnrollment) { "Windows device enrollment is unavailable." }
        try {
            enrollment.exportPublicKey(file)
            update(
                presentation.copy(
                    emptyStateMessage = "Give ${file.name} to the server administrator, restart the server, then connect.",
                    exportRequired = false,
                    statusMessage = "Public key exported: ${presentation.deviceKeyFingerprint}",
                ),
            )
        } catch (exception: IOException) {
            update(
                presentation.copy(
                    emptyStateMessage = "Public-key export failed. Retry using the same device key.",
                    exportRequired = true,
                    statusMessage = "Public-key export failed: ${exception.message}",
                ),
            )
        } catch (exception: IllegalStateException) {
            update(
                presentation.copy(
                    emptyStateMessage = "Public-key export failed. Retry using the same device key.",
                    exportRequired = true,
                    statusMessage = "Public-key export failed",
                ),
            )
        }
    }

    fun resetDeviceSetup() {
        val enrollment = requireNotNull(deviceEnrollment) { "Windows device enrollment is unavailable." }
        enrollment.delete()
        update(
            presentation.copy(
                connectionActive = false,
                deviceKeyFingerprint = null,
                editability = Editability.READ_ONLY,
                emptyStateMessage = "Device setup is required before connecting.",
                exportRequired = false,
                statusMessage = "Device setup reset",
                setupRequired = true,
            ),
        )
    }

    private fun parseConfiguration(
        host: String,
        port: String,
        certificateFingerprint: String,
    ): ServerConfiguration? {
        val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val normalizedFingerprint = certificateFingerprint.replace(":", "").uppercase()
        if (host.isBlank() || !FINGERPRINT.matches(normalizedFingerprint)) {
            return null
        }
        return ServerConfiguration(host.trim(), parsedPort, normalizedFingerprint)
    }

    private fun showClientState(clientState: ClientState) {
        when (clientState) {
            is ClientState.Failure -> showFailure(clientState.enrollment)
            is ClientState.Ready -> showReadyState(clientState)
        }
    }

    private fun showConnectionFailure() {
        update(
            presentation.copy(
                connectionActive = false,
                editability = Editability.READ_ONLY,
                emptyStateMessage = if (cachedState.lists.isEmpty()) "Unable to synchronize shared lists." else null,
                lists = cachedState.lists.map { list -> list.name },
                sharedLists = cachedState.lists.map { list -> WindowsListRow(list.id, list.name) },
                statusMessage = "Disconnected — retry when the server is available",
            ),
        )
    }

    private fun showFailure(enrollment: EnrollmentState) {
        update(
            presentation.copy(
                connectionActive = false,
                editability = Editability.READ_ONLY,
                emptyStateMessage = failureMessage(enrollment),
                lists = cachedState.lists.map { list -> list.name },
                sharedLists = cachedState.lists.map { list -> WindowsListRow(list.id, list.name) },
                statusMessage = "Synchronization unavailable",
            ),
        )
    }

    private fun showReadyState(clientState: ClientState.Ready) {
        cachedState = clientState.canonicalState
        val isLive = clientState.editingEnabled
        update(
            presentation.copy(
                connectionActive = false,
                editability = if (isLive) Editability.LIVE else Editability.READ_ONLY,
                emptyStateMessage = emptyStateMessage(clientState, isLive),
                lists = cachedState.lists.map { list -> list.name },
                sharedLists = cachedState.lists.map { list -> WindowsListRow(list.id, list.name) },
                statusMessage = statusMessage(clientState, isLive),
            ),
        )
        clientState.lastOperationOutcome?.let { outcome ->
            update(
                presentation.copy(
                    statusMessage = when (outcome.outcome) {
                        OperationOutcome.APPLIED -> "Saved"
                        OperationOutcome.IGNORED -> "No change: the list or item was deleted."
                        OperationOutcome.REJECTED -> "Not saved: invalid list name or item text."
                    },
                ),
            )
        }
    }

    private fun newListId(): SharedListId = SharedListId.parse(UUID.randomUUID().toString())

    private fun newItemId(): ListItemId = ListItemId.parse(UUID.randomUUID().toString())

    private fun newOperationId(): OperationId = OperationId.parse(UUID.randomUUID().toString())

    private fun emptyStateMessage(clientState: ClientState.Ready, isLive: Boolean): String? =
        when {
            cachedState.lists.isNotEmpty() -> null
            isLive -> "No shared lists yet."
            clientState.connectivity == ConnectivityState.SYNCHRONIZING -> "Synchronizing shared lists…"
            else -> "No synchronized lists are available yet."
        }

    private fun failureMessage(enrollment: EnrollmentState): String =
        when (enrollment) {
            EnrollmentState.UNCONFIGURED -> "Device setup is required before connecting."
            EnrollmentState.UNENROLLED -> "This device is not enrolled with the server."
            EnrollmentState.UNREADABLE_DEVICE_KEY -> "Device setup cannot be read. Retry after it is available."
            EnrollmentState.ENROLLED -> "Unable to synchronize shared lists."
        }

    private fun statusMessage(clientState: ClientState.Ready, isLive: Boolean): String? =
        when {
            isLive -> "Device enrolled and synchronized"
            clientState.connectivity == ConnectivityState.CONNECTING -> "Connecting…"
            clientState.connectivity == ConnectivityState.SYNCHRONIZING -> "Synchronizing…"
            else -> "Disconnected — cached lists are read-only"
        }

    private fun submit(
        name: String?,
        maximumLength: Int = LIST_NAME_LIMIT,
        command: (OperationId) -> EditCommand,
    ) {
        if (!presentation.editingEnabled) {
            update(presentation.copy(statusMessage = "Editing is available only while synchronized."))
            return
        }
        if (name != null && !validText(name, maximumLength)) {
            val itemOrList = if (maximumLength == ITEM_TEXT_LIMIT) "item text" else "list name"
            update(presentation.copy(statusMessage = "Enter $itemOrList of at most $maximumLength characters."))
            return
        }
        val client = requireNotNull(liveClient) { "Live client is missing." }
        update(presentation.copy(editability = Editability.READ_ONLY, statusMessage = "Saving…"))
        synchronizationRunner.run {
            suspend { client.submit(command(newOperationId())) }.startCoroutine(
                object : Continuation<ClientState> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<ClientState>) {
                        result.fold(
                            onSuccess = ::showClientState,
                            onFailure = {
                                update(presentation.copy(editability = Editability.READ_ONLY, statusMessage = "Unable to save change."))
                            },
                        )
                    }
                },
            )
        }
    }

    private fun validText(text: String, maximumLength: Int): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= maximumLength
    }

    @Synchronized
    private fun isCurrentConnectionAttempt(attempt: Long): Boolean = attempt == connectionAttempt

    @Synchronized
    private fun nextConnectionAttempt(): Long {
        connectionAttempt += 1
        return connectionAttempt
    }

    private fun update(nextPresentation: WindowsClientPresentation) {
        presentation = nextPresentation
        stateChanged(nextPresentation)
    }

    private fun deviceKeyFingerprint(): String? =
        try {
            deviceEnrollment?.current()?.keyFingerprint
        } catch (exception: UnreadableDeviceKeyException) {
            deviceKeyUnreadable = true
            null
        }

    private fun setupRequired(): Boolean =
        !deviceKeyUnreadable && deviceEnrollment != null && deviceKeyFingerprint() == null

    companion object {
        private const val ITEM_TEXT_LIMIT = 500
        private const val LIST_NAME_LIMIT = 100
        private val FINGERPRINT = Regex("^[0-9A-F]{64}$")
    }
}

private object UnconfiguredSharedListsClient : SharedListsClient {
    override suspend fun submit(command: EditCommand): ClientState =
        unconfiguredState()

    override suspend fun synchronize(): ClientState = unconfiguredState()

    private fun unconfiguredState(): ClientState =
        ClientState.Failure(
            enrollment = EnrollmentState.UNCONFIGURED,
            connectivity = ConnectivityState.OFFLINE,
            reason = "Windows device enrollment is not configured.",
        )
}

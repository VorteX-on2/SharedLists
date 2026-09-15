package dev.sharedlists.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.sharedlists.client.CachedSharedListsClient
import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.CreateItem
import dev.sharedlists.client.CreateList
import dev.sharedlists.client.DeleteItem
import dev.sharedlists.client.DeleteList
import dev.sharedlists.client.EditCommand
import dev.sharedlists.client.EditItemText
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.FileClientStateStore
import dev.sharedlists.client.ForegroundSharedListsClient
import dev.sharedlists.client.GrpcSharedListsClient
import dev.sharedlists.client.LocalStateResettableClient
import dev.sharedlists.client.ObservableSharedListsClient
import dev.sharedlists.client.ListItemId
import dev.sharedlists.client.MoveItem
import dev.sharedlists.client.OperationId
import dev.sharedlists.client.RenameList
import dev.sharedlists.client.ServerEndpoint
import dev.sharedlists.client.SharedListsClient
import dev.sharedlists.client.SharedListId
import dev.sharedlists.client.SetMarked
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AndroidServerConfiguration(
    val host: String,
    val port: Int,
    val certificateFingerprint: String,
)

class AndroidServerConfigurationStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("sharedlists-server", Context.MODE_PRIVATE)

    fun load(): AndroidServerConfiguration? {
        val host = preferences.getString(HOST, null) ?: return null
        val fingerprint = preferences.getString(FINGERPRINT, null) ?: return null
        return AndroidServerConfiguration(host, preferences.getInt(PORT, -1), fingerprint)
            .takeIf { it.host.isNotBlank() && it.port in 1..65535 && FINGERPRINT_PATTERN.matches(it.certificateFingerprint) }
    }

    fun save(configuration: AndroidServerConfiguration) {
        preferences.edit().putString(HOST, configuration.host).putInt(PORT, configuration.port)
            .putString(FINGERPRINT, configuration.certificateFingerprint).apply()
    }

    private companion object {
        const val FINGERPRINT = "certificateFingerprint"
        const val HOST = "host"
        const val PORT = "port"
        val FINGERPRINT_PATTERN = Regex("^[0-9A-F]{64}$")
    }
}

data class AndroidClientPresentation(
    val canonicalState: CanonicalState = CanonicalState(),
    val configuration: AndroidServerConfiguration? = null,
    val editingEnabled: Boolean = false,
    val enrollment: EnrollmentState = EnrollmentState.UNCONFIGURED,
    val exportRequired: Boolean = false,
    val status: String = "Device setup is required.",
)

class AndroidSynchronizationController(
    context: Context,
    private val enrollment: AndroidDeviceEnrollment = AndroidKeystoreDeviceEnrollment(context),
    private val configurationStore: AndroidServerConfigurationStore = AndroidServerConfigurationStore(context),
    private val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.IO),
) {
    private val stateDirectory = File(context.filesDir, "synchronization")
    private var client: SharedListsClient? = null
    private var foreground = false
    private var networkAvailable = true
    private var observer: (AndroidClientPresentation) -> Unit = {}
    private var presentation = AndroidClientPresentation(configuration = configurationStore.load())
    private var retryJob: Job? = null
    private var synchronizationJob: Job? = null

    init {
        refreshEnrollment()
    }

    fun configure(host: String, port: String, fingerprint: String): Boolean {
        val configuration = parseConfiguration(host, port, fingerprint) ?: return false
        configurationStore.save(configuration)
        presentation = presentation.copy(configuration = configuration, status = "Server configured.")
        publish()
        startIfEligible()
        return true
    }

    fun createDeviceKey() {
        presentation = try {
            enrollment.create()
            presentation.copy(
                enrollment = EnrollmentState.UNENROLLED,
                exportRequired = true,
                status = "Device key created. Export its public key for enrollment.",
            )
        } catch (exception: UnreadableAndroidDeviceKeyException) {
            presentation.copy(enrollment = EnrollmentState.UNREADABLE_DEVICE_KEY, status = "Android Keystore is unavailable. Retry without reset.")
        } catch (exception: IllegalStateException) {
            presentation.copy(
                enrollment = EnrollmentState.UNENROLLED,
                exportRequired = true,
                status = "A device key already exists. Export its public key for enrollment.",
            )
        }
        publish()
    }

    fun createItem(listId: SharedListId, text: String) {
        submit(text, ITEM_TEXT_LIMIT) { operationId ->
            CreateItem(ListItemId.parse(UUID.randomUUID().toString()), listId, operationId, text.trim())
        }
    }

    fun createList(name: String) {
        submit(name, LIST_NAME_LIMIT) { operationId ->
            CreateList(operationId, SharedListId.parse(UUID.randomUUID().toString()), name.trim())
        }
    }

    fun deleteItem(listId: SharedListId, itemId: ListItemId) {
        submit { operationId -> DeleteItem(itemId, listId, operationId) }
    }

    fun deleteList(listId: SharedListId) {
        submit { operationId -> DeleteList(operationId, listId) }
    }

    fun editItemText(listId: SharedListId, itemId: ListItemId, text: String) {
        submit(text, ITEM_TEXT_LIMIT) { operationId -> EditItemText(itemId, listId, operationId, text.trim()) }
    }

    fun exportPublicKey(): Pair<String, String> {
        val signer = requireNotNull(enrollment.current()) { "Create a device key before exporting it." }
        presentation = presentation.copy(exportRequired = false, status = "Public key ready to share with the administrator.")
        publish()
        return "sharedlists-${Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "-")}-${signer.keyFingerprint.take(12)}.pem" to signer.publicKeyPem
    }

    fun observe(observer: (AndroidClientPresentation) -> Unit) {
        this.observer = observer
        observer(presentation)
    }

    fun onForeground() {
        foreground = true
        startIfEligible()
    }

    fun onBackground() {
        foreground = false
        retryJob?.cancel()
        cancelClient()
        presentation = presentation.copy(editingEnabled = false, status = "Synchronization paused in the background.")
        publish()
    }

    fun onNetworkAvailable(available: Boolean) {
        networkAvailable = available
        if (available) startIfEligible() else {
            cancelClient()
            presentation = presentation.copy(editingEnabled = false, status = "Waiting for a network connection.")
            publish()
        }
    }

    fun moveItem(listId: SharedListId, itemId: ListItemId, destinationIndex: Int) {
        val items = presentation.canonicalState.lists.firstOrNull { it.id == listId }?.items ?: return
        val remaining = items.filterNot { it.id == itemId }
        val index = destinationIndex.coerceIn(0, remaining.size)
        submit { operationId ->
            MoveItem(itemId, listId, operationId, remaining.getOrNull(index - 1)?.id, remaining.getOrNull(index)?.id)
        }
    }

    fun moveItem(
        listId: SharedListId,
        itemId: ListItemId,
        predecessorItemId: ListItemId?,
        successorItemId: ListItemId?,
    ) {
        submit { operationId -> MoveItem(itemId, listId, operationId, predecessorItemId, successorItemId) }
    }

    fun renameList(listId: SharedListId, name: String) {
        submit(name, LIST_NAME_LIMIT) { operationId -> RenameList(operationId, listId, name.trim()) }
    }

    fun resetLocalData() {
        (client as? LocalStateResettableClient)?.resetLocalState()
        presentation = presentation.copy(canonicalState = CanonicalState(), editingEnabled = false, status = "Local synchronization data reset.")
        publish()
        startIfEligible()
    }

    fun resetDeviceSetup() {
        onBackground()
        enrollment.delete()
        presentation = presentation.copy(enrollment = EnrollmentState.UNCONFIGURED, exportRequired = false, status = "Device setup reset.")
        publish()
    }

    fun retryDeviceKey() {
        refreshEnrollment()
        publish()
        startIfEligible()
    }

    fun setMarked(listId: SharedListId, itemId: ListItemId, value: Boolean) {
        submit { operationId -> SetMarked(itemId, listId, operationId, value) }
    }

    private fun cancelClient() {
        synchronizationJob?.cancel()
        synchronizationJob = null
        (client as? ForegroundSharedListsClient)?.cancelForegroundSynchronization()
        client = null
    }

    private fun startIfEligible() {
        if (!foreground || !networkAvailable) return
        val configuration = presentation.configuration ?: return
        val signer = try {
            enrollment.current()
        } catch (exception: UnreadableAndroidDeviceKeyException) {
            presentation = presentation.copy(enrollment = EnrollmentState.UNREADABLE_DEVICE_KEY, status = "Android Keystore is unavailable.")
            publish()
            return
        }
        if (signer == null) {
            presentation = presentation.copy(enrollment = EnrollmentState.UNCONFIGURED, status = "Create and export a device key to begin.")
            publish()
            return
        }
        connect(configuration, signer)
    }

    private fun connect(configuration: AndroidServerConfiguration, signer: AndroidDeviceSigner) {
        retryJob?.cancel()
        cancelClient()
        val nextClient = GrpcSharedListsClient(
            channelFactory = AndroidPinnedChannelFactory()::create,
            deviceSigner = signer,
            endpoint = ServerEndpoint(configuration.host, configuration.port, configuration.certificateFingerprint),
            stateStore = FileClientStateStore(stateFile(configuration, signer)),
        )
        client = nextClient
        presentation = presentation.copy(
            canonicalState = (nextClient as CachedSharedListsClient).cachedCanonicalState(),
            editingEnabled = false,
            enrollment = EnrollmentState.ENROLLED,
            status = "Connecting…",
        )
        publish()
        synchronizationJob = scope.launch {
            try {
                showState(nextClient.synchronize())
                (nextClient as ObservableSharedListsClient).observeState(::showState)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (client !== nextClient) return@launch
                if (isRevokedEnrollment(exception)) {
                    presentation = presentation.copy(
                        editingEnabled = false,
                        enrollment = EnrollmentState.UNENROLLED,
                        status = "Device enrollment was revoked. Export the public key for administrator enrollment.",
                    )
                    publish()
                    return@launch
                }
                presentation = presentation.copy(editingEnabled = false, status = "Disconnected — retrying when available.")
                publish()
                if (foreground && networkAvailable) scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(RETRY_DELAY_MILLIS)
            startIfEligible()
        }
    }

    private fun submit(command: (OperationId) -> EditCommand) {
        submit(null, 0, command)
    }

    private fun submit(text: String?, maximumLength: Int, command: (OperationId) -> EditCommand) {
        val liveClient = client
        if (!presentation.editingEnabled || liveClient == null) {
            presentation = presentation.copy(status = "Editing is available only while synchronized.")
            publish()
            return
        }
        if (text != null && (text.trim().isEmpty() || text.trim().codePointCount(0, text.trim().length) > maximumLength)) {
            presentation = presentation.copy(status = "Enter text within the allowed length.")
            publish()
            return
        }
        presentation = presentation.copy(editingEnabled = false, status = "Saving…")
        publish()
        scope.launch {
            try {
                showState(liveClient.submit(command(OperationId.parse(UUID.randomUUID().toString()))))
            } catch (exception: Exception) {
                presentation = presentation.copy(editingEnabled = false, status = "Unable to save change.")
                publish()
            }
        }
    }

    private fun showState(state: ClientState) {
        if (state !is ClientState.Ready) {
            presentation = presentation.copy(editingEnabled = false, status = "Device enrollment is required.")
        } else {
            presentation = presentation.copy(
                canonicalState = state.canonicalState,
                editingEnabled = state.editingEnabled,
                enrollment = state.enrollment,
                status = when (state.connectivity) {
                    ConnectivityState.LIVE -> "Synchronized"
                    ConnectivityState.FATAL -> "Server requires administrator attention."
                    ConnectivityState.SUPERSEDED -> "Synchronization was superseded. Return and retry to take over."
                    else -> "Synchronizing…"
                },
            )
            if (state.connectivity == ConnectivityState.FAILED && foreground && networkAvailable) scheduleRetry()
        }
        publish()
    }

    private fun parseConfiguration(host: String, port: String, fingerprint: String): AndroidServerConfiguration? {
        val normalizedFingerprint = fingerprint.replace(":", "").uppercase()
        val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || !FINGERPRINT_PATTERN.matches(normalizedFingerprint)) return null
        return AndroidServerConfiguration(host.trim(), parsedPort, normalizedFingerprint)
    }

    private fun publish() = observer(presentation)

    private fun stateFile(configuration: AndroidServerConfiguration, signer: AndroidDeviceSigner): File =
        File(stateDirectory, "${configuration.certificateFingerprint.lowercase()}-${signer.keyFingerprint}.properties")

    private fun isRevokedEnrollment(exception: Exception): Boolean =
        when (exception) {
            is StatusException -> exception.status.code == Status.Code.PERMISSION_DENIED
            is StatusRuntimeException -> exception.status.code == Status.Code.PERMISSION_DENIED
            else -> false
        }

    private fun refreshEnrollment() {
        presentation = try {
            val signer = enrollment.current()
            if (signer == null) {
                presentation.copy(enrollment = EnrollmentState.UNCONFIGURED, exportRequired = false)
            } else {
                presentation.copy(
                    enrollment = EnrollmentState.UNENROLLED,
                    exportRequired = true,
                    status = "Export this device public key for server enrollment.",
                )
            }
        } catch (exception: UnreadableAndroidDeviceKeyException) {
            presentation.copy(enrollment = EnrollmentState.UNREADABLE_DEVICE_KEY, exportRequired = false, status = "Android Keystore is unavailable.")
        }
    }

    private companion object {
        const val ITEM_TEXT_LIMIT = 500
        const val LIST_NAME_LIMIT = 100
        const val RETRY_DELAY_MILLIS = 5_000L
        val FINGERPRINT_PATTERN = Regex("^[0-9A-F]{64}$")
    }

}

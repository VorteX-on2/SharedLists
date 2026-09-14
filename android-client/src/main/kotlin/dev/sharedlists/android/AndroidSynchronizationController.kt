package dev.sharedlists.android

import android.content.Context
import android.content.SharedPreferences
import dev.sharedlists.client.CachedSharedListsClient
import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.FileClientStateStore
import dev.sharedlists.client.ForegroundSharedListsClient
import dev.sharedlists.client.GrpcSharedListsClient
import dev.sharedlists.client.LocalStateResettableClient
import dev.sharedlists.client.ObservableSharedListsClient
import dev.sharedlists.client.ServerEndpoint
import dev.sharedlists.client.SharedListsClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
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
    private val stateFile = File(context.filesDir, "synchronization/state.properties")
    private var client: SharedListsClient? = null
    private var foreground = false
    private var networkAvailable = true
    private var observer: (AndroidClientPresentation) -> Unit = {}
    private var presentation = AndroidClientPresentation(configuration = configurationStore.load())
    private var retryJob: Job? = null

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
        }
        publish()
    }

    fun exportPublicKey(): Pair<String, String> {
        val signer = requireNotNull(enrollment.current()) { "Create a device key before exporting it." }
        presentation = presentation.copy(exportRequired = false, status = "Public key ready to share with the administrator.")
        publish()
        return "sharedlists-${android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "-")}-${signer.keyFingerprint.take(12)}.pem" to signer.publicKeyPem
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

    private fun cancelClient() {
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
            deviceSigner = signer,
            endpoint = ServerEndpoint(configuration.host, configuration.port, configuration.certificateFingerprint),
            stateStore = FileClientStateStore(stateFile),
        )
        client = nextClient
        presentation = presentation.copy(
            canonicalState = (nextClient as CachedSharedListsClient).cachedCanonicalState(),
            editingEnabled = false,
            enrollment = EnrollmentState.ENROLLED,
            status = "Connecting…",
        )
        publish()
        scope.launch {
            try {
                showState(nextClient.synchronize())
                (nextClient as ObservableSharedListsClient).observeState(::showState)
            } catch (exception: Exception) {
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

    private companion object {
        const val RETRY_DELAY_MILLIS = 5_000L
        val FINGERPRINT_PATTERN = Regex("^[0-9A-F]{64}$")
    }
}

package dev.sharedlists.windows

import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.EditCommand
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.SharedListsClient
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

object UnconfiguredWindowsClientFactory : WindowsClientFactory {
    override fun create(configuration: ServerConfiguration): SharedListsClient =
        UnconfiguredSharedListsClient
}

data class WindowsClientPresentation(
    val configuration: ServerConfiguration?,
    val editability: Editability,
    val emptyStateMessage: String?,
    val lists: List<String>,
    val statusMessage: String?,
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
    private val synchronizationRunner: SynchronizationRunner = BackgroundSynchronizationRunner,
) {
    private var cachedState = CanonicalState()
    private var stateChanged: (WindowsClientPresentation) -> Unit = {}
    private val storedConfiguration = configurationStore.load()
    private var presentation = WindowsClientPresentation(
        configuration = storedConfiguration,
        editability = Editability.READ_ONLY,
        emptyStateMessage = storedConfiguration
            ?.let { "Connect to synchronize shared lists." }
            ?: "Configure a server to begin synchronization.",
        lists = emptyList(),
        statusMessage = storedConfiguration?.let { "Ready to connect" } ?: "Not configured",
    )

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
        update(
            presentation.copy(
                configuration = parsedConfiguration,
                editability = Editability.READ_ONLY,
                emptyStateMessage = if (cachedState.lists.isEmpty()) "Connecting to synchronized lists…" else null,
                lists = cachedState.lists.map { list -> list.name },
                statusMessage = "Connecting…",
            ),
        )
        synchronizationRunner.run {
            suspend {
                clientFactory.create(parsedConfiguration).synchronize()
            }.startCoroutine(
                object : Continuation<ClientState> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<ClientState>) {
                        result.fold(
                            onSuccess = ::showClientState,
                            onFailure = { showConnectionFailure() },
                        )
                    }
                },
            )
        }
    }

    fun observePresentation(observer: (WindowsClientPresentation) -> Unit) {
        stateChanged = observer
        observer(presentation)
    }

    fun presentation(): WindowsClientPresentation = presentation

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
                editability = Editability.READ_ONLY,
                emptyStateMessage = if (cachedState.lists.isEmpty()) "Unable to synchronize shared lists." else null,
                lists = cachedState.lists.map { list -> list.name },
                statusMessage = "Disconnected — retry when the server is available",
            ),
        )
    }

    private fun showFailure(enrollment: EnrollmentState) {
        update(
            presentation.copy(
                editability = Editability.READ_ONLY,
                emptyStateMessage = failureMessage(enrollment),
                lists = cachedState.lists.map { list -> list.name },
                statusMessage = "Synchronization unavailable",
            ),
        )
    }

    private fun showReadyState(clientState: ClientState.Ready) {
        cachedState = clientState.canonicalState
        val isLive = clientState.editingEnabled
        update(
            presentation.copy(
                editability = if (isLive) Editability.LIVE else Editability.READ_ONLY,
                emptyStateMessage = emptyStateMessage(clientState, isLive),
                lists = cachedState.lists.map { list -> list.name },
                statusMessage = statusMessage(clientState, isLive),
            ),
        )
    }

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
            isLive -> null
            clientState.connectivity == ConnectivityState.CONNECTING -> "Connecting…"
            clientState.connectivity == ConnectivityState.SYNCHRONIZING -> "Synchronizing…"
            else -> "Disconnected — cached lists are read-only"
        }

    private fun update(nextPresentation: WindowsClientPresentation) {
        presentation = nextPresentation
        stateChanged(nextPresentation)
    }

    companion object {
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

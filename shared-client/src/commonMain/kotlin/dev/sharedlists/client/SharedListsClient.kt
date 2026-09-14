package dev.sharedlists.client

data class SynchronizationCursor(
    val generation: String,
    val lastAppliedRevision: Long,
)

data class CanonicalState(
    val lists: List<SharedList> = emptyList(),
)

data class SharedList(
    val id: String,
    val name: String,
)

enum class EnrollmentState {
    UNCONFIGURED,
    UNENROLLED,
    ENROLLED,
    UNREADABLE_DEVICE_KEY,
}

enum class ConnectivityState {
    OFFLINE,
    CONNECTING,
    SYNCHRONIZING,
    LIVE,
    FAILED,
}

sealed interface ClientState {
    data class Ready(
        val enrollment: EnrollmentState,
        val connectivity: ConnectivityState,
        val canonicalState: CanonicalState,
        val cursor: SynchronizationCursor?,
        val editingEnabled: Boolean,
    ) : ClientState

    data class Failure(
        val enrollment: EnrollmentState,
        val connectivity: ConnectivityState,
        val reason: String,
    ) : ClientState
}

sealed interface EditCommand {
    val operationId: String
}

interface SharedListsClient {
    suspend fun synchronize(): ClientState

    suspend fun submit(command: EditCommand): ClientState
}

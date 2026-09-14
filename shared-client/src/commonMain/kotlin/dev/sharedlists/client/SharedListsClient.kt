package dev.sharedlists.client

import kotlin.jvm.JvmInline

@JvmInline
value class SharedListId private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): SharedListId = SharedListId(requireUuidV4(value))
    }
}

@JvmInline
value class OperationId private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): OperationId = OperationId(requireUuidV4(value))
    }
}

private fun requireUuidV4(value: String): String {
    require(UUID_V4.matches(value)) { "Expected a canonical UUIDv4." }
    return value
}

private val UUID_V4 =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

data class SynchronizationCursor(
    val generation: String,
    val lastAppliedRevision: Long,
)

data class CanonicalState(
    val lists: List<SharedList> = emptyList(),
)

data class SharedList(
    val id: SharedListId,
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
    ) : ClientState {
        val editingEnabled: Boolean
            get() = enrollment == EnrollmentState.ENROLLED && connectivity == ConnectivityState.LIVE
    }

    data class Failure(
        val enrollment: EnrollmentState,
        val connectivity: ConnectivityState,
        val reason: String,
    ) : ClientState
}

interface EditCommand {
    val operationId: OperationId
}

interface SharedListsClient {
    suspend fun synchronize(): ClientState

    suspend fun submit(command: EditCommand): ClientState
}

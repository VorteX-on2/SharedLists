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

@JvmInline
value class ListItemId private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): ListItemId = ListItemId(requireUuidV4(value))
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
    val items: List<ListItem> = emptyList(),
    val name: String,
) {
    constructor(
        id: SharedListId,
        name: String,
    ) : this(id, emptyList(), name)
}

data class ListItem(
    val id: ListItemId,
    val marked: Boolean = false,
    val text: String,
)

data class DurableOperationOutcome(
    val operationId: OperationId,
    val outcome: OperationOutcome,
    val reason: String? = null,
)

enum class OperationOutcome {
    APPLIED,
    IGNORED,
    REJECTED,
}

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
    FATAL,
    SUPERSEDED,
}

sealed interface ClientState {
    data class Ready(
        val enrollment: EnrollmentState,
        val connectivity: ConnectivityState,
        val canonicalState: CanonicalState,
        val cursor: SynchronizationCursor?,
        val lastOperationOutcome: DurableOperationOutcome? = null,
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

data class CreateList(
    override val operationId: OperationId,
    val listId: SharedListId,
    val name: String,
) : EditCommand

data class DeleteList(
    override val operationId: OperationId,
    val listId: SharedListId,
) : EditCommand

data class RenameList(
    override val operationId: OperationId,
    val listId: SharedListId,
    val name: String,
) : EditCommand

data class CreateItem(
    val itemId: ListItemId,
    val listId: SharedListId,
    override val operationId: OperationId,
    val text: String,
) : EditCommand

data class DeleteItem(
    val itemId: ListItemId,
    val listId: SharedListId,
    override val operationId: OperationId,
) : EditCommand

data class EditItemText(
    val itemId: ListItemId,
    val listId: SharedListId,
    override val operationId: OperationId,
    val text: String,
) : EditCommand

data class SetMarked(
    val itemId: ListItemId,
    val listId: SharedListId,
    override val operationId: OperationId,
    val value: Boolean,
) : EditCommand

data class MoveItem(
    val itemId: ListItemId,
    val listId: SharedListId,
    override val operationId: OperationId,
    val predecessorItemId: ListItemId?,
    val successorItemId: ListItemId?,
) : EditCommand

interface SharedListsClient {
    suspend fun synchronize(): ClientState

    suspend fun submit(command: EditCommand): ClientState
}

interface ForegroundSharedListsClient {
    fun cancelForegroundSynchronization()
}

interface CachedSharedListsClient {
    fun cachedCanonicalState(): CanonicalState
}

interface LocalStateResettableClient {
    fun resetLocalState()
}

interface ObservableSharedListsClient {
    fun observeState(observer: (ClientState.Ready) -> Unit)
}

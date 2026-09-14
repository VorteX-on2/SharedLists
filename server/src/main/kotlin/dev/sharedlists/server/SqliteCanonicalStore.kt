package dev.sharedlists.server

import dev.sharedlists.protocol.ClientOperation
import dev.sharedlists.protocol.CreateList
import dev.sharedlists.protocol.DeleteList
import dev.sharedlists.protocol.JournalEntry
import dev.sharedlists.protocol.ListTombstone
import dev.sharedlists.protocol.OperationOutcome
import dev.sharedlists.protocol.RenameList
import dev.sharedlists.protocol.SharedList
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

internal class SqliteCanonicalStore(
    databaseFile: Path,
) : AutoCloseable {
    private val connection: Connection
    private val lock = Any()
    private val _journalEntries = MutableSharedFlow<JournalEntry>(extraBufferCapacity = 256)

    val journalEntries: SharedFlow<JournalEntry> = _journalEntries

    init {
        Files.createDirectories(requireNotNull(databaseFile.parent))
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = DELETE")
            statement.execute("PRAGMA synchronous = FULL")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS synchronization_metadata (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    generation TEXT NOT NULL,
                    head_revision INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS shared_lists (
                    id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL UNIQUE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS list_tombstones (
                    list_id TEXT PRIMARY KEY,
                    deleted_revision INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS operation_journal (
                    revision INTEGER PRIMARY KEY,
                    operation_id TEXT NOT NULL UNIQUE,
                    request_fingerprint TEXT NOT NULL,
                    operation_type INTEGER NOT NULL,
                    list_id TEXT NOT NULL,
                    list_name TEXT NOT NULL,
                    outcome INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        connection.prepareStatement(
            "INSERT OR IGNORE INTO synchronization_metadata (singleton, generation, head_revision) VALUES (1, ?, 0)",
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.executeUpdate()
        }
    }

    fun snapshot(): CanonicalSnapshot = synchronized(lock) {
        val metadata = metadata()
        val lists = connection.prepareStatement(
            "SELECT id, display_name FROM shared_lists ORDER BY normalized_name, id",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SharedList.newBuilder()
                                .setId(result.getString("id"))
                                .setName(result.getString("display_name"))
                                .build(),
                        )
                    }
                }
            }
        }
        val tombstones = connection.prepareStatement(
            "SELECT list_id, deleted_revision FROM list_tombstones ORDER BY list_id",
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            ListTombstone.newBuilder()
                                .setListId(result.getString("list_id"))
                                .setDeletedRevision(result.getLong("deleted_revision"))
                                .build(),
                        )
                    }
                }
            }
        }
        metadata.copy(lists = lists, tombstones = tombstones)
    }

    fun journalAfter(revision: Long): List<JournalEntry> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT revision, operation_id, operation_type, list_id, list_name, outcome
            FROM operation_journal WHERE revision > ? ORDER BY revision
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, revision)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.journalEntry())
                    }
                }
            }
        }
    }

    fun submit(operation: ClientOperation): JournalEntry {
        validateUuidV4(operation.operationId, "operation ID")
        val entry = synchronized(lock) {
            connection.autoCommit = false
            try {
                existing(operation)?.let { return@synchronized it }
                val resolved = resolve(operation)
                val revision = metadata().revision + 1
                apply(resolved, revision)
                connection.prepareStatement(
                    """
                    INSERT INTO operation_journal
                    (revision, operation_id, request_fingerprint, operation_type, list_id, list_name, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, revision)
                    statement.setString(2, operation.operationId)
                    statement.setString(3, operationFingerprint(operation))
                    statement.setInt(4, resolved.type.ordinal)
                    statement.setString(5, resolved.listId)
                    statement.setString(6, resolved.name)
                    statement.setInt(7, resolved.outcome.number)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE synchronization_metadata SET head_revision = ? WHERE singleton = 1",
                ).use { statement ->
                    statement.setLong(1, revision)
                    check(statement.executeUpdate() == 1)
                }
                connection.commit()
                resolved.toEntry(revision, operation.operationId)
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = true
            }
        }
        _journalEntries.tryEmit(entry)
        return entry
    }

    override fun close() {
        synchronized(lock) {
            connection.close()
        }
    }

    private fun existing(operation: ClientOperation): JournalEntry? =
        connection.prepareStatement(
            """
            SELECT revision, operation_id, request_fingerprint, operation_type, list_id, list_name, outcome
            FROM operation_journal WHERE operation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, operation.operationId)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    val entry = result.journalEntry()
                    if (result.getString("request_fingerprint") != operationFingerprint(operation)) {
                        throw OperationIdReuseException()
                    }
                    entry
                }
            }
        }

    private fun resolve(operation: ClientOperation): ResolvedOperation =
        when (operation.operationCase) {
            ClientOperation.OperationCase.CREATE_LIST -> {
                validateUuidV4(operation.createList.listId, "list ID")
                val listId = operation.createList.listId
                when {
                    isTombstoned(listId) || listExists(listId) ->
                        ResolvedOperation(OperationOutcome.OPERATION_OUTCOME_IGNORED, Type.CREATE, listId, operation.createList.name)
                    else -> resolveName(operation.createList.name).let { name ->
                        ResolvedOperation(name.outcome, Type.CREATE, listId, name.value)
                    }
                }
            }

            ClientOperation.OperationCase.RENAME_LIST -> {
                validateUuidV4(operation.renameList.listId, "list ID")
                val listId = operation.renameList.listId
                when {
                    isTombstoned(listId) || !listExists(listId) ->
                        ResolvedOperation(OperationOutcome.OPERATION_OUTCOME_IGNORED, Type.RENAME, listId, operation.renameList.name)
                    else -> resolveName(operation.renameList.name, listId).let { name ->
                        ResolvedOperation(name.outcome, Type.RENAME, listId, name.value)
                    }
                }
            }

            ClientOperation.OperationCase.DELETE_LIST -> {
                validateUuidV4(operation.deleteList.listId, "list ID")
                val listId = operation.deleteList.listId
                ResolvedOperation(
                    if (isTombstoned(listId) || !listExists(listId)) {
                        OperationOutcome.OPERATION_OUTCOME_IGNORED
                    } else {
                        OperationOutcome.OPERATION_OUTCOME_APPLIED
                    },
                    Type.DELETE,
                    listId,
                    "",
                )
            }

            ClientOperation.OperationCase.OPERATION_NOT_SET ->
                throw IllegalArgumentException("operation is required")
        }

    private fun resolveName(value: String, excludedListId: String? = null): ResolvedName {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > MAXIMUM_NAME_CODE_POINTS) {
            return ResolvedName(OperationOutcome.OPERATION_OUTCOME_REJECTED, normalized)
        }
        var candidate = normalized
        var suffix = 2
        while (nameExists(candidate, excludedListId)) {
            val postfix = " ($suffix)"
            candidate = truncateCodePoints(normalized, MAXIMUM_NAME_CODE_POINTS - postfix.codePointCount(0, postfix.length)) + postfix
            suffix += 1
        }
        return ResolvedName(OperationOutcome.OPERATION_OUTCOME_APPLIED, candidate)
    }

    private fun apply(operation: ResolvedOperation, revision: Long) {
        if (operation.outcome != OperationOutcome.OPERATION_OUTCOME_APPLIED) return
        when (operation.type) {
            Type.CREATE -> connection.prepareStatement(
                "INSERT INTO shared_lists (id, display_name, normalized_name) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, operation.listId)
                statement.setString(2, operation.name)
                statement.setString(3, fold(operation.name))
                statement.executeUpdate()
            }

            Type.RENAME -> connection.prepareStatement(
                "UPDATE shared_lists SET display_name = ?, normalized_name = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, operation.name)
                statement.setString(2, fold(operation.name))
                statement.setString(3, operation.listId)
                statement.executeUpdate()
            }

            Type.DELETE -> {
                connection.prepareStatement("DELETE FROM shared_lists WHERE id = ?").use { statement ->
                    statement.setString(1, operation.listId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO list_tombstones (list_id, deleted_revision) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, operation.listId)
                    statement.setLong(2, revision)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun metadata(): CanonicalSnapshot =
        connection.prepareStatement(
            "SELECT generation, head_revision FROM synchronization_metadata WHERE singleton = 1",
        ).use { statement ->
            statement.executeQuery().use { result ->
                check(result.next()) { "Synchronization metadata is missing." }
                CanonicalSnapshot(result.getString("generation"), result.getLong("head_revision"))
            }
        }

    private fun listExists(id: String): Boolean = exists("SELECT 1 FROM shared_lists WHERE id = ?", id)

    private fun isTombstoned(id: String): Boolean = exists("SELECT 1 FROM list_tombstones WHERE list_id = ?", id)

    private fun nameExists(name: String, excludedListId: String?): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM shared_lists WHERE normalized_name = ? AND id != COALESCE(?, '')",
        ).use { statement ->
            statement.setString(1, fold(name))
            statement.setString(2, excludedListId)
            statement.executeQuery().use { it.next() }
        }

    private fun exists(query: String, value: String): Boolean =
        connection.prepareStatement(query).use { statement ->
            statement.setString(1, value)
            statement.executeQuery().use { it.next() }
        }

    private fun java.sql.ResultSet.journalEntry(): JournalEntry {
        val type = Type.fromNumber(getInt("operation_type"))
        val operation = ClientOperation.newBuilder().setOperationId(getString("operation_id")).apply {
            when (type) {
                Type.CREATE -> setCreateList(
                    CreateList.newBuilder()
                        .setListId(getString("list_id")).setName(getString("list_name")),
                )
                Type.RENAME -> setRenameList(
                    RenameList.newBuilder()
                        .setListId(getString("list_id")).setName(getString("list_name")),
                )
                Type.DELETE -> setDeleteList(DeleteList.newBuilder().setListId(getString("list_id")))
            }
        }.build()
        return JournalEntry.newBuilder()
            .setRevision(getLong("revision"))
            .setOperation(operation)
            .setOutcome(OperationOutcome.forNumber(getInt("outcome")))
            .build()
    }

    private enum class Type {
        CREATE,
        RENAME,
        DELETE;

        companion object {
            fun fromNumber(number: Int): Type = entries[number]
        }
    }

    private data class ResolvedOperation(
        val outcome: OperationOutcome,
        val type: Type,
        val listId: String,
        val name: String,
    ) {
        fun toEntry(revision: Long, operationId: String): JournalEntry {
            val operation = ClientOperation.newBuilder().setOperationId(operationId).apply {
                when (type) {
                    Type.CREATE -> setCreateList(CreateList.newBuilder().setListId(listId).setName(name))
                    Type.RENAME -> setRenameList(RenameList.newBuilder().setListId(listId).setName(name))
                    Type.DELETE -> setDeleteList(DeleteList.newBuilder().setListId(listId))
                }
            }.build()
            return JournalEntry.newBuilder().setRevision(revision).setOperation(operation).setOutcome(outcome).build()
        }
    }

    private data class ResolvedName(
        val outcome: OperationOutcome,
        val value: String,
    )

    private companion object {
        const val MAXIMUM_NAME_CODE_POINTS = 100

        fun fold(value: String): String = value.lowercase(Locale.ROOT)

        fun truncateCodePoints(value: String, count: Int): String {
            val end = value.offsetByCodePoints(0, minOf(count, value.codePointCount(0, value.length)))
            return value.substring(0, end)
        }

        fun validateUuidV4(value: String, field: String) {
            val uuid = try {
                UUID.fromString(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("$field must be a UUIDv4")
            }
            require(uuid.toString() == value && uuid.version() == 4 && uuid.variant() == 2) {
                "$field must be a UUIDv4"
            }

        }
    }

    private fun operationFingerprint(operation: ClientOperation): String =
        when (operation.operationCase) {
            ClientOperation.OperationCase.CREATE_LIST ->
                "create\u0000${operation.createList.listId}\u0000${operation.createList.name}"
            ClientOperation.OperationCase.RENAME_LIST ->
                "rename\u0000${operation.renameList.listId}\u0000${operation.renameList.name}"
            ClientOperation.OperationCase.DELETE_LIST -> "delete\u0000${operation.deleteList.listId}"
            ClientOperation.OperationCase.OPERATION_NOT_SET -> "unset"
        }
}

internal class OperationIdReuseException : RuntimeException()

internal data class CanonicalSnapshot(
    val generation: String,
    val revision: Long,
    val lists: List<SharedList> = emptyList(),
    val tombstones: List<ListTombstone> = emptyList(),
)

package dev.sharedlists.server

import dev.sharedlists.protocol.ClientOperation
import dev.sharedlists.protocol.CreateItem
import dev.sharedlists.protocol.CreateList
import dev.sharedlists.protocol.DeleteItem
import dev.sharedlists.protocol.DeleteList
import dev.sharedlists.protocol.EditItemText
import dev.sharedlists.protocol.JournalEntry
import dev.sharedlists.protocol.ListItem
import dev.sharedlists.protocol.ListTombstone
import dev.sharedlists.protocol.MoveItem
import dev.sharedlists.protocol.OperationOutcome
import dev.sharedlists.protocol.RenameList
import dev.sharedlists.protocol.SetMarked
import dev.sharedlists.protocol.SharedList
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
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
        val existingDatabase = Files.exists(databaseFile)
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = DELETE")
            statement.execute("PRAGMA synchronous = FULL")
            if (existingDatabase) {
                require(statement.executeQuery("PRAGMA quick_check").use { result -> result.next() && result.getString(1) == "ok" }) {
                    "SQLite integrity check failed."
                }
                require(isSupportedSchema(statement)) { "SQLite schema is unsupported; restore a matching whole-installation backup." }
            }
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
                CREATE TABLE IF NOT EXISTS list_items (
                    id TEXT PRIMARY KEY,
                    list_id TEXT NOT NULL REFERENCES shared_lists(id) ON DELETE CASCADE,
                    text TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    marked INTEGER NOT NULL DEFAULT 0,
                    UNIQUE (list_id, position)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS item_tombstones (
                    item_id TEXT PRIMARY KEY,
                    deleted_revision INTEGER NOT NULL
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
                    item_id TEXT NOT NULL,
                    item_text TEXT NOT NULL,
                    marked_value INTEGER NOT NULL,
                    predecessor_item_id TEXT NOT NULL DEFAULT '',
                    successor_item_id TEXT NOT NULL DEFAULT '',
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
                                .addAllItems(items(result.getString("id")))
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

    fun journalAfter(revision: Long, limit: Int = Int.MAX_VALUE): List<JournalEntry> = synchronized(lock) {
        journalAfterLocked(revision, limit)
    }

    fun catchUpAfter(revision: Long, limit: Int = Int.MAX_VALUE): CanonicalCatchUp = synchronized(lock) {
        CanonicalCatchUp(metadata(), journalAfterLocked(revision, limit))
    }

    private fun journalAfterLocked(revision: Long, limit: Int): List<JournalEntry> {
        require(limit > 0)
        return connection.prepareStatement(
            """
            SELECT revision, operation_id, operation_type, list_id, list_name, item_id, item_text,
                   marked_value, predecessor_item_id, successor_item_id, outcome
            FROM operation_journal WHERE revision > ? ORDER BY revision LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, revision)
            statement.setInt(2, limit)
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
                    (revision, operation_id, request_fingerprint, operation_type, list_id, list_name, item_id, item_text, marked_value, predecessor_item_id, successor_item_id, outcome)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, revision)
                    statement.setString(2, operation.operationId)
                    statement.setString(3, operationFingerprint(operation))
                    statement.setInt(4, resolved.type.ordinal)
                    statement.setString(5, resolved.listId)
                    statement.setString(6, resolved.name)
                    statement.setString(7, resolved.itemId)
                    statement.setString(8, resolved.itemText)
                    statement.setBoolean(9, resolved.marked)
                    statement.setString(10, resolved.predecessorItemId)
                    statement.setString(11, resolved.successorItemId)
                    statement.setInt(12, resolved.outcome.number)
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

    private fun isSupportedSchema(statement: Statement): Boolean {
        val expectedTables = setOf(
            "synchronization_metadata",
            "list_items",
            "item_tombstones",
            "shared_lists",
            "list_tombstones",
            "operation_journal",
        )
        val actualTables = statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        ).use { result ->
            buildSet {
                while (result.next()) add(result.getString(1))
            }
        }
        return actualTables == expectedTables &&
            columns(statement, "operation_journal") == setOf(
                "revision", "operation_id", "request_fingerprint", "operation_type", "list_id", "list_name",
                "item_id", "item_text", "marked_value", "predecessor_item_id", "successor_item_id", "outcome",
            )
    }

    private fun columns(statement: Statement, table: String): Set<String> =
        statement.executeQuery("PRAGMA table_info($table)").use { result ->
            buildSet {
                while (result.next()) add(result.getString("name"))
            }
        }

    private fun existing(operation: ClientOperation): JournalEntry? =
        connection.prepareStatement(
            """
            SELECT revision, operation_id, request_fingerprint, operation_type, list_id, list_name, item_id, item_text,
                   marked_value, predecessor_item_id, successor_item_id, outcome
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

            ClientOperation.OperationCase.CREATE_ITEM -> {
                validateUuidV4(operation.createItem.listId, "list ID")
                validateUuidV4(operation.createItem.itemId, "item ID")
                val listId = operation.createItem.listId
                val itemId = operation.createItem.itemId
                when {
                    isTombstoned(listId) || !listExists(listId) || isItemTombstoned(itemId) || itemExists(itemId) ->
                        ResolvedOperation(OperationOutcome.OPERATION_OUTCOME_IGNORED, Type.CREATE_ITEM, listId, "", itemId, operation.createItem.text)
                    else -> resolveText(operation.createItem.text).let { text ->
                        ResolvedOperation(text.outcome, Type.CREATE_ITEM, listId, "", itemId, text.value)
                    }
                }
            }

            ClientOperation.OperationCase.EDIT_ITEM_TEXT -> {
                validateUuidV4(operation.editItemText.listId, "list ID")
                validateUuidV4(operation.editItemText.itemId, "item ID")
                val listId = operation.editItemText.listId
                val itemId = operation.editItemText.itemId
                when {
                    isTombstoned(listId) || !listExists(listId) || isItemTombstoned(itemId) || !itemExists(itemId, listId) ->
                        ResolvedOperation(OperationOutcome.OPERATION_OUTCOME_IGNORED, Type.EDIT_ITEM_TEXT, listId, "", itemId, operation.editItemText.text)
                    else -> resolveText(operation.editItemText.text).let { text ->
                        ResolvedOperation(text.outcome, Type.EDIT_ITEM_TEXT, listId, "", itemId, text.value)
                    }
                }
            }

            ClientOperation.OperationCase.SET_MARKED -> {
                validateUuidV4(operation.setMarked.listId, "list ID")
                validateUuidV4(operation.setMarked.itemId, "item ID")
                val listId = operation.setMarked.listId
                val itemId = operation.setMarked.itemId
                ResolvedOperation(
                    if (isTombstoned(listId) || !listExists(listId) || isItemTombstoned(itemId) || !itemExists(itemId, listId)) {
                        OperationOutcome.OPERATION_OUTCOME_IGNORED
                    } else {
                        OperationOutcome.OPERATION_OUTCOME_APPLIED
                    },
                    Type.SET_MARKED,
                    listId,
                    "",
                    itemId,
                    marked = operation.setMarked.value,
                )
            }

            ClientOperation.OperationCase.DELETE_ITEM -> {
                validateUuidV4(operation.deleteItem.listId, "list ID")
                validateUuidV4(operation.deleteItem.itemId, "item ID")
                val listId = operation.deleteItem.listId
                val itemId = operation.deleteItem.itemId
                ResolvedOperation(
                    if (isTombstoned(listId) || !listExists(listId) || isItemTombstoned(itemId) || !itemExists(itemId, listId)) {
                        OperationOutcome.OPERATION_OUTCOME_IGNORED
                    } else {
                        OperationOutcome.OPERATION_OUTCOME_APPLIED
                    },
                    Type.DELETE_ITEM,
                    listId,
                    "",
                    itemId,
                    "",
                )
            }

            ClientOperation.OperationCase.MOVE_ITEM -> {
                validateUuidV4(operation.moveItem.listId, "list ID")
                validateUuidV4(operation.moveItem.itemId, "item ID")
                operation.moveItem.predecessorItemId.takeIf { it.isNotEmpty() }?.also { validateUuidV4(it, "predecessor item ID") }
                operation.moveItem.successorItemId.takeIf { it.isNotEmpty() }?.also { validateUuidV4(it, "successor item ID") }
                val move = operation.moveItem
                val anchorsCrossLists = listOf(move.predecessorItemId, move.successorItemId)
                    .filter { it.isNotEmpty() }
                    .any { anchor -> itemExists(anchor) && !itemExists(anchor, move.listId) }
                if (
                    isTombstoned(move.listId) || !listExists(move.listId) ||
                    isItemTombstoned(move.itemId) || !itemExists(move.itemId, move.listId)
                ) {
                    ResolvedOperation(
                        if (itemExists(move.itemId)) OperationOutcome.OPERATION_OUTCOME_REJECTED else OperationOutcome.OPERATION_OUTCOME_IGNORED,
                        Type.MOVE_ITEM,
                        move.listId,
                        "",
                        move.itemId,
                    )
                } else if (anchorsCrossLists) {
                    ResolvedOperation(OperationOutcome.OPERATION_OUTCOME_REJECTED, Type.MOVE_ITEM, move.listId, "", move.itemId)
                } else {
                    resolveMove(move)
                }
            }

            ClientOperation.OperationCase.OPERATION_NOT_SET ->
                throw IllegalArgumentException("operation is required")
        }

    private fun resolveMove(move: MoveItem): ResolvedOperation {
        val remaining = items(move.listId).filterNot { it.id == move.itemId }
        val predecessor = move.predecessorItemId.takeIf { anchor -> remaining.any { it.id == anchor } }
        val successor = move.successorItemId.takeIf { anchor -> remaining.any { it.id == anchor } }
        // Prefer an intact adjacent anchor pair; otherwise prefer the surviving predecessor, successor, then end.
        val insertionIndex = when {
            predecessor != null && successor != null &&
                remaining.indexOfFirst { it.id == predecessor } + 1 == remaining.indexOfFirst { it.id == successor } ->
                remaining.indexOfFirst { it.id == successor }
            predecessor != null -> remaining.indexOfFirst { it.id == predecessor } + 1
            successor != null -> remaining.indexOfFirst { it.id == successor }
            else -> remaining.size
        }
        return ResolvedOperation(
            outcome = OperationOutcome.OPERATION_OUTCOME_APPLIED,
            type = Type.MOVE_ITEM,
            listId = move.listId,
            name = "",
            itemId = move.itemId,
            predecessorItemId = remaining.getOrNull(insertionIndex - 1)?.id.orEmpty(),
            successorItemId = remaining.getOrNull(insertionIndex)?.id.orEmpty(),
        )
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

    private fun resolveText(value: String): ResolvedName {
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        return if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length) > MAXIMUM_ITEM_TEXT_CODE_POINTS) {
            ResolvedName(OperationOutcome.OPERATION_OUTCOME_REJECTED, normalized)
        } else {
            ResolvedName(OperationOutcome.OPERATION_OUTCOME_APPLIED, normalized)
        }
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

            Type.CREATE_ITEM -> connection.prepareStatement(
                """
                INSERT INTO list_items (id, list_id, text, position, marked)
                VALUES (?, ?, ?, COALESCE((SELECT MAX(position) + 1 FROM list_items WHERE list_id = ?), 0), 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, operation.itemId)
                statement.setString(2, operation.listId)
                statement.setString(3, operation.itemText)
                statement.setString(4, operation.listId)
                statement.executeUpdate()
            }

            Type.EDIT_ITEM_TEXT -> connection.prepareStatement(
                "UPDATE list_items SET text = ? WHERE id = ? AND list_id = ?",
            ).use { statement ->
                statement.setString(1, operation.itemText)
                statement.setString(2, operation.itemId)
                statement.setString(3, operation.listId)
                statement.executeUpdate()
            }

            Type.SET_MARKED -> connection.prepareStatement(
                "UPDATE list_items SET marked = ? WHERE id = ? AND list_id = ?",
            ).use { statement ->
                statement.setBoolean(1, operation.marked)
                statement.setString(2, operation.itemId)
                statement.setString(3, operation.listId)
                statement.executeUpdate()
            }

            Type.DELETE_ITEM -> {
                connection.prepareStatement("DELETE FROM list_items WHERE id = ?").use { statement ->
                    statement.setString(1, operation.itemId)
                    statement.executeUpdate()
                }
                writePositions(operation.listId, items(operation.listId).map { it.id })
                connection.prepareStatement(
                    "INSERT INTO item_tombstones (item_id, deleted_revision) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, operation.itemId)
                    statement.setLong(2, revision)
                    statement.executeUpdate()
                }
            }

            Type.MOVE_ITEM -> {
                val orderedIds = items(operation.listId).map { it.id }.filterNot { it == operation.itemId }.toMutableList()
                val insertionIndex = operation.successorItemId.takeIf { it.isNotEmpty() }
                    ?.let { successor -> orderedIds.indexOf(successor).takeIf { it >= 0 } }
                    ?: operation.predecessorItemId.takeIf { it.isNotEmpty() }
                        ?.let { predecessor -> orderedIds.indexOf(predecessor).takeIf { it >= 0 }?.plus(1) }
                    ?: orderedIds.size
                orderedIds.add(insertionIndex, operation.itemId)
                writePositions(operation.listId, orderedIds)
            }
        }
    }

    private fun writePositions(listId: String, itemIds: List<String>) {
        connection.prepareStatement("UPDATE list_items SET position = -position - 1 WHERE list_id = ?").use { statement ->
            statement.setString(1, listId)
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE list_items SET position = ? WHERE id = ? AND list_id = ?").use { statement ->
            itemIds.forEachIndexed { position, itemId ->
                statement.setInt(1, position)
                statement.setString(2, itemId)
                statement.setString(3, listId)
                statement.addBatch()
            }
            statement.executeBatch()
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

    private fun addJournalAnchorColumns(statement: Statement) {
        val columns = statement.executeQuery("PRAGMA table_info(operation_journal)").use { result ->
            buildSet {
                while (result.next()) add(result.getString("name"))
            }
        }
        if ("predecessor_item_id" !in columns) {
            statement.execute("ALTER TABLE operation_journal ADD COLUMN predecessor_item_id TEXT NOT NULL DEFAULT ''")
        }
        if ("successor_item_id" !in columns) {
            statement.execute("ALTER TABLE operation_journal ADD COLUMN successor_item_id TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun listExists(id: String): Boolean = exists("SELECT 1 FROM shared_lists WHERE id = ?", id)
    private fun isTombstoned(id: String): Boolean = exists("SELECT 1 FROM list_tombstones WHERE list_id = ?", id)
    private fun isItemTombstoned(id: String): Boolean = exists("SELECT 1 FROM item_tombstones WHERE item_id = ?", id)
    private fun itemExists(id: String, listId: String? = null): Boolean =
        if (listId == null) {
            exists("SELECT 1 FROM list_items WHERE id = ?", id)
        } else {
            connection.prepareStatement("SELECT 1 FROM list_items WHERE id = ? AND list_id = ?").use { statement ->
                statement.setString(1, id)
                statement.setString(2, listId)
                statement.executeQuery().use { it.next() }
            }
        }

    private fun items(listId: String): List<ListItem> =
        connection.prepareStatement(
            "SELECT id, text, position, marked FROM list_items WHERE list_id = ? ORDER BY position",
        ).use { statement ->
            statement.setString(1, listId)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            ListItem.newBuilder()
                                .setId(result.getString("id"))
                                .setMarked(result.getBoolean("marked"))
                                .setPosition(result.getInt("position"))
                                .setText(result.getString("text"))
                                .build(),
                        )
                    }
                }
            }
        }

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

    private fun operationJournalColumns(): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(operation_journal)").use { result ->
                buildSet {
                    while (result.next()) {
                        add(result.getString("name"))
                    }
                }
            }
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
                Type.CREATE_ITEM -> setCreateItem(
                    CreateItem.newBuilder().setListId(getString("list_id")).setItemId(getString("item_id")).setText(getString("item_text")),
                )
                Type.EDIT_ITEM_TEXT -> setEditItemText(
                    EditItemText.newBuilder().setListId(getString("list_id")).setItemId(getString("item_id")).setText(getString("item_text")),
                )
                Type.SET_MARKED -> setSetMarked(
                    SetMarked.newBuilder()
                        .setListId(getString("list_id"))
                        .setItemId(getString("item_id"))
                        .setValue(getBoolean("marked_value")),
                )
                Type.DELETE_ITEM -> setDeleteItem(
                    DeleteItem.newBuilder().setListId(getString("list_id")).setItemId(getString("item_id")),
                )
                Type.MOVE_ITEM -> setMoveItem(
                    MoveItem.newBuilder().setListId(getString("list_id")).setItemId(getString("item_id"))
                        .also { move ->
                            getString("predecessor_item_id").takeIf { it.isNotEmpty() }?.let(move::setPredecessorItemId)
                            getString("successor_item_id").takeIf { it.isNotEmpty() }?.let(move::setSuccessorItemId)
                        },
                )
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
        DELETE,
        CREATE_ITEM,
        DELETE_ITEM,
        EDIT_ITEM_TEXT,
        RENAME,
        SET_MARKED,
        MOVE_ITEM;

        companion object {
            fun fromNumber(number: Int): Type = entries[number]
        }
    }

    private data class ResolvedOperation(
        val outcome: OperationOutcome,
        val type: Type,
        val listId: String,
        val name: String,
        val itemId: String = "",
        val itemText: String = "",
        val marked: Boolean = false,
        val predecessorItemId: String = "",
        val successorItemId: String = "",
    ) {
        fun toEntry(revision: Long, operationId: String): JournalEntry {
            val operation = ClientOperation.newBuilder().setOperationId(operationId).apply {
                when (type) {
                    Type.CREATE -> setCreateList(CreateList.newBuilder().setListId(listId).setName(name))
                    Type.RENAME -> setRenameList(RenameList.newBuilder().setListId(listId).setName(name))
                    Type.DELETE -> setDeleteList(DeleteList.newBuilder().setListId(listId))
                    Type.CREATE_ITEM -> setCreateItem(
                        CreateItem.newBuilder().setListId(listId).setItemId(itemId).setText(itemText),
                    )
                    Type.EDIT_ITEM_TEXT -> setEditItemText(
                        EditItemText.newBuilder().setListId(listId).setItemId(itemId).setText(itemText),
                    )
                    Type.SET_MARKED -> setSetMarked(
                        SetMarked.newBuilder().setListId(listId).setItemId(itemId).setValue(marked),
                    )
                    Type.DELETE_ITEM -> setDeleteItem(
                        DeleteItem.newBuilder().setListId(listId).setItemId(itemId),
                    )
                    Type.MOVE_ITEM -> setMoveItem(
                        MoveItem.newBuilder().setListId(listId).setItemId(itemId).also { move ->
                            predecessorItemId.takeIf { it.isNotEmpty() }?.let(move::setPredecessorItemId)
                            successorItemId.takeIf { it.isNotEmpty() }?.let(move::setSuccessorItemId)
                        },
                    )
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
        const val MAXIMUM_ITEM_TEXT_CODE_POINTS = 500
        const val MAXIMUM_NAME_CODE_POINTS = 100

        fun fold(value: String): String =
            value.uppercase(Locale.ROOT)
                .lowercase(Locale.ROOT)
                .replace('\u03C2', '\u03C3')

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
            ClientOperation.OperationCase.CREATE_ITEM ->
                "create-item\u0000${operation.createItem.listId}\u0000${operation.createItem.itemId}\u0000${operation.createItem.text}"
            ClientOperation.OperationCase.EDIT_ITEM_TEXT ->
                "edit-item-text\u0000${operation.editItemText.listId}\u0000${operation.editItemText.itemId}\u0000${operation.editItemText.text}"
            ClientOperation.OperationCase.SET_MARKED ->
                "set-marked\u0000${operation.setMarked.listId}\u0000${operation.setMarked.itemId}\u0000${operation.setMarked.value}"
            ClientOperation.OperationCase.DELETE_ITEM ->
                "delete-item\u0000${operation.deleteItem.listId}\u0000${operation.deleteItem.itemId}"
            ClientOperation.OperationCase.MOVE_ITEM ->
                "move-item\u0000${operation.moveItem.listId}\u0000${operation.moveItem.itemId}\u0000${operation.moveItem.predecessorItemId}\u0000${operation.moveItem.successorItemId}"
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

internal data class CanonicalCatchUp(
    val snapshot: CanonicalSnapshot,
    val journalEntries: List<JournalEntry>,
)

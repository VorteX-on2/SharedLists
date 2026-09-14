package dev.sharedlists.server

import dev.sharedlists.protocol.ClientOperation
import dev.sharedlists.protocol.CreateList
import dev.sharedlists.protocol.DeleteList
import dev.sharedlists.protocol.OperationOutcome
import dev.sharedlists.protocol.RenameList
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteCanonicalStoreTest {
    @Test
    fun `persists canonical names journal and terminal tombstones`() {
        fixture().use { fixture ->
            val listId = "11111111-1111-4111-8111-111111111111"
            val created = fixture.store.submit(create("21111111-1111-4111-8111-111111111111", listId, "  Café  "))
            val collision = fixture.store.submit(create("31111111-1111-4111-8111-111111111111", "41111111-1111-4111-8111-111111111111", "cafe\u0301"))
            val renamed = fixture.store.submit(rename("51111111-1111-4111-8111-111111111111", listId, "  Groceries  "))
            val deleted = fixture.store.submit(delete("61111111-1111-4111-8111-111111111111", listId))
            val stale = fixture.store.submit(rename("71111111-1111-4111-8111-111111111111", listId, "Resurrected"))

            assertEquals(OperationOutcome.OPERATION_OUTCOME_APPLIED, created.outcome)
            assertEquals("Café", created.operation.createList.name)
            assertEquals("café (2)", collision.operation.createList.name)
            assertEquals("Groceries", renamed.operation.renameList.name)
            assertEquals(OperationOutcome.OPERATION_OUTCOME_APPLIED, deleted.outcome)
            assertEquals(OperationOutcome.OPERATION_OUTCOME_IGNORED, stale.outcome)
            assertEquals(5, fixture.store.snapshot().revision)
            assertTrue(fixture.store.snapshot().lists.none { it.id == listId })
            assertEquals(listId, fixture.store.snapshot().tombstones.single().listId)
            assertEquals(5, fixture.store.journalAfter(0).size)
            fixture.store.catchUpAfter(0).also { catchUp ->
                assertEquals(catchUp.snapshot.revision, catchUp.journalEntries.last().revision)
            }

            fixture.reopen()
            assertEquals(5, fixture.store.snapshot().revision)
            assertEquals(5, fixture.store.journalAfter(0).size)
            assertEquals(listId, fixture.store.snapshot().tombstones.single().listId)
        }
    }

    @Test
    fun `rejects invalid names and mismatched operation identifier reuse`() {
        fixture().use { fixture ->
            val listId = "11111111-1111-4111-8111-111111111111"
            val operationId = "21111111-1111-4111-8111-111111111111"
            val rejected = fixture.store.submit(create(operationId, listId, " "))

            assertEquals(OperationOutcome.OPERATION_OUTCOME_REJECTED, rejected.outcome)
            assertEquals(rejected, fixture.store.submit(create(operationId, listId, " ")))
            assertFailsWith<OperationIdReuseException> {
                fixture.store.submit(create(operationId, listId, "Different"))
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.store.submit(create("31111111-1111-4111-8111-111111111111", "not-a-uuid", "Name"))
            }
        }
    }

    @Test
    fun `uses a Unicode case fold approximation for name uniqueness`() {
        fixture().use { fixture ->
            fixture.store.submit(
                create(
                    "21111111-1111-4111-8111-111111111111",
                    "11111111-1111-4111-8111-111111111111",
                    "Straße",
                ),
            )

            val collision = fixture.store.submit(
                create(
                    "31111111-1111-4111-8111-111111111111",
                    "41111111-1111-4111-8111-111111111111",
                    "STRASSE",
                ),
            )

            assertEquals("STRASSE (2)", collision.operation.createList.name)
        }
    }

    private fun create(operationId: String, listId: String, name: String): ClientOperation =
        ClientOperation.newBuilder().setOperationId(operationId)
            .setCreateList(CreateList.newBuilder().setListId(listId).setName(name)).build()

    private fun delete(operationId: String, listId: String): ClientOperation =
        ClientOperation.newBuilder().setOperationId(operationId)
            .setDeleteList(DeleteList.newBuilder().setListId(listId)).build()

    private fun rename(operationId: String, listId: String, name: String): ClientOperation =
        ClientOperation.newBuilder().setOperationId(operationId)
            .setRenameList(RenameList.newBuilder().setListId(listId).setName(name)).build()
}

private class StoreFixture : AutoCloseable {
    private val directory = Files.createDirectories(Path.of("build", "test-store-${System.nanoTime()}"))
    private val database = directory.resolve("sharedlists.db")

    var store = SqliteCanonicalStore(database)
        private set

    fun reopen() {
        store.close()
        store = SqliteCanonicalStore(database)
    }

    override fun close() {
        store.close()
        directory.toFile().deleteRecursively()
    }
}

private fun fixture(): StoreFixture = StoreFixture()

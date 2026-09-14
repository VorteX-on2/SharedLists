package dev.sharedlists.server

import dev.sharedlists.protocol.AppliedThrough
import dev.sharedlists.protocol.ClientOperation
import dev.sharedlists.protocol.CreateList
import dev.sharedlists.protocol.OpenSync
import dev.sharedlists.protocol.SubmitOperation
import dev.sharedlists.protocol.SyncRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class SharedListsServiceTest {
    @Test
    fun `transitions from live to submitted authoritative journal entry`() = runBlocking {
        fixture().use { fixture ->
            val responses = SharedListsService(ChallengeAuthenticator("test", emptyMap()), fixture.store).sync(
                flow {
                    emit(SyncRequest.newBuilder().setOpen(OpenSync.getDefaultInstance()).build())
                    emit(SyncRequest.newBuilder().setAppliedThrough(AppliedThrough.newBuilder().setRevision(0)).build())
                    emit(
                        SyncRequest.newBuilder().setSubmitOperation(
                            SubmitOperation.newBuilder().setOperation(
                                ClientOperation.newBuilder()
                                    .setOperationId("21111111-1111-4111-8111-111111111111")
                                    .setCreateList(
                                        CreateList.newBuilder()
                                            .setListId("11111111-1111-4111-8111-111111111111")
                                            .setName("  Groceries  "),
                                    ),
                            ),
                        ).build(),
                    )
                    emit(SyncRequest.newBuilder().setAppliedThrough(AppliedThrough.newBuilder().setRevision(1)).build())
                },
            ).toList()

            assertTrue(responses.any { it.hasLive() })
            val journal = responses.single { it.hasJournalBatch() }.journalBatch.entriesList.single()
            assertEquals("Groceries", journal.operation.createList.name)
            assertEquals(1, journal.revision)
        }
    }
}

private class ServiceFixture : AutoCloseable {
    private val directory = Files.createDirectories(Path.of("build", "test-service-${System.nanoTime()}"))

    val store = SqliteCanonicalStore(directory.resolve("sharedlists.db"))

    override fun close() {
        store.close()
        directory.toFile().deleteRecursively()
    }
}

private fun fixture(): ServiceFixture = ServiceFixture()

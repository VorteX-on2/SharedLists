package dev.sharedlists.server

import dev.sharedlists.protocol.AppliedThrough
import dev.sharedlists.protocol.ClientOperation
import dev.sharedlists.protocol.CreateList
import dev.sharedlists.protocol.OpenSync
import dev.sharedlists.protocol.SubmitOperation
import dev.sharedlists.protocol.SyncRequest
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SharedListsServiceTest {
    @Test
    fun `delivers an operation committed after a stream becomes live`() = runBlocking {
        fixture().use { fixture ->
            val requests = Channel<SyncRequest>()
            val responses = SharedListsService(ChallengeAuthenticator("test", emptyMap()), fixture.store)
                .sync(requests.receiveAsFlow())
                .produceIn(this)
            try {
                requests.send(SyncRequest.newBuilder().setOpen(OpenSync.getDefaultInstance()).build())
                withTimeout(5.seconds) { responses.receive() }
                requests.send(SyncRequest.newBuilder().setAppliedThrough(AppliedThrough.newBuilder().setRevision(0)).build())
                withTimeout(5.seconds) { responses.receive() }

                fixture.store.submit(
                    ClientOperation.newBuilder()
                        .setOperationId("21111111-1111-4111-8111-111111111111")
                        .setCreateList(
                            CreateList.newBuilder()
                                .setListId("11111111-1111-4111-8111-111111111111")
                                .setName("Groceries"),
                        )
                        .build(),
                )

                assertEquals(
                    1,
                    withTimeout(5.seconds) { responses.receive() }.journalBatch.entriesList.single().revision,
                )
            } finally {
                requests.close()
                responses.cancel()
            }
        }
    }

    @Test
    fun `rejects another submission until the previous journal entry is acknowledged`() {
        fixture().use { fixture ->
            assertFailsWith<StatusRuntimeException> {
                runBlocking {
                    SharedListsService(ChallengeAuthenticator("test", emptyMap()), fixture.store).sync(
                        flow {
                            emit(SyncRequest.newBuilder().setOpen(OpenSync.getDefaultInstance()).build())
                            emit(SyncRequest.newBuilder().setAppliedThrough(AppliedThrough.newBuilder().setRevision(0)).build())
                            emit(submission("21111111-1111-4111-8111-111111111111", "First"))
                            emit(submission("31111111-1111-4111-8111-111111111111", "Second"))
                        },
                    ).toList()
                }
            }
        }
    }

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

    private fun submission(operationId: String, name: String): SyncRequest =
        SyncRequest.newBuilder().setSubmitOperation(
            SubmitOperation.newBuilder().setOperation(
                ClientOperation.newBuilder()
                    .setOperationId(operationId)
                    .setCreateList(
                        CreateList.newBuilder()
                            .setListId(
                                if (name == "First") {
                                    "11111111-1111-4111-8111-111111111111"
                                } else {
                                    "41111111-1111-4111-8111-111111111111"
                                },
                            )
                            .setName(name),
                    ),
            ),
        ).build()
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

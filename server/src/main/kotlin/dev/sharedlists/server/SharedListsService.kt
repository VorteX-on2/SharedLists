package dev.sharedlists.server

import dev.sharedlists.protocol.AuthenticationChallenge
import dev.sharedlists.protocol.ChallengeRequest
import dev.sharedlists.protocol.EmptySnapshot
import dev.sharedlists.protocol.JournalBatch
import dev.sharedlists.protocol.JournalEntry
import dev.sharedlists.protocol.Live
import dev.sharedlists.protocol.ServerFault
import dev.sharedlists.protocol.ServerFaultReason
import dev.sharedlists.protocol.SharedListsGrpcKt
import dev.sharedlists.protocol.Snapshot
import dev.sharedlists.protocol.SnapshotBatch
import dev.sharedlists.protocol.SyncRequest
import dev.sharedlists.protocol.SyncResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class SharedListsService(
    private val authenticator: ChallengeAuthenticator,
    private val store: SqliteCanonicalStore,
    private val maximumBatchRecords: Int = MAXIMUM_BATCH_RECORDS,
) : SharedListsGrpcKt.SharedListsCoroutineImplBase() {
    init {
        require(maximumBatchRecords > 0)
    }

    override suspend fun getChallenge(request: ChallengeRequest): AuthenticationChallenge =
        try {
            authenticator.issue(request.keyFingerprint).toProto()
        } catch (exception: AuthenticationException) {
            throw exception.asStatusException()
        }

    override fun sync(requests: Flow<SyncRequest>): Flow<SyncResponse> = channelFlow {
        val events = Channel<Event>(MAXIMUM_QUEUED_EVENTS)
        var phase = Phase.OPENING
        var generation = ""
        var lastAcknowledgedRevision = -1L
        var lastDeliveredRevision = -1L
        var snapshotTransfer: SnapshotTransfer? = null
        val journalJob = launch(start = CoroutineStart.UNDISPATCHED) {
            store.journalEntries.collect { entry ->
                if (events.trySend(Event.Journal(entry)).isFailure) {
                    events.close(SlowConsumerException())
                }
            }
        }
        val requestJob = launch {
            try {
                requests.collect { events.send(Event.Request(it)) }
            } finally {
                events.send(Event.Closed)
            }
        }
        try {
            for (event in events) {
                when (event) {
                    is Event.Journal -> if (phase == Phase.LIVE && event.entry.revision > lastDeliveredRevision) {
                        val entries = store.journalAfter(lastDeliveredRevision, maximumBatchRecords)
                        send(journal(generation, entries))
                        lastDeliveredRevision = entries.last().revision
                    }

                    is Event.Request -> when (phase) {
                        Phase.OPENING -> when (val opening = open(event.request)) {
                            is Opening.EmptySnapshot -> {
                                generation = opening.snapshot.generation
                                phase = Phase.SYNCING
                                lastDeliveredRevision = opening.snapshot.revision
                                send(emptySnapshot(opening.snapshot))
                            }

                            is Opening.LegacySnapshot -> {
                                generation = opening.snapshot.generation
                                phase = Phase.SYNCING
                                lastDeliveredRevision = opening.snapshot.revision
                                send(snapshotResponse(opening.snapshot))
                            }

                            is Opening.Live -> {
                                generation = opening.snapshot.generation
                                phase = Phase.LIVE
                                lastAcknowledgedRevision = opening.snapshot.revision
                                lastDeliveredRevision = opening.snapshot.revision
                                send(live(opening.snapshot))
                            }

                            is Opening.Snapshot -> {
                                generation = opening.transfer.snapshot.generation
                                phase = Phase.SNAPSHOTTING
                                snapshotTransfer = opening.transfer
                                send(opening.transfer.batch())
                            }

                            is Opening.Journal -> {
                                generation = opening.generation
                                phase = Phase.SYNCING
                                lastDeliveredRevision = opening.entries.last().revision
                                send(journal(generation, opening.entries))
                            }
                        }

                        Phase.SNAPSHOTTING -> {
                            val transfer = requireNotNull(snapshotTransfer)
                            requireRequest(
                                event.request.hasAppliedSnapshotBatch() &&
                                    event.request.appliedSnapshotBatch.revision == transfer.snapshot.revision &&
                                    event.request.appliedSnapshotBatch.batchIndex == transfer.index,
                            )
                            if (transfer.isLast) {
                                snapshotTransfer = null
                                val next = advanceAfterSynchronization(transfer.snapshot.revision)
                                phase = next.phase
                                lastAcknowledgedRevision = next.lastAcknowledgedRevision
                                lastDeliveredRevision = next.lastDeliveredRevision
                                next.response?.let { send(it) }
                            } else {
                                snapshotTransfer = transfer.next()
                                send(requireNotNull(snapshotTransfer).batch())
                            }
                        }

                        Phase.SYNCING -> {
                            requireRequest(
                                event.request.hasAppliedThrough() &&
                                    event.request.appliedThrough.revision == lastDeliveredRevision,
                            )
                            val next = advanceAfterSynchronization(lastDeliveredRevision)
                            phase = next.phase
                            lastAcknowledgedRevision = next.lastAcknowledgedRevision
                            lastDeliveredRevision = next.lastDeliveredRevision
                            next.response?.let { send(it) }
                        }

                        Phase.LIVE -> {
                            if (event.request.hasAppliedThrough()) {
                                val revision = event.request.appliedThrough.revision
                                requireRequest(revision in lastAcknowledgedRevision..lastDeliveredRevision)
                                lastAcknowledgedRevision = revision
                            } else {
                                requireRequest(event.request.hasSubmitOperation())
                                requireRequest(lastAcknowledgedRevision == lastDeliveredRevision)
                                try {
                                    val entry = store.submit(event.request.submitOperation.operation)
                                    send(journal(generation, entry))
                                    if (entry.revision > lastDeliveredRevision) {
                                        lastDeliveredRevision = entry.revision
                                    }
                                } catch (exception: OperationIdReuseException) {
                                    throw Status.INVALID_ARGUMENT
                                        .withDescription("operation ID was reused with different content")
                                        .asRuntimeException()
                                } catch (exception: IllegalArgumentException) {
                                    throw Status.INVALID_ARGUMENT
                                        .withDescription(exception.message)
                                        .asRuntimeException()
                                }
                            }
                        }
                    }

                    Event.Closed -> break
                }
            }
        } catch (_: SlowConsumerException) {
            throw Status.RESOURCE_EXHAUSTED.withDescription("slow consumer overflow").asRuntimeException()
        } catch (exception: StatusRuntimeException) {
            throw exception
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            send(serverFault(exception))
        } finally {
            journalJob.cancel()
            requestJob.cancel()
            events.close()
        }
    }

    private fun advanceAfterSynchronization(revision: Long): SynchronizationAdvance {
        val catchUp = store.catchUpAfter(revision, maximumBatchRecords)
        return if (catchUp.journalEntries.isEmpty()) {
            SynchronizationAdvance(
                phase = Phase.LIVE,
                lastAcknowledgedRevision = catchUp.snapshot.revision,
                lastDeliveredRevision = catchUp.snapshot.revision,
                response = live(catchUp.snapshot),
            )
        } else {
            SynchronizationAdvance(
                phase = Phase.SYNCING,
                lastAcknowledgedRevision = revision,
                lastDeliveredRevision = catchUp.journalEntries.last().revision,
                response = journal(generation = catchUp.snapshot.generation, entries = catchUp.journalEntries),
            )
        }
    }

    private fun open(request: SyncRequest): Opening {
        requireRequest(request.hasOpen())
        val snapshot = store.snapshot()
        val cursor = request.open.cursor
        return if (
            request.open.hasCursor() &&
            cursor.generation == snapshot.generation &&
            cursor.lastAppliedRevision in 0..snapshot.revision
        ) {
            if (cursor.lastAppliedRevision == snapshot.revision) {
                Opening.Live(snapshot)
            } else {
                Opening.Journal(snapshot.generation, store.journalAfter(cursor.lastAppliedRevision, maximumBatchRecords))
            }
        } else {
            if (snapshot.lists.isEmpty() && snapshot.tombstones.isEmpty()) {
                Opening.EmptySnapshot(snapshot)
            } else if (!request.open.supportsBoundedTransfer) {
                Opening.LegacySnapshot(snapshot)
            } else {
                Opening.Snapshot(SnapshotTransfer(snapshot, maximumBatchRecords))
            }
        }
    }

    private fun SnapshotTransfer.batch(): SyncResponse =
        SyncResponse.newBuilder().setSnapshotBatch(
            SnapshotBatch.newBuilder()
                .setGeneration(snapshot.generation)
                .setRevision(snapshot.revision)
                .setBatchIndex(index)
                .setIsLast(isLast)
                .addAllLists(snapshot.lists.drop(offset).take(maximumBatchRecords))
                .addAllTombstones(
                    snapshot.tombstones.drop((offset - snapshot.lists.size).coerceAtLeast(0))
                        .take((maximumBatchRecords - (snapshot.lists.size - offset).coerceAtLeast(0)).coerceAtLeast(0)),
                ),
        ).build()

    private fun emptySnapshot(snapshot: CanonicalSnapshot): SyncResponse =
        SyncResponse.newBuilder().setEmptySnapshot(
            EmptySnapshot.newBuilder().setGeneration(snapshot.generation).setRevision(snapshot.revision),
        ).build()

    private fun snapshotResponse(snapshot: CanonicalSnapshot): SyncResponse =
        SyncResponse.newBuilder().setSnapshot(
            Snapshot.newBuilder()
                .setGeneration(snapshot.generation)
                .setRevision(snapshot.revision)
                .addAllLists(snapshot.lists)
                .addAllTombstones(snapshot.tombstones),
        ).build()

    private fun journal(generation: String, entries: List<JournalEntry>): SyncResponse =
        SyncResponse.newBuilder().setJournalBatch(
            JournalBatch.newBuilder().setGeneration(generation).addAllEntries(entries),
        ).build()

    private fun journal(generation: String, entry: JournalEntry): SyncResponse = journal(generation, listOf(entry))

    private fun live(snapshot: CanonicalSnapshot): SyncResponse =
        SyncResponse.newBuilder().setLive(
            Live.newBuilder().setGeneration(snapshot.generation).setRevision(snapshot.revision),
        ).build()

    private fun serverFault(exception: Exception): SyncResponse =
        SyncResponse.newBuilder().setServerFault(
            ServerFault.newBuilder().setReason(
                when {
                    exception is SQLException && exception.message.orEmpty().containsAny(
                        "schema",
                        "no such table",
                        "no such column",
                    ) ->
                        ServerFaultReason.SERVER_FAULT_REASON_SCHEMA
                    exception is SQLException && exception.message.orEmpty().containsAny(
                        "corrupt",
                        "malformed",
                        "integrity",
                    ) -> ServerFaultReason.SERVER_FAULT_REASON_INTEGRITY
                    exception is SQLException ->
                        ServerFaultReason.SERVER_FAULT_REASON_STORAGE
                    exception is IllegalStateException ->
                        ServerFaultReason.SERVER_FAULT_REASON_INTEGRITY
                    else -> ServerFaultReason.SERVER_FAULT_REASON_INTERNAL
                },
            ),
        ).build()

    private fun String.containsAny(vararg values: String): Boolean =
        values.any { contains(it, ignoreCase = true) }

    private fun requireRequest(valid: Boolean) {
        if (!valid) {
            throw Status.INVALID_ARGUMENT.withDescription("invalid synchronization sequence").asRuntimeException()
        }
    }

    private sealed interface Event {
        data object Closed : Event
        data class Journal(val entry: JournalEntry) : Event
        data class Request(val request: SyncRequest) : Event
    }

    private sealed interface Opening {
        data class EmptySnapshot(val snapshot: CanonicalSnapshot) : Opening
        data class Journal(val generation: String, val entries: List<JournalEntry>) : Opening
        data class LegacySnapshot(val snapshot: CanonicalSnapshot) : Opening
        data class Live(val snapshot: CanonicalSnapshot) : Opening
        data class Snapshot(val transfer: SnapshotTransfer) : Opening
    }

    private enum class Phase {
        OPENING,
        SNAPSHOTTING,
        SYNCING,
        LIVE,
    }

    private data class SnapshotTransfer(
        val snapshot: CanonicalSnapshot,
        val maximumBatchRecords: Int,
        val index: Int = 0,
    ) {
        val offset: Int
            get() = index * maximumBatchRecords

        val isLast: Boolean
            get() = offset + maximumBatchRecords >= snapshot.lists.size + snapshot.tombstones.size

        fun next(): SnapshotTransfer = copy(index = index + 1)
    }

    private data class SynchronizationAdvance(
        val phase: Phase,
        val lastAcknowledgedRevision: Long,
        val lastDeliveredRevision: Long,
        val response: SyncResponse?,
    )

    private class SlowConsumerException : Exception()

    private companion object {
        const val MAXIMUM_BATCH_RECORDS = 100
        const val MAXIMUM_QUEUED_EVENTS = 128
    }
}

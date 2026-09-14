package dev.sharedlists.server

import dev.sharedlists.protocol.AuthenticationChallenge
import dev.sharedlists.protocol.ChallengeRequest
import dev.sharedlists.protocol.EmptySnapshot
import dev.sharedlists.protocol.JournalBatch
import dev.sharedlists.protocol.JournalEntry
import dev.sharedlists.protocol.Live
import dev.sharedlists.protocol.SharedListsGrpcKt
import dev.sharedlists.protocol.Snapshot
import dev.sharedlists.protocol.SyncRequest
import dev.sharedlists.protocol.SyncResponse
import io.grpc.Status
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class SharedListsService(
    private val authenticator: ChallengeAuthenticator,
    private val store: SqliteCanonicalStore,
) : SharedListsGrpcKt.SharedListsCoroutineImplBase() {
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
        var synchronizedRevision = -1L
        var lastDeliveredRevision = -1L
        val journalJob = launch(start = CoroutineStart.UNDISPATCHED) {
            store.journalEntries.collect { entry ->
                if (events.trySend(Event.Journal(entry)).isFailure) {
                    events.trySend(Event.SlowConsumer)
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
                        send(journal(generation, event.entry))
                        lastDeliveredRevision = event.entry.revision
                    }

                    is Event.Request -> when (phase) {
                        Phase.OPENING -> when (val opening = open(event.request)) {
                            is Opening.Live -> {
                                generation = opening.snapshot.generation
                                phase = Phase.LIVE
                                lastAcknowledgedRevision = opening.snapshot.revision
                                lastDeliveredRevision = opening.snapshot.revision
                                send(live(opening.snapshot))
                            }

                            is Opening.Snapshot -> {
                                generation = opening.snapshot.generation
                                phase = Phase.SYNCING
                                synchronizedRevision = opening.snapshot.revision
                                lastDeliveredRevision = opening.snapshot.revision
                                send(snapshotResponse(opening.snapshot))
                            }

                            is Opening.Journal -> {
                                generation = opening.generation
                                phase = Phase.SYNCING
                                synchronizedRevision = opening.entries.last().revision
                                lastDeliveredRevision = synchronizedRevision
                                send(journal(generation, opening.entries))
                            }
                        }

                        Phase.SYNCING -> {
                            requireRequest(event.request.hasAppliedThrough() &&
                                event.request.appliedThrough.revision == synchronizedRevision)
                            lastAcknowledgedRevision = synchronizedRevision
                            val catchUp = store.catchUpAfter(synchronizedRevision)
                            if (catchUp.journalEntries.isEmpty()) {
                                phase = Phase.LIVE
                                lastDeliveredRevision = catchUp.snapshot.revision
                                send(live(catchUp.snapshot))
                            } else {
                                synchronizedRevision = catchUp.journalEntries.last().revision
                                lastDeliveredRevision = synchronizedRevision
                                send(journal(generation, catchUp.journalEntries))
                            }
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
                    Event.SlowConsumer -> throw Status.RESOURCE_EXHAUSTED
                        .withDescription("slow consumer overflow")
                        .asRuntimeException()
                }
            }
        } finally {
            journalJob.cancel()
            requestJob.cancel()
            events.close()
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
                Opening.Journal(snapshot.generation, store.journalAfter(cursor.lastAppliedRevision))
            }
        } else {
            Opening.Snapshot(snapshot)
        }
    }

    private fun snapshotResponse(snapshot: CanonicalSnapshot): SyncResponse =
        if (snapshot.lists.isEmpty() && snapshot.tombstones.isEmpty()) {
            SyncResponse.newBuilder().setEmptySnapshot(
                EmptySnapshot.newBuilder().setGeneration(snapshot.generation).setRevision(snapshot.revision),
            ).build()
        } else {
            SyncResponse.newBuilder().setSnapshot(
                Snapshot.newBuilder()
                    .setGeneration(snapshot.generation)
                    .setRevision(snapshot.revision)
                    .addAllLists(snapshot.lists)
                    .addAllTombstones(snapshot.tombstones),
            ).build()
        }

    private fun journal(generation: String, entries: List<JournalEntry>): SyncResponse =
        SyncResponse.newBuilder().setJournalBatch(
            JournalBatch.newBuilder()
                .setGeneration(generation)
                .addAllEntries(entries),
        ).build()

    private fun journal(generation: String, entry: JournalEntry): SyncResponse = journal(generation, listOf(entry))

    private fun live(snapshot: CanonicalSnapshot): SyncResponse =
        SyncResponse.newBuilder().setLive(
            Live.newBuilder().setGeneration(snapshot.generation).setRevision(snapshot.revision),
        ).build()

    private fun requireRequest(valid: Boolean) {
        if (!valid) {
            throw Status.INVALID_ARGUMENT.withDescription("invalid synchronization sequence").asRuntimeException()
        }
    }

    private sealed interface Event {
        data object Closed : Event

        data object SlowConsumer : Event

        data class Journal(val entry: JournalEntry) : Event

        data class Request(val request: SyncRequest) : Event
    }

    private sealed interface Opening {
        data class Journal(val generation: String, val entries: List<JournalEntry>) : Opening

        data class Live(val snapshot: CanonicalSnapshot) : Opening

        data class Snapshot(val snapshot: CanonicalSnapshot) : Opening
    }

    private enum class Phase {
        OPENING,
        SYNCING,
        LIVE,
    }

    private companion object {
        const val MAXIMUM_QUEUED_EVENTS = 128
    }
}

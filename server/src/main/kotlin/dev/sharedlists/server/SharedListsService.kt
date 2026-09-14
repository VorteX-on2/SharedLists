package dev.sharedlists.server

import dev.sharedlists.protocol.EmptySnapshot
import dev.sharedlists.protocol.Live
import dev.sharedlists.protocol.SharedListsGrpcKt
import dev.sharedlists.protocol.SyncRequest
import dev.sharedlists.protocol.SyncResponse
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class SharedListsService(
    private val store: SqliteCanonicalStore,
    private val testPreAuthenticatedClient: Boolean,
) : SharedListsGrpcKt.SharedListsCoroutineImplBase() {
    override fun sync(requests: Flow<SyncRequest>): Flow<SyncResponse> = flow {
        requireTestAuthentication()
        var opened = false
        var snapshot: CanonicalSnapshot? = null
        requests.collect { request ->
            when {
                request.hasOpen() && !opened -> {
                    opened = true
                    snapshot = store.snapshot()
                    emit(
                        SyncResponse.newBuilder().setEmptySnapshot(
                            EmptySnapshot.newBuilder()
                                .setGeneration(snapshot.generation)
                                .setRevision(snapshot.revision),
                        ).build(),
                    )
                }

                request.hasAppliedThrough() && opened &&
                    request.appliedThrough.revision == snapshot?.revision -> {
                    emit(
                        SyncResponse.newBuilder().setLive(
                            Live.newBuilder()
                                .setGeneration(snapshot?.generation.orEmpty())
                                .setRevision(snapshot?.revision ?: 0),
                        ).build(),
                    )
                }

                else -> throw Status.INVALID_ARGUMENT
                    .withDescription("invalid synchronization sequence")
                    .asRuntimeException()
            }
        }
    }

    private fun requireTestAuthentication() {
        val authenticated = Metadata.Key.of(TEST_AUTHENTICATION_HEADER, Metadata.ASCII_STRING_MARSHALLER)
            .let { header -> ServerCallContext.headers.get().get(header) == TEST_AUTHENTICATION_VALUE }
        if (!testPreAuthenticatedClient || !authenticated) {
            throw Status.UNAUTHENTICATED.withDescription("authentication required").asRuntimeException()
        }
    }

    companion object {
        const val TEST_AUTHENTICATION_HEADER = "x-sharedlists-test-authentication"
        const val TEST_AUTHENTICATION_VALUE = "fixture"
    }
}

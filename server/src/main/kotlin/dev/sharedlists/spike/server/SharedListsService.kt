package dev.sharedlists.spike.server

import dev.sharedlists.spike.protocol.ChallengeRequest
import dev.sharedlists.spike.protocol.ChallengeResponse
import dev.sharedlists.spike.protocol.SharedListsSpikeGrpcKt
import dev.sharedlists.spike.protocol.SyncRequest
import dev.sharedlists.spike.protocol.SyncResponse
import dev.sharedlists.spike.protocol.challengeResponse
import dev.sharedlists.spike.protocol.syncResponse
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

internal class SharedListsService(
    private val authenticator: ChallengeAuthenticator,
    private val streams: StreamRegistry,
) : SharedListsSpikeGrpcKt.SharedListsSpikeCoroutineImplBase() {
    override suspend fun challenge(request: ChallengeRequest): ChallengeResponse {
        val challenge = try {
            authenticator.issue(request.kid)
        } catch (failure: AuthenticationException) {
            throw Status.PERMISSION_DENIED.withDescription(failure.failure.wireName).asRuntimeException()
        }
        return challengeResponse {
            nonce = com.google.protobuf.ByteString.copyFrom(challenge.nonce)
            issuedAtEpochSeconds = challenge.issuedAt.epochSecond
            expiresAtEpochSeconds = challenge.expiresAt.epochSecond
            audience = challenge.audience
        }
    }

    override fun sync(requests: Flow<SyncRequest>): Flow<SyncResponse> = flow {
        val kid = AuthContext.KID.get()
            ?: throw Status.UNAUTHENTICATED.withDescription("invalid authentication").asRuntimeException()
        val job = currentCoroutineContext()[Job] ?: error("stream has no coroutine job")
        streams.register(kid, job)
        try {
            requests.collect { request ->
                emit(syncResponse {
                    payload = request.payload
                    authenticatedKid = kid
                })
            }
        } finally {
            streams.release(kid, job)
        }
    }
}

internal class StreamRegistry {
    private val streams = ConcurrentHashMap<String, Job>()

    fun register(kid: String, stream: Job) {
        streams.put(kid, stream)?.cancel(CancellationException("superseded by a newer authenticated stream"))
    }

    fun release(kid: String, stream: Job) {
        streams.remove(kid, stream)
    }

    fun activeCount(): Int = streams.size
}

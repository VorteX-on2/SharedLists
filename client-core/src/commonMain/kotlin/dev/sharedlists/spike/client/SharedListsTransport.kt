package dev.sharedlists.spike.client

import dev.sharedlists.spike.protocol.ChallengeRequest
import dev.sharedlists.spike.protocol.SharedListsSpike
import dev.sharedlists.spike.protocol.SyncRequest
import dev.sharedlists.spike.protocol.SyncResponse
import dev.sharedlists.spike.protocol.invoke
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.grpc.GrpcMetadata
import kotlinx.rpc.grpc.append
import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.grpc.client.GrpcClientCallScope
import kotlinx.rpc.grpc.client.GrpcClientInterceptor
import kotlinx.rpc.withService
import kotlin.time.Instant

class SharedListsTransport(
    private val host: String,
    private val port: Int,
    private val trustedCertificatePem: String,
    private val tlsAuthority: String = host,
) {
    suspend fun challenge(kid: String): StreamChallenge {
        val client = client()
        return try {
            val response = client.withService<SharedListsSpike>().Challenge(ChallengeRequest { this.kid = kid })
            StreamChallenge(
                nonce = response.nonce.toByteArray(),
                issuedAt = Instant.fromEpochSeconds(response.issuedAtEpochSeconds),
                expiresAt = Instant.fromEpochSeconds(response.expiresAtEpochSeconds),
                audience = response.audience,
            )
        } finally {
            client.shutdownNow()
        }
    }

    fun authenticated(token: String): AuthenticatedTransport {
        val client = client(token)
        return AuthenticatedTransport(client, client.withService())
    }

    private fun client(token: String? = null): GrpcClient = GrpcClient(host, port) {
        credentials = tls { trustManager(trustedCertificatePem) }
        overrideAuthority = tlsAuthority
        if (token != null) {
            intercept(BearerTokenInterceptor(token))
        }
    }
}

class AuthenticatedTransport internal constructor(
    private val client: GrpcClient,
    private val service: SharedListsSpike,
) : AutoCloseable {
    fun sync(requests: Flow<SyncRequest>): Flow<SyncResponse> = service.Sync(requests)

    override fun close() {
        client.shutdownNow()
    }
}

private class BearerTokenInterceptor(
    private val token: String,
) : GrpcClientInterceptor {
    override fun <Request, Response> GrpcClientCallScope<Request, Response>.intercept(
        request: Flow<Request>,
    ): Flow<Response> {
        requestHeaders.append("authorization", "Bearer $token")
        return proceed(request)
    }
}

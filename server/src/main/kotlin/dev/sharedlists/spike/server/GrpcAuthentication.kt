package dev.sharedlists.spike.server

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status

internal object AuthContext {
    val KID: Context.Key<String> = Context.key("authenticated-kid")
}

internal class AuthenticationInterceptor(
    private val authenticator: ChallengeAuthenticator,
) : ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!call.methodDescriptor.fullMethodName.endsWith("/Sync")) {
            return next.startCall(call, headers)
        }
        val token = headers.get(AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?: return close(call, AuthFailure.INVALID_AUTHENTICATION)
        return try {
            val kid = authenticator.authenticate(token)
            Contexts.interceptCall(Context.current().withValue(AuthContext.KID, kid), call, headers, next)
        } catch (failure: AuthenticationException) {
            close(call, failure.failure)
        }
    }

    private fun <ReqT : Any, RespT : Any> close(
        call: ServerCall<ReqT, RespT>,
        failure: AuthFailure,
    ): ServerCall.Listener<ReqT> {
        val status = when (failure) {
            AuthFailure.DEVICE_NOT_ENROLLED -> Status.PERMISSION_DENIED
            AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED,
            AuthFailure.INVALID_AUTHENTICATION,
            -> Status.UNAUTHENTICATED
        }.withDescription(failure.wireName)
        call.close(status, Metadata())
        return object : ServerCall.Listener<ReqT>() {}
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

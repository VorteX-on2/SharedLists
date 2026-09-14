package dev.sharedlists.server

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor

internal object ServerCallContext {
    val headers: Context.Key<Metadata> = Context.key("request-headers")
}

internal class RequestHeadersInterceptor : ServerInterceptor {
    override fun <RequestT : Any, ResponseT : Any> interceptCall(
        call: ServerCall<RequestT, ResponseT>,
        headers: Metadata,
        next: ServerCallHandler<RequestT, ResponseT>,
    ): ServerCall.Listener<RequestT> =
        Contexts.interceptCall(Context.current().withValue(ServerCallContext.headers, headers), call, headers, next)
}

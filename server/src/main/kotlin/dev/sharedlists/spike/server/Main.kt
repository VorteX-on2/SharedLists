package dev.sharedlists.spike.server

import io.grpc.ServerInterceptors
import io.grpc.netty.NettyServerBuilder
import java.io.File
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 5) {
        "usage: <port> <certificate.pem> <private-key.pem> <allowlist-directory> <canonical-audience>"
    }
    val port = args[0].toInt()
    val keys = PemAllowlist.load(Path.of(args[3]))
    val authenticator = ChallengeAuthenticator(keys, args[4])
    val service = SharedListsService(authenticator, StreamRegistry())
    val server = NettyServerBuilder.forPort(port)
        .useTransportSecurity(File(args[1]), File(args[2]))
        .addService(ServerInterceptors.intercept(service, AuthenticationInterceptor(authenticator)))
        .build()
        .start()

    println("READY port=$port enrolledKeys=${keys.size}")
    Runtime.getRuntime().addShutdownHook(Thread { server.shutdownNow() })
    server.awaitTermination()
}

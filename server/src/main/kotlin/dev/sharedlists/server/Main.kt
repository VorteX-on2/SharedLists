package dev.sharedlists.server

import dev.sharedlists.protocol.ServerFaultReason
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

fun main(args: Array<String>) {
    require(args.contentEquals(arrayOf("--config", args.getOrNull(1)))) {
        "usage: --config <sharedlists.properties>"
    }
    val configuration = ServerConfiguration.load(Path.of(args[1]))
    val identity = ServerIdentityManager.loadOrCreate(configuration)
    val authenticator = ChallengeAuthenticator(
        configuration.serviceUri,
        AuthorizedDeviceKeys.load(configuration.authorizedDevicesDirectory),
    )
    val streams = ActiveStreamRegistry()
    SqliteCanonicalStore(configuration.databaseFile).use { store ->
        var fatalFault: ServerFaultReason? = null
        val serverReference = AtomicReference<Server>()
        val server = NettyServerBuilder
            .forAddress(InetSocketAddress(InetAddress.getByName(configuration.bindAddress), configuration.port))
            .useTransportSecurity(identity.certificateFile.toFile(), identity.privateKeyFile.toFile())
            .addService(
                ServerInterceptors.intercept(
                    SharedListsService(
                        authenticator,
                        store,
                        onFatalFault = { reason ->
                            fatalFault = reason
                            serverReference.get()?.shutdownNow()
                        },
                    ),
                    AuthenticationInterceptor(authenticator, streams),
                    RequestHeadersInterceptor(),
                ),
            ).build()
            .start()
        serverReference.set(server)
        println("READY port=${configuration.port} tlsFingerprint=${identity.fingerprint}")
        Runtime.getRuntime().addShutdownHook(Thread { server.shutdown() })
        server.awaitTermination()
        check(fatalFault == null) { "Server stopped after fatal fault: $fatalFault" }
    }
}

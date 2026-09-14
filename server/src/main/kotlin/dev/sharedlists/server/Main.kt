package dev.sharedlists.server

import dev.sharedlists.protocol.ServerFaultReason
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.NettyServerBuilder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = try {
        run(args)
        0
    } catch (exception: Exception) {
        System.err.println("ERROR: ${exception.message ?: exception::class.simpleName}")
        1
    }
    if (exitCode != 0) exitProcess(exitCode)
}

private fun run(args: Array<String>) {
    require(args.size == 2 && args[0] == "--config") { "usage: java -jar sharedlists-server.jar --config <sharedlists.properties>" }
    val configuration = ServerConfiguration.load(Path.of(args[1]))
    println("STARTING configuration validated for ${configuration.serviceUri}")
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
            .maxInboundMessageSize(MAXIMUM_MESSAGE_BYTES)
            .addService(
                ServerInterceptors.intercept(
                    SharedListsService(
                        authenticator,
                        store,
                        onFatalFault = { reason ->
                            fatalFault = reason
                            System.err.println("FATAL storage fault: $reason")
                            serverReference.get()?.shutdownNow()
                        },
                    ),
                    AuthenticationInterceptor(authenticator, streams),
                    RequestHeadersInterceptor(),
                ),
            ).build()
            .start()
        serverReference.set(server)
        println("STARTED serviceUri=${configuration.serviceUri} tlsFingerprint=${identity.fingerprint}")
        Runtime.getRuntime().addShutdownHook(
            Thread {
                println("SHUTDOWN stopping RPCs")
                server.shutdown()
                if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    System.err.println("ERROR: graceful shutdown exceeded $SHUTDOWN_TIMEOUT_SECONDS seconds")
                    server.shutdownNow()
                }
            },
        )
        server.awaitTermination()
        check(fatalFault == null) { "Server stopped after fatal fault: $fatalFault" }
    }
}

private const val MAXIMUM_MESSAGE_BYTES = 1024 * 1024
private const val SHUTDOWN_TIMEOUT_SECONDS = 10L

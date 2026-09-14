package dev.sharedlists.server

import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.FileClientStateStore
import dev.sharedlists.client.GrpcSharedListsClient
import dev.sharedlists.client.ServerEndpoint
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.inputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RealServerAcceptanceTest {
    @Test
    fun `real server reaches durable empty live canonical state`() {
        TemporaryServerInstallation().use { fixture ->
            fixture.start()
            val firstState = fixture.client().synchronizeBlocking()

            assertEquals(
                ClientState.Ready(
                    enrollment = EnrollmentState.ENROLLED,
                    connectivity = ConnectivityState.LIVE,
                    canonicalState = CanonicalState(),
                    cursor = firstState.cursor,
                ),
                firstState,
            )
            assertEquals(0, firstState.cursor?.lastAppliedRevision)
            assertTrue(Files.exists(fixture.databaseFile))
            assertTrue(Files.exists(fixture.certificateFile))
            assertTrue(Files.exists(fixture.privateKeyFile))

            val firstFingerprint = fixture.certificateFingerprint()
            fixture.restart()
            val restartedState = fixture.client().synchronizeBlocking()

            assertEquals(firstFingerprint, fixture.certificateFingerprint())
            assertEquals(firstState.cursor, restartedState.cursor)
            assertEquals(ConnectivityState.LIVE, restartedState.connectivity)
        }
    }

    private fun GrpcSharedListsClient.synchronizeBlocking(): ClientState.Ready =
        runBlocking {
            synchronize() as ClientState.Ready
        }
}

private class TemporaryServerInstallation : AutoCloseable {
    private val directory = Files.createTempDirectory("sharedlists-real-server-")
    private val port = ServerSocket(0).use { socket -> socket.localPort }
    private val processOutput = StringBuilder()
    private var process: Process? = null

    val certificateFile: Path = directory.resolve("data/tls/server.pem")
    val databaseFile: Path = directory.resolve("data/sharedlists.db")
    val privateKeyFile: Path = directory.resolve("data/tls/server-key.pem")

    init {
        directory.resolve("data/authorized-devices").let(Files::createDirectories)
        directory.resolve("sharedlists.properties").writeText(
            """
            bindAddress=127.0.0.1
            port=$port
            serverIp=127.0.0.1
            databaseFile=data/sharedlists.db
            tlsCertificateFile=data/tls/server.pem
            tlsPrivateKeyFile=data/tls/server-key.pem
            """.trimIndent(),
        )
    }

    fun certificateFingerprint(): String {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateFile.inputStream()) as X509Certificate
        return MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { byte -> "%02X".format(byte) }
    }

    fun client(): GrpcSharedListsClient =
        GrpcSharedListsClient(
            endpoint = ServerEndpoint("127.0.0.1", port, certificateFingerprint()),
            stateStore = FileClientStateStore(directory.resolve("client-state.properties").toFile()),
            preAuthenticatedForTest = true,
        )

    fun restart() {
        stop()
        start()
    }

    fun start() {
        check(process == null) { "Server process is already running." }
        synchronized(processOutput) {
            processOutput.setLength(0)
        }
        val distribution = Path.of("build/install/server").toAbsolutePath()
        check(Files.isDirectory(distribution.resolve("lib"))) { "Server distribution is missing: $distribution" }
        process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Dsharedlists.testPreAuthenticatedClient=true",
            "-cp",
            "${distribution.resolve("lib")}\\*",
            "dev.sharedlists.server.MainKt",
            "--config",
            directory.resolve("sharedlists.properties").toString(),
        ).redirectErrorStream(true).start()
        val outputThread = Thread {
            process?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    synchronized(processOutput) {
                        processOutput.appendLine(line)
                    }
                }
            }
        }
        outputThread.isDaemon = true
        outputThread.start()
        awaitReady()
    }

    fun stop() {
        val currentProcess = process ?: return
        currentProcess.destroy()
        check(currentProcess.waitFor(10, TimeUnit.SECONDS)) { "Server did not stop: ${output()}" }
        process = null
    }

    override fun close() {
        stop()
        directory.toFile().deleteRecursively()
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (System.nanoTime() < deadline) {
            check(process?.isAlive == true) { "Server exited before readiness: ${output()}" }
            if (output().contains("READY port=$port")) {
                Socket("127.0.0.1", port).use {
                    return
                }
            }
            Thread.sleep(50)
        }
        error("Server did not become ready: ${output()}")
    }

    private fun output(): String =
        synchronized(processOutput) {
            processOutput.toString()
        }
}

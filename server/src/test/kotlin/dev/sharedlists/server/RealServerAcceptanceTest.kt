package dev.sharedlists.server

import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.CreateItem
import dev.sharedlists.client.CreateList
import dev.sharedlists.client.DeleteItem
import dev.sharedlists.client.DeleteList
import dev.sharedlists.client.DeviceSigner
import dev.sharedlists.client.EditItemText
import dev.sharedlists.client.EditCommand
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.FileClientStateStore
import dev.sharedlists.client.GrpcSharedListsClient
import dev.sharedlists.client.ListItemId
import dev.sharedlists.client.OperationId
import dev.sharedlists.client.OperationOutcome
import dev.sharedlists.client.RenameList
import dev.sharedlists.client.ServerEndpoint
import dev.sharedlists.client.SharedListId
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
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

    @Test
    fun `enrolled clients receive durable shared-list lifecycle effects`() {
        TemporaryServerInstallation().use { fixture ->
            fixture.start()
            val first = fixture.client(0)
            val second = fixture.client(1)
            val listId = SharedListId.parse("11111111-1111-4111-8111-111111111111")

            first.synchronizeBlocking()
            assertEquals(
                OperationOutcome.APPLIED,
                first.submitBlocking(
                    CreateList(
                        operationId = OperationId.parse("21111111-1111-4111-8111-111111111111"),
                        listId = listId,
                        name = "  Groceries  ",
                    ),
                ).lastOperationOutcome?.outcome,
            )
            assertEquals(listOf("Groceries"), second.synchronizeBlocking().canonicalState.lists.map { it.name })
            assertEquals(
                OperationOutcome.APPLIED,
                first.submitBlocking(
                    RenameList(
                        operationId = OperationId.parse("31111111-1111-4111-8111-111111111111"),
                        listId = listId,
                        name = "Kitchen",
                    ),
                ).lastOperationOutcome?.outcome,
            )
            assertEquals(listOf("Kitchen"), second.synchronizeBlocking().canonicalState.lists.map { it.name })
            assertEquals(
                OperationOutcome.APPLIED,
                first.submitBlocking(
                    DeleteList(
                        operationId = OperationId.parse("41111111-1111-4111-8111-111111111111"),
                        listId = listId,
                    ),
                ).lastOperationOutcome?.outcome,
            )
            assertEquals(emptyList(), second.synchronizeBlocking().canonicalState.lists)
            assertEquals(
                OperationOutcome.IGNORED,
                first.submitBlocking(
                    RenameList(
                        operationId = OperationId.parse("51111111-1111-4111-8111-111111111111"),
                        listId = listId,
                        name = "Resurrected",
                    ),
                ).lastOperationOutcome?.outcome,
            )
            fixture.restart()
            assertEquals(emptyList(), fixture.client(1).synchronizeBlocking().canonicalState.lists)
        }
    }

    @Test
    fun `real clients persist item lifecycle, duplicate text, and terminal deletion`() {
        TemporaryServerInstallation().use { fixture ->
                fixture.start()
                val first = fixture.client(0)
                val second = fixture.client(1)
                val listId = SharedListId.parse("61111111-1111-4111-8111-111111111111")
                val firstItemId = ListItemId.parse("71111111-1111-4111-8111-111111111111")
                val secondItemId = ListItemId.parse("81111111-1111-4111-8111-111111111111")

                first.synchronizeBlocking()
                first.submitBlocking(
                    CreateList(
                        operationId = OperationId.parse("91111111-1111-4111-8111-111111111111"),
                        listId = listId,
                        name = "Groceries",
                    ),
                )
                assertEquals(
                    OperationOutcome.REJECTED,
                    first.submitBlocking(invalidItem()).lastOperationOutcome?.outcome,
                )
                assertEquals(
                    OperationOutcome.APPLIED,
                    first.submitBlocking(firstItem()).lastOperationOutcome?.outcome,
                )
                assertEquals(OperationOutcome.APPLIED, first.submitBlocking(firstItem()).lastOperationOutcome?.outcome)
                first.submitBlocking(
                    CreateItem(
                        operationId = OperationId.parse("b1111111-1111-4111-8111-111111111111"),
                        itemId = secondItemId,
                        listId = listId,
                        text = "Milk",
                    ),
                )
                assertEquals(
                    listOf("Milk", "Milk"),
                    second.synchronizeBlocking().canonicalState.lists.single().items.map { it.text },
                )
                first.submitBlocking(
                    EditItemText(
                        operationId = OperationId.parse("c1111111-1111-4111-8111-111111111111"),
                        itemId = firstItemId,
                        listId = listId,
                        text = "Oat milk",
                    ),
                )
                assertEquals(
                    OperationOutcome.APPLIED,
                    first.submitBlocking(
                        DeleteItem(
                            operationId = OperationId.parse("d1111111-1111-4111-8111-111111111111"),
                            itemId = firstItemId,
                            listId = listId,
                        ),
                    ).lastOperationOutcome?.outcome,
                )
                assertEquals(
                    listOf("Milk"),
                    second.synchronizeBlocking().canonicalState.lists.single().items.map { it.text },
                )
                assertEquals(
                    OperationOutcome.IGNORED,
                    first.submitBlocking(
                        EditItemText(
                            operationId = OperationId.parse("e1111111-1111-4111-8111-111111111111"),
                            itemId = firstItemId,
                            listId = listId,
                            text = "Resurrected",
                        ),
                    ).lastOperationOutcome?.outcome,
                )
                fixture.restart()
                assertEquals(
                    listOf("Milk"),
                    fixture.client(1).synchronizeBlocking().canonicalState.lists.single().items.map { it.text },
                )
        }
    }

    private fun firstItem(): CreateItem =
        CreateItem(
            operationId = OperationId.parse("a1111111-1111-4111-8111-111111111111"),
            itemId = ListItemId.parse("71111111-1111-4111-8111-111111111111"),
            listId = SharedListId.parse("61111111-1111-4111-8111-111111111111"),
            text = "Milk",
        )

    private fun invalidItem(): CreateItem =
        CreateItem(
            operationId = OperationId.parse("f1111111-1111-4111-8111-111111111111"),
            itemId = ListItemId.parse("f2111111-1111-4111-8111-111111111111"),
            listId = SharedListId.parse("61111111-1111-4111-8111-111111111111"),
            text = "x".repeat(501),
        )

    private fun GrpcSharedListsClient.synchronizeBlocking(): ClientState.Ready =
        runBlocking {
            synchronize() as ClientState.Ready
        }

    private fun GrpcSharedListsClient.submitBlocking(command: EditCommand): ClientState.Ready =
        runBlocking {
            submit(command) as ClientState.Ready
        }
}

private class TemporaryServerInstallation : AutoCloseable {
    private val directory = Files.createTempDirectory("sharedlists-real-server-")
    private val distribution = directory.resolve("server")
    private val port = ServerSocket(0).use { socket -> socket.localPort }
    private val processOutput = StringBuilder()
    private val deviceSigners = listOf(TestDeviceSigner.create(), TestDeviceSigner.create())
    private var process: Process? = null

    val certificateFile: Path = directory.resolve("data/tls/server.pem")
    val databaseFile: Path = directory.resolve("data/sharedlists.db")
    val privateKeyFile: Path = directory.resolve("data/tls/server-key.pem")

    init {
        copyDistribution()
        directory.resolve("data/authorized-devices").also { authorizedDevicesDirectory ->
            Files.createDirectories(authorizedDevicesDirectory)
            deviceSigners.forEachIndexed { index, signer ->
                signer.writePublicKeyPem(authorizedDevicesDirectory.resolve("fixture-$index.pem"))
            }
        }
        directory.resolve("sharedlists.properties").writeText(
            """
            bindAddress=127.0.0.1
            authorizedDevicesDirectory=data/authorized-devices
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

    fun client(index: Int = 0): GrpcSharedListsClient =
        GrpcSharedListsClient(
            endpoint = ServerEndpoint("127.0.0.1", port, certificateFingerprint()),
            deviceSigner = deviceSigners[index],
            stateStore = FileClientStateStore(directory.resolve("client-state-$index.properties").toFile()),
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
        check(Files.isDirectory(distribution.resolve("lib"))) { "Server distribution is missing: $distribution" }
        process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
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

    private fun copyDistribution() {
        val source = Path.of("build/install/server").toAbsolutePath()
        check(Files.isDirectory(source)) { "Server distribution is missing: $source" }
        Files.walk(source).use { paths ->
            paths.forEach { sourcePath ->
                val target = distribution.resolve(source.relativize(sourcePath).toString())
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun output(): String =
        synchronized(processOutput) {
            processOutput.toString()
        }
}

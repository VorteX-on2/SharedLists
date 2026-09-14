package dev.sharedlists.windows

import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.CreateItem
import dev.sharedlists.client.DeviceSigner
import dev.sharedlists.client.EditCommand
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.ListItem
import dev.sharedlists.client.ListItemId
import dev.sharedlists.client.SharedList
import dev.sharedlists.client.SharedListId
import dev.sharedlists.client.SharedListsClient
import dev.sharedlists.client.SynchronizationCursor
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsSynchronizationControllerTest {
    @Test
    fun `connect shows progress then the live empty state through the public facade`() {
        val facade = DeferredSharedListsClient()
        val store = InMemoryServerConfigurationStore()
        val controller = controller(facade, store)

        controller.connect("192.0.2.10", "8443", FINGERPRINT)

        assertEquals("Connecting…", controller.presentation().statusMessage)
        assertEquals("Connecting to synchronized lists…", controller.presentation().emptyStateMessage)
        assertTrue(controller.presentation().connectionActive)
        assertFalse(controller.presentation().editingEnabled)
        facade.complete(liveState(CanonicalState()))

        assertEquals("Device enrolled and synchronized", controller.presentation().statusMessage)
        assertFalse(controller.presentation().connectionActive)
        assertEquals("No shared lists yet.", controller.presentation().emptyStateMessage)
        assertTrue(controller.presentation().editingEnabled)
        assertEquals("192.0.2.10", store.load()?.host)
    }

    @Test
    fun `invalid configuration remains visibly disconnected`() {
        val controller = controller(DeferredSharedListsClient(), InMemoryServerConfigurationStore())

        controller.connect("", "not-a-port", "not-a-fingerprint")

        assertEquals("Configuration incomplete", controller.presentation().statusMessage)
        assertFalse(controller.presentation().editingEnabled)
    }

    @Test
    fun `saved server configuration is available when the app starts`() {
        val configuration = ServerConfiguration("192.0.2.10", 8443, FINGERPRINT)
        val store = InMemoryServerConfigurationStore().apply { save(configuration) }
        val controller = controller(DeferredSharedListsClient(), store)

        assertEquals(configuration, controller.presentation().configuration)
        assertEquals("Ready to connect", controller.presentation().statusMessage)
        assertFalse(controller.presentation().editingEnabled)
    }

    @Test
    fun `cached canonical state remains visible but read-only when disconnected`() {
        val facade = DeferredSharedListsClient()
        val controller = controller(facade, InMemoryServerConfigurationStore())
        val cachedList = SharedList(SharedListId.parse("00000000-0000-4000-8000-000000000001"), "Groceries")

        controller.connect("192.0.2.10", "8443", FINGERPRINT)
        facade.complete(liveState(CanonicalState(listOf(cachedList))))
        controller.connect("192.0.2.10", "8443", FINGERPRINT)
        facade.complete(
            ClientState.Ready(
                enrollment = EnrollmentState.ENROLLED,
                connectivity = ConnectivityState.OFFLINE,
                canonicalState = CanonicalState(listOf(cachedList)),
                cursor = null,
            ),
        )

        assertEquals(listOf("Groceries"), controller.presentation().lists)
        assertEquals("Disconnected — cached lists are read-only", controller.presentation().statusMessage)
        assertFalse(controller.presentation().editingEnabled)
    }

    @Test
    fun `connection failures hide protocol details and keep editing unavailable`() {
        val facade = DeferredSharedListsClient()
        val controller = controller(facade, InMemoryServerConfigurationStore())

        controller.connect("192.0.2.10", "8443", FINGERPRINT)
        facade.fail(IllegalStateException("JWT challenge nonce leaked"))

        assertEquals("Unable to synchronize shared lists.", controller.presentation().emptyStateMessage)
        assertFalse(controller.presentation().editingEnabled)
    }

    @Test
    fun `completion from an older connection cannot replace the current state`() {
        val firstFacade = DeferredSharedListsClient()
        val secondFacade = DeferredSharedListsClient()
        val controller = WindowsSynchronizationController(
            clientFactory = SequentialWindowsClientFactory(firstFacade, secondFacade),
            configurationStore = InMemoryServerConfigurationStore(),
            synchronizationRunner = SynchronizationRunner { block -> block() },
        )

        controller.connect("192.0.2.10", "8443", FINGERPRINT)
        controller.connect("192.0.2.11", "8443", FINGERPRINT)
        secondFacade.complete(liveState(CanonicalState()))
        firstFacade.fail(IllegalStateException("older attempt"))

        assertEquals("Device enrolled and synchronized", controller.presentation().statusMessage)
        assertTrue(controller.presentation().editingEnabled)
    }

    @Test
    fun `configured factory creates the public grpc facade`() {
        val factory = WindowsGrpcClientFactory(DeferredSharedListsClientSigner)

        val client = factory.create(ServerConfiguration("192.0.2.10", 8443, FINGERPRINT))

        assertTrue(client.javaClass.name.endsWith("GrpcSharedListsClient"))
    }

    @Test
    fun `device setup saves server identity then requests public-key export without connecting`() {
        val enrollment = InMemoryWindowsDeviceEnrollment()
        val store = InMemoryServerConfigurationStore()
        val controller = WindowsSynchronizationController(
            clientFactory = StaticWindowsClientFactory(DeferredSharedListsClient()),
            configurationStore = store,
            deviceEnrollment = enrollment,
            synchronizationRunner = SynchronizationRunner { block -> block() },
        )

        controller.createDeviceKey("192.0.2.10", "8443", FINGERPRINT)

        assertEquals("test-key", controller.presentation().deviceKeyFingerprint)
        assertEquals("Device key created — export public key", controller.presentation().statusMessage)
        assertTrue(controller.presentation().exportRequired)
        assertEquals("192.0.2.10", store.load()?.host)
        assertFalse(enrollment.connected)
    }

    @Test
    fun `item presentation validates text and submits duplicate items through the public facade`() {
        val list = SharedList(
            id = SharedListId.parse("a0000000-0000-4000-8000-000000000001"),
            name = "Groceries",
            items = listOf(
                ListItem(
                    id = ListItemId.parse("b0000000-0000-4000-8000-000000000001"),
                    text = "Milk",
                ),
            ),
        )
        val facade = CapturingSharedListsClient(liveState(CanonicalState(listOf(list))))
        val controller = controller(facade, InMemoryServerConfigurationStore())

        controller.connect("192.0.2.10", "8443", FINGERPRINT)
        controller.createItem(list.id, "x".repeat(501))

        assertEquals("Enter item text of at most 500 characters.", controller.presentation().statusMessage)
        assertTrue(facade.commands.isEmpty())

        controller.createItem(list.id, "Milk")

        assertTrue(facade.commands.single() is CreateItem)
        assertEquals(listOf("Milk"), controller.items(list.id).map { it.text })
    }

    private fun liveState(canonicalState: CanonicalState): ClientState.Ready =
        ClientState.Ready(
            enrollment = EnrollmentState.ENROLLED,
            connectivity = ConnectivityState.LIVE,
            canonicalState = canonicalState,
            cursor = SynchronizationCursor("test-generation", 0),
        )

    private fun controller(
        facade: SharedListsClient,
        store: ServerConfigurationStore,
    ): WindowsSynchronizationController =
        WindowsSynchronizationController(
            clientFactory = StaticWindowsClientFactory(facade),
            configurationStore = store,
            synchronizationRunner = SynchronizationRunner { block -> block() },
        )

    private class DeferredSharedListsClient : SharedListsClient {
        private var continuation: Continuation<ClientState>? = null

        fun complete(state: ClientState) {
            requireNotNull(continuation).resumeWith(Result.success(state))
        }

        fun fail(error: Throwable) {
            requireNotNull(continuation).resumeWith(Result.failure(error))
        }

        override suspend fun submit(command: EditCommand): ClientState = error("Edits are outside this tracer.")

        override suspend fun synchronize(): ClientState =
            suspendCoroutine { continuation = it }
    }

    private class CapturingSharedListsClient(
        private val state: ClientState,
    ) : SharedListsClient {
        val commands = mutableListOf<EditCommand>()

        override suspend fun submit(command: EditCommand): ClientState {
            commands += command
            return state
        }

        override suspend fun synchronize(): ClientState = state
    }

    private class StaticWindowsClientFactory(
        private val client: SharedListsClient,
    ) : WindowsClientFactory {
        override fun create(configuration: ServerConfiguration): SharedListsClient = client
    }

    private class SequentialWindowsClientFactory(
        private vararg val clients: SharedListsClient,
    ) : WindowsClientFactory {
        private var nextClient = 0

        override fun create(configuration: ServerConfiguration): SharedListsClient =
            clients[nextClient++]
    }

    private class InMemoryServerConfigurationStore : ServerConfigurationStore {
        private var configuration: ServerConfiguration? = null

        override fun load(): ServerConfiguration? = configuration

        override fun save(configuration: ServerConfiguration) {
            this.configuration = configuration
        }
    }

    private class InMemoryWindowsDeviceEnrollment : WindowsDeviceEnrollment {
        var connected = false
        private var signer: DeviceSigner? = null

        override fun create(): DeviceSigner =
            DeferredSharedListsClientSigner.also { signer = it }

        override fun current(): DeviceSigner? = signer

        override fun delete() {
            signer = null
        }

        override fun exportPublicKey(file: File) {
            file.writeText("public key")
        }
    }

    private object DeferredSharedListsClientSigner : DeviceSigner {
        override val keyFingerprint: String = "test-key"

        override fun signEs256(signingInput: ByteArray): ByteArray = ByteArray(64)
    }

    private companion object {
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    }
}

package dev.sharedlists.windows

import dev.sharedlists.client.CanonicalState
import dev.sharedlists.client.ClientState
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.EditCommand
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.SharedList
import dev.sharedlists.client.SharedListId
import dev.sharedlists.client.SharedListsClient
import dev.sharedlists.client.SynchronizationCursor
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
        assertFalse(controller.presentation().editingEnabled)
        facade.complete(liveState(CanonicalState()))

        assertNull(controller.presentation().statusMessage)
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

    private class StaticWindowsClientFactory(
        private val client: SharedListsClient,
    ) : WindowsClientFactory {
        override fun create(configuration: ServerConfiguration): SharedListsClient = client
    }

    private class InMemoryServerConfigurationStore : ServerConfigurationStore {
        private var configuration: ServerConfiguration? = null

        override fun load(): ServerConfiguration? = configuration

        override fun save(configuration: ServerConfiguration) {
            this.configuration = configuration
        }
    }

    private companion object {
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
    }
}

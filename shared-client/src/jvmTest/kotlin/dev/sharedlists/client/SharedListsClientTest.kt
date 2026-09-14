package dev.sharedlists.client

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedListsClientTest {
    @Test
    fun `client facade exposes the synchronized empty canonical state`() {
        val expected = ClientState.Ready(
            enrollment = EnrollmentState.ENROLLED,
            connectivity = ConnectivityState.LIVE,
            canonicalState = CanonicalState(),
            cursor = SynchronizationCursor("test-generation", 0),
            editingEnabled = true,
        )
        val client: SharedListsClient = StaticSharedListsClient(expected)

        assertEquals(expected, kotlinx.coroutines.runBlocking { client.synchronize() })
    }

    private class StaticSharedListsClient(
        private val state: ClientState,
    ) : SharedListsClient {
        override suspend fun synchronize(): ClientState = state

        override suspend fun submit(command: EditCommand): ClientState = state
    }
}

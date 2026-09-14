package dev.sharedlists.client

import java.nio.file.Files
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
        )
        val client: SharedListsClient = StaticSharedListsClient(expected)

        assertEquals(expected, kotlinx.coroutines.runBlocking { client.synchronize() })
        assertEquals(true, expected.editingEnabled)
    }

    @Test
    fun `file state store atomically retains canonical state with its cursor`() {
        val directory = Files.createTempDirectory("sharedlists-client-state-")
        val file = directory.resolve("state.properties").toFile()
        val expectedState = CanonicalState(
            listOf(SharedList(SharedListId.parse("11111111-1111-4111-8111-111111111111"), "Groceries")),
        )
        val expectedCursor = SynchronizationCursor("test-generation", 7)

        try {
            FileClientStateStore(file).save(expectedState, expectedCursor)
            val reopened = FileClientStateStore(file)

            assertEquals(expectedState, reopened.loadCanonicalState())
            assertEquals(expectedCursor, reopened.loadCursor())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class StaticSharedListsClient(
        private val state: ClientState,
    ) : SharedListsClient {
        override suspend fun synchronize(): ClientState = state

        override suspend fun submit(command: EditCommand): ClientState = state
    }
}

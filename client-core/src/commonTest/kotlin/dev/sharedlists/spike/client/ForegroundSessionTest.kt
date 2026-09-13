package dev.sharedlists.spike.client

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundSessionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun backgroundCancelsTheStreamAndReleasesTransport() = runTest {
        var released = false
        val session = ForegroundSession(this) { released = true }

        session.foreground { awaitCancellation() }
        runCurrent()
        assertTrue(session.isActive)

        session.background()

        assertFalse(session.isActive)
        assertTrue(released)
    }
}

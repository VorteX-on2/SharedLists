package dev.sharedlists.spike.server

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamRegistryTest {
    @Test
    fun newerStreamSupersedesOldAndResourcesRelease() {
        val registry = StreamRegistry()
        val old = Job()
        val replacement = Job()

        registry.register("kid", old)
        assertTrue(old.isActive)
        registry.register("kid", replacement)
        assertFalse(old.isActive)
        assertEquals(1, registry.activeCount())

        registry.release("kid", old)
        assertEquals(1, registry.activeCount())
        registry.release("kid", replacement)
        assertEquals(0, registry.activeCount())
    }
}

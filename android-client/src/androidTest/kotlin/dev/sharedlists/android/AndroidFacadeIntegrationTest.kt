package dev.sharedlists.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedlists.client.EnrollmentState
import dev.sharedlists.client.SharedList
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFacadeIntegrationTest {
    @Test
    fun appliesEditsAndRestoresCachedCanonicalStateWithProductionFacade() {
        val configuration = fixtureConfiguration()
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)
        val listName = "Android cache ${UUID.randomUUID()}"
        val list = AtomicReference<SharedList>()

        assertTrue(controller.configure(configuration.host, configuration.port, configuration.fingerprint))
        controller.onForeground()
        awaitPresentation(controller, "Android facade did not reach LIVE state.") {
            it.enrollment == EnrollmentState.ENROLLED && it.editingEnabled
        }

        controller.createList(listName)
        awaitPresentation(controller, "List creation was not acknowledged by the standalone server.") { presentation ->
            presentation.canonicalState.lists.firstOrNull { it.name == listName }?.also(list::set) != null &&
                presentation.editingEnabled
        }
        controller.createItem(requireNotNull(list.get()).id, "Persisted Android item")
        awaitPresentation(controller, "Item creation was not acknowledged by the standalone server.") { presentation ->
            presentation.canonicalState.lists.singleOrNull { it.name == listName }
                ?.items?.any { it.text == "Persisted Android item" } == true && presentation.editingEnabled
        }
        controller.onBackground()

        val restored = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(restored.configure(configuration.host, "1", configuration.fingerprint))
        restored.onForeground()
        awaitPresentation(restored, "Persisted canonical state was not available before reconnection.") { presentation ->
            presentation.canonicalState.lists.singleOrNull { it.name == listName }
                ?.items?.any { it.text == "Persisted Android item" } == true
        }
        restored.onBackground()
    }

    @Test
    fun backgroundsAuthenticatedFacadeImmediately() {
        val configuration = fixtureConfiguration()
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)
        val connected = CountDownLatch(1)
        val paused = CountDownLatch(1)

        controller.observe { presentation ->
            if (presentation.status == "Synchronized") connected.countDown()
            if (!presentation.editingEnabled && presentation.status == "Synchronization paused in the background.") paused.countDown()
        }
        assertTrue(controller.configure(configuration.host, configuration.port, configuration.fingerprint))
        controller.onForeground()
        assertTrue("Android facade did not reach LIVE state.", connected.await(20, TimeUnit.SECONDS))
        controller.onBackground()
        assertTrue("Foreground stream was not cancelled on background.", paused.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun pausesAndReconnectsAfterAndroidNetworkAvailabilityChange() {
        val configuration = fixtureConfiguration()
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)

        assertTrue(controller.configure(configuration.host, configuration.port, configuration.fingerprint))
        controller.onForeground()
        awaitPresentation(controller, "Android facade did not reach LIVE state.") { it.editingEnabled }
        controller.onNetworkAvailable(false)
        awaitPresentation(controller, "Network loss did not immediately gate editing.") {
            !it.editingEnabled && it.status == "Waiting for a network connection."
        }
        controller.onNetworkAvailable(true)
        awaitPresentation(controller, "Android facade did not reconnect after network recovery.") { it.editingEnabled }
        controller.onBackground()
    }

    @Test
    fun rejectsStandaloneServerWithWrongPinnedIdentity() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("serverHost")
        val port = arguments.getString("serverPort")
        assumeTrue(host != null && port != null)
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)
        val connected = CountDownLatch(1)

        controller.observe { presentation ->
            if (presentation.status == "Synchronized") connected.countDown()
        }
        assertTrue(
            controller.configure(
                requireNotNull(host),
                requireNotNull(port),
                "0000000000000000000000000000000000000000000000000000000000000000",
            ),
        )
        controller.onForeground()

        assertTrue("Mismatched server identity reached LIVE.", !connected.await(2, TimeUnit.SECONDS))
        controller.onBackground()
    }

    @Test
    fun showsReadOnlyRecoveryAfterStandaloneServerRevokesEnrollment() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("expectRevoked") == "true")
        val configuration = fixtureConfiguration()
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)

        assertTrue(controller.configure(configuration.host, configuration.port, configuration.fingerprint))
        controller.onForeground()
        awaitPresentation(controller, "Revoked enrollment was not reported as a recovery state.") {
            it.enrollment == EnrollmentState.UNENROLLED &&
                !it.editingEnabled &&
                it.status == "Device enrollment was revoked. Export the public key for administrator enrollment."
        }
        controller.createList("Must not be submitted after revocation")
        awaitPresentation(controller, "Revoked enrollment did not gate edits.") {
            !it.editingEnabled && it.status == "Editing is available only while synchronized."
        }
        controller.onBackground()
    }

    @Test
    fun authenticatesAndroidKeystoreIdentityWithStandaloneServer() {
        val configuration = fixtureConfiguration()
        val connected = CountDownLatch(1)
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)

        controller.observe { presentation ->
            if (presentation.enrollment == EnrollmentState.ENROLLED && presentation.status == "Synchronized") {
                connected.countDown()
            }
        }
        assertTrue(controller.configure(configuration.host, configuration.port, configuration.fingerprint))
        controller.onForeground()

        assertTrue("Android facade did not reach LIVE state.", connected.await(20, TimeUnit.SECONDS))
        controller.onBackground()
    }

    @Test
    fun gatesEditsWhileBackgroundedAndRecoversOnForeground() {
        val configuration = fixtureConfiguration()
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)

        assertTrue(controller.configure(configuration.host, configuration.port, configuration.fingerprint))
        controller.onForeground()
        awaitPresentation(controller, "Android facade did not reach LIVE state.") { it.editingEnabled }
        controller.onBackground()
        controller.createList("Must not be submitted while backgrounded")
        awaitPresentation(controller, "Background state did not reject an edit.") {
            !it.editingEnabled && it.status == "Editing is available only while synchronized."
        }
        controller.onForeground()
        awaitPresentation(controller, "Android facade did not recover after returning to foreground.") { it.editingEnabled }
        controller.onBackground()
    }

    private fun awaitPresentation(
        controller: AndroidSynchronizationController,
        failureMessage: String,
        predicate: (AndroidClientPresentation) -> Boolean,
    ) {
        val latch = CountDownLatch(1)
        controller.observe { presentation ->
            if (predicate(presentation)) latch.countDown()
        }
        assertTrue(failureMessage, latch.await(20, TimeUnit.SECONDS))
    }

    private fun fixtureConfiguration(): FixtureConfiguration {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("serverHost")
        val fingerprint = arguments.getString("serverFingerprint")
        val port = arguments.getString("serverPort")
        assumeTrue(host != null && fingerprint != null && port != null)
        return FixtureConfiguration(requireNotNull(host), requireNotNull(port), requireNotNull(fingerprint))
    }

    private data class FixtureConfiguration(
        val host: String,
        val port: String,
        val fingerprint: String,
    )
}

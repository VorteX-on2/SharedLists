package dev.sharedlists.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedlists.client.ConnectivityState
import dev.sharedlists.client.EnrollmentState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidFacadeIntegrationTest {
    @Test
    fun authenticatesAndroidKeystoreIdentityWithStandaloneServer() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("serverHost")
        val fingerprint = arguments.getString("serverFingerprint")
        val port = arguments.getString("serverPort")
        assumeTrue(host != null && fingerprint != null && port != null)
        val connected = CountDownLatch(1)
        val controller = AndroidSynchronizationController(InstrumentationRegistry.getInstrumentation().targetContext)

        controller.observe { presentation ->
            if (presentation.enrollment == EnrollmentState.ENROLLED && presentation.status == "Synchronized") {
                connected.countDown()
            }
        }
        assertTrue(controller.configure(requireNotNull(host), requireNotNull(port), requireNotNull(fingerprint)))
        controller.onForeground()

        assertTrue("Android facade did not reach LIVE state.", connected.await(20, TimeUnit.SECONDS))
        controller.onBackground()
    }
}

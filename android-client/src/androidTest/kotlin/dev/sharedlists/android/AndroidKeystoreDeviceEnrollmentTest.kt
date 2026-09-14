package dev.sharedlists.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDeviceEnrollmentTest {
    @Test
    fun createsSignsExportsAndDeletesNonExportableP256Identity() {
        val enrollment = AndroidKeystoreDeviceEnrollment(InstrumentationRegistry.getInstrumentation().targetContext)
        enrollment.delete()
        val first = enrollment.create()
        val signature = first.signEs256("sharedlists-test".encodeToByteArray())

        assertEquals(64, signature.size)
        assertTrue(first.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----\n"))
        assertTrue(first.publicKeyPem.endsWith("-----END PUBLIC KEY-----\n"))
        assertEquals(first.keyFingerprint, enrollment.current()?.keyFingerprint)

        enrollment.delete()
        val replacement = enrollment.create()
        assertNotEquals(first.keyFingerprint, replacement.keyFingerprint)
        enrollment.delete()
    }
}

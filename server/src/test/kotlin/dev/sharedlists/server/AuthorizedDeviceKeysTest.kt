package dev.sharedlists.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthorizedDeviceKeysTest {
    @Test
    fun `loads an enrolled P-256 public key by full fingerprint`() {
        val directory = Files.createTempDirectory("sharedlists-authorized-devices-")
        val signer = TestDeviceSigner.create()

        signer.writePublicKeyPem(directory.resolve("device.pem"))

        assertEquals(setOf(signer.keyFingerprint), AuthorizedDeviceKeys.load(directory).keys)
    }

    @Test
    fun `rejects malformed duplicate certificate and private-key allowlist entries`() {
        val directory = Files.createTempDirectory("sharedlists-invalid-authorized-devices-")
        val signer = TestDeviceSigner.create()
        signer.writePublicKeyPem(directory.resolve("first.pem"))
        signer.writePublicKeyPem(directory.resolve("duplicate.pem"))

        assertFailsWith<IllegalArgumentException> { AuthorizedDeviceKeys.load(directory) }
        Files.delete(directory.resolve("duplicate.pem"))
        Files.writeString(directory.resolve("certificate.pem"), "-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----\n")
        assertFailsWith<IllegalStateException> { AuthorizedDeviceKeys.load(directory) }
        Files.delete(directory.resolve("certificate.pem"))
        Files.writeString(
            directory.resolve("private.pem"),
            "-----BEGIN PRIVATE KEY-----\ninvalid\n-----END PRIVATE KEY-----\n",
        )
        assertFailsWith<IllegalStateException> { AuthorizedDeviceKeys.load(directory) }
    }
}

package dev.sharedlists.windows

import dev.sharedlists.client.DeviceSigner
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.prefs.Preferences

interface WindowsDeviceEnrollment {
    fun create(): DeviceSigner

    fun current(): DeviceSigner?

    fun delete()

    fun exportPublicKey(file: File)
}

class UnreadableDeviceKeyException(
    cause: Throwable,
) : IllegalStateException("The Windows device key cannot be read.", cause)

class WindowsCngDeviceEnrollment(
    private val certificateStore: WindowsCertificateStore = SunMscapiCertificateStore(),
    private val identityStore: WindowsDeviceIdentityStore = PreferencesWindowsDeviceIdentityStore(),
) : WindowsDeviceEnrollment {
    override fun create(): DeviceSigner {
        check(identityStore.loadThumbprint() == null) { "A Windows device key already exists." }
        val thumbprint = certificateStore.createNonExportableP256Certificate()
        identityStore.saveThumbprint(thumbprint)
        return load(thumbprint)
    }

    override fun current(): DeviceSigner? {
        val thumbprint = identityStore.loadThumbprint() ?: return null
        return try {
            load(thumbprint)
        } catch (exception: Exception) {
            throw UnreadableDeviceKeyException(exception)
        }
    }

    override fun delete() {
        val thumbprint = identityStore.loadThumbprint() ?: return
        certificateStore.deleteCertificate(thumbprint)
        identityStore.clear()
    }

    override fun exportPublicKey(file: File) {
        val thumbprint = requireNotNull(identityStore.loadThumbprint()) {
            "A Windows device key must be created before export."
        }
        val signer = load(thumbprint)
        file.parentFile?.mkdirs()
        Files.writeString(file.toPath(), signer.publicKeySpkiDer.toPublicKeyPem())
    }

    private fun load(thumbprint: String): WindowsCertificateStoreSigner =
        WindowsCertificateStoreSigner.open(certificateStore.open(thumbprint))
}

interface WindowsCertificateStore {
    fun createNonExportableP256Certificate(): String

    fun deleteCertificate(thumbprint: String)

    fun open(thumbprint: String): WindowsCertificateHandle
}

interface WindowsCertificateHandle {
    val privateKey: PrivateKey
    val publicKey: ECPublicKey
}

class SunMscapiCertificateStore : WindowsCertificateStore {
    override fun createNonExportableP256Certificate(): String {
        require(System.getProperty("os.name").startsWith("Windows")) {
            "Windows CNG device enrollment requires Windows."
        }
        val command = listOf(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "New-SelfSignedCertificate -Type Custom -Subject 'CN=SharedLists Device' " +
                "-KeyAlgorithm ECDSA_nistP256 -HashAlgorithm SHA256 -KeyExportPolicy NonExportable " +
                "-KeyUsage DigitalSignature -CertStoreLocation Cert:\\CurrentUser\\My " +
                "-Provider 'Microsoft Software Key Storage Provider' | Select-Object -ExpandProperty Thumbprint",
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0 && THUMBPRINT.matches(output)) {
            "Windows CNG device-key creation failed: $output"
        }
        return output
    }

    override fun deleteCertificate(thumbprint: String) {
        val process = ProcessBuilder(
            "certutil.exe",
            "-user",
            "-delstore",
            "My",
            thumbprint,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Windows device-key deletion failed: $output" }
    }

    override fun open(thumbprint: String): WindowsCertificateHandle {
        val store = KeyStore.getInstance("Windows-MY").apply { load(null, null) }
        val alias = store.aliases().asSequence().firstOrNull { alias ->
            alias.equals(thumbprint, ignoreCase = true) ||
                sha1Hex(store.getCertificate(alias).encoded).equals(thumbprint, ignoreCase = true)
        } ?: error("Windows device certificate $thumbprint was not found.")
        val privateKey = store.getKey(alias, null) as? PrivateKey
            ?: error("Windows device certificate $thumbprint has no private signing key.")
        val publicKey = store.getCertificate(alias).publicKey as? ECPublicKey
            ?: error("Windows device certificate $thumbprint is not an EC public key.")
        return object : WindowsCertificateHandle {
            override val privateKey = privateKey
            override val publicKey = publicKey
        }
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }

    private companion object {
        val THUMBPRINT = Regex("^[0-9A-Fa-f]{40}$")
    }
}

class WindowsCertificateStoreSigner private constructor(
    private val privateKey: PrivateKey,
    val publicKeySpkiDer: ByteArray,
) : DeviceSigner {
    override val keyFingerprint: String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKeySpkiDer))

    override fun signEs256(signingInput: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA", "SunMSCAPI").run {
            initSign(privateKey)
            update(signingInput)
            EcdsaDer.toJoseP256(sign())
        }

    companion object {
        fun open(handle: WindowsCertificateHandle): WindowsCertificateStoreSigner {
            require(handle.privateKey.encoded == null) {
                "Refusing exportable private key; expected an opaque Windows CNG handle."
            }
            require(handle.publicKey.params.curve.field.fieldSize == 256) {
                "Windows device key must be P-256."
            }
            return WindowsCertificateStoreSigner(handle.privateKey, handle.publicKey.encoded)
        }
    }
}

interface WindowsDeviceIdentityStore {
    fun clear()

    fun loadThumbprint(): String?

    fun saveThumbprint(thumbprint: String)
}

class PreferencesWindowsDeviceIdentityStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(PreferencesWindowsDeviceIdentityStore::class.java),
) : WindowsDeviceIdentityStore {
    override fun clear() {
        preferences.remove(THUMBPRINT)
    }

    override fun loadThumbprint(): String? = preferences.get(THUMBPRINT, null)

    override fun saveThumbprint(thumbprint: String) {
        preferences.put(THUMBPRINT, thumbprint)
    }

    private companion object {
        const val THUMBPRINT = "deviceCertificateThumbprint"
    }
}

private object EcdsaDer {
    fun toJoseP256(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER sequence." }
        var offset = 1
        val (sequenceLength, sequenceStart) = readLength(der, offset)
        offset = sequenceStart
        require(sequenceLength == der.size - offset) { "Invalid ECDSA DER sequence length." }
        val (r, afterR) = readInteger(der, offset)
        val (s, afterS) = readInteger(der, afterR)
        require(afterS == der.size) { "Trailing ECDSA DER data." }
        return normalize(r) + normalize(s)
    }

    private fun normalize(integer: ByteArray): ByteArray {
        val unsigned = if (integer.size == 33 && integer[0] == 0.toByte()) integer.copyOfRange(1, 33) else integer
        require(unsigned.size <= 32) { "ECDSA component exceeds P-256 width." }
        return ByteArray(32 - unsigned.size) + unsigned
    }

    private fun readInteger(input: ByteArray, offset: Int): Pair<ByteArray, Int> {
        require(offset < input.size && input[offset] == 0x02.toByte()) { "Expected DER INTEGER." }
        val (length, start) = readLength(input, offset + 1)
        require(length in 1..33 && start + length <= input.size) { "Invalid DER INTEGER length." }
        val value = input.copyOfRange(start, start + length)
        require(value[0].toInt() and 0x80 == 0) { "Negative ECDSA integer." }
        return value to start + length
    }

    private fun readLength(input: ByteArray, offset: Int): Pair<Int, Int> {
        require(offset < input.size) { "Missing DER length." }
        val first = input[offset].toInt() and 0xff
        if (first < 0x80) return first to offset + 1
        val octets = first and 0x7f
        require(octets in 1..2 && offset + octets < input.size) { "Invalid DER length." }
        var length = 0
        repeat(octets) { length = (length shl 8) or (input[offset + 1 + it].toInt() and 0xff) }
        return length to offset + 1 + octets
    }
}

private fun ByteArray.toPublicKeyPem(): String {
    val body = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(this)
    return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
}

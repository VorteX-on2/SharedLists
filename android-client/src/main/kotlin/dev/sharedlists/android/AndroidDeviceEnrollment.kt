package dev.sharedlists.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import dev.sharedlists.client.DeviceSigner
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

interface AndroidDeviceEnrollment {
    fun create(): AndroidDeviceSigner

    fun current(): AndroidDeviceSigner?

    fun delete()

    fun publicKeyPem(): String
}

class UnreadableAndroidDeviceKeyException(
    cause: Throwable,
) : IllegalStateException("The Android Keystore device key cannot be read.", cause)

class AndroidKeystoreDeviceEnrollment(
    context: Context,
) : AndroidDeviceEnrollment {
    private val alias = "${context.packageName}.device-signing-key"

    override fun create(): AndroidDeviceSigner {
        check(current() == null) { "An Android device key already exists." }
        return generate(strongBox = true)
    }

    override fun current(): AndroidDeviceSigner? =
        try {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val entry = store.getEntry(alias, null) ?: return null
            val privateEntry = entry as? KeyStore.PrivateKeyEntry
                ?: error("The Android Keystore alias is not a private key.")
            AndroidDeviceSigner.open(alias, privateEntry.privateKey, privateEntry.certificate.publicKey.encoded)
        } catch (exception: UnreadableAndroidDeviceKeyException) {
            throw exception
        } catch (exception: Exception) {
            throw UnreadableAndroidDeviceKeyException(exception)
        }

    override fun delete() {
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
        } catch (exception: Exception) {
            throw UnreadableAndroidDeviceKeyException(exception)
        }
    }

    override fun publicKeyPem(): String = requireNotNull(current()) {
        "Create an Android device key before exporting its public key."
    }.publicKeyPem

    private fun generate(strongBox: Boolean): AndroidDeviceSigner =
        try {
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                val specification = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    specification.setIsStrongBoxBacked(true)
                }
                initialize(
                    specification.build(),
                )
                generateKeyPair()
            }
            AndroidDeviceSigner.open(alias, pair.private, pair.public.encoded)
        } catch (exception: StrongBoxUnavailableException) {
            if (strongBox) generate(strongBox = false) else throw exception
        }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

class AndroidDeviceSigner private constructor(
    private val alias: String,
    private val privateKey: PrivateKey,
    val publicKeySpkiDer: ByteArray,
    val custody: String,
) : DeviceSigner {
    override val keyFingerprint: String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(publicKeySpkiDer),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    val publicKeyPem: String =
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.encodeToString(publicKeySpkiDer, Base64.NO_WRAP).chunked(64).joinToString("\n") +
            "\n-----END PUBLIC KEY-----\n"

    fun delete() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    override fun signEs256(signingInput: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signingInput)
            EcdsaDer.toJoseP256(sign())
        }

    companion object {
        fun open(alias: String, privateKey: PrivateKey, publicKeySpkiDer: ByteArray): AndroidDeviceSigner {
            require(privateKey.encoded == null) {
                "Refusing exportable private key; expected an opaque Android Keystore handle."
            }
            return AndroidDeviceSigner(alias, privateKey, publicKeySpkiDer, custody(privateKey))
        }

        @Suppress("DEPRECATION")
        private fun custody(privateKey: PrivateKey): String {
            val info = KeyFactory.getInstance(privateKey.algorithm, PROVIDER).getKeySpec(privateKey, KeyInfo::class.java)
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    else -> "unknown"
                }
            } else if (info.isInsideSecureHardware) {
                "secure hardware"
            } else {
                "software"
            }
            return "Android Keystore ($level)"
        }

        private const val PROVIDER = "AndroidKeyStore"
    }
}

internal object EcdsaDer {
    fun toJoseP256(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER sequence." }
        val (sequenceLength, offset) = readLength(der, 1)
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

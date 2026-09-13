package dev.sharedlists.spike.client

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.os.Build
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AndroidKeystoreDeviceSigner private constructor(
    private val alias: String,
    private val privateKey: PrivateKey,
    override val publicKeySpkiDer: ByteArray,
    override val custody: String,
) : DeviceSigner {
    override fun signEs256(signingInput: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signingInput)
            sign()
        }
        return EcdsaDer.toJoseP256(der)
    }

    fun delete() {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(alias)
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"

        fun openOrCreate(alias: String): AndroidKeystoreDeviceSigner {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            keyStore.getEntry(alias, null)?.let { entry ->
                val privateEntry = entry as? KeyStore.PrivateKeyEntry
                    ?: error("$alias is not a private-key entry")
                require(privateEntry.privateKey.encoded == null) {
                    "refusing exportable private key; expected Android Keystore custody"
                }
                return AndroidKeystoreDeviceSigner(
                    alias,
                    privateEntry.privateKey,
                    privateEntry.certificate.publicKey.encoded,
                    custody(privateEntry.privateKey),
                )
            }

            val strongBox = runCatching { generate(alias, strongBox = true) }
            val pair = strongBox.getOrElse { failure ->
                if (failure !is StrongBoxUnavailableException) throw failure
                generate(alias, strongBox = false)
            }
            require(pair.private.encoded == null) {
                "refusing exportable private key; expected Android Keystore custody"
            }
            return AndroidKeystoreDeviceSigner(
                alias,
                pair.private,
                pair.public.encoded,
                custody(pair.private),
            )
        }

        @Suppress("DEPRECATION")
        private fun custody(privateKey: PrivateKey): String {
            val keyInfo = KeyFactory.getInstance(privateKey.algorithm, PROVIDER)
                .getKeySpec(privateKey, KeyInfo::class.java)
            val level = if (Build.VERSION.SDK_INT >= 31) {
                when (keyInfo.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    else -> "unknown"
                }
            } else if (keyInfo.isInsideSecureHardware) {
                "secure hardware"
            } else {
                "software"
            }
            return "Android Keystore ($level)"
        }

        private fun generate(alias: String, strongBox: Boolean) =
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
                initialize(spec)
                generateKeyPair()
            }
    }
}

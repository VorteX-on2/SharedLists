package dev.sharedlists.spike.windows

import dev.sharedlists.spike.client.DeviceSigner
import dev.sharedlists.spike.client.EcdsaDer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

internal class WindowsCertificateStoreSigner private constructor(
    private val privateKey: PrivateKey,
    override val publicKeySpkiDer: ByteArray,
    private val alias: String,
) : DeviceSigner {
    override val custody: String =
        "Windows CNG persisted non-exportable key exposed by SunMSCAPI alias $alias"

    override fun signEs256(signingInput: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA", "SunMSCAPI").run {
            initSign(privateKey)
            update(signingInput)
            sign()
        }
        return EcdsaDer.toJoseP256(der)
    }

    companion object {
        fun open(thumbprint: String): WindowsCertificateStoreSigner {
            require(System.getProperty("os.name").startsWith("Windows")) {
                "Windows certificate-store signer requires Windows"
            }
            val store = KeyStore.getInstance("Windows-MY").apply { load(null, null) }
            val alias = store.aliases().asSequence()
                .firstOrNull {
                    it.equals(thumbprint, ignoreCase = true) ||
                        sha1Hex(store.getCertificate(it).encoded).equals(thumbprint, ignoreCase = true)
                }
                ?: error("certificate $thumbprint was not found in Windows-MY")
            val key = store.getKey(alias, null) as? PrivateKey
                ?: error("certificate $thumbprint has no private signing key")
            require(key.encoded == null) {
                "refusing exportable private key; expected an opaque Windows CNG handle"
            }
            val publicKey = store.getCertificate(alias).publicKey as? ECPublicKey
                ?: error("certificate $thumbprint is not an EC key")
            require(publicKey.params.curve.field.fieldSize == 256) { "device key is not P-256" }
            return WindowsCertificateStoreSigner(key, publicKey.encoded, alias)
        }

        private fun sha1Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}

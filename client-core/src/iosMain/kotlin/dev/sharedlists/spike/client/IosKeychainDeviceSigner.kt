@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.sharedlists.spike.client

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSData
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecPrivateKeyAttrs

class IosKeychainDeviceSigner private constructor(
    private val privateKey: SecKeyRef,
    override val publicKeySpkiDer: ByteArray,
    private val secureEnclaveBacked: Boolean,
) : DeviceSigner {
    override val custody: String =
        if (secureEnclaveBacked) "iOS Keychain Secure Enclave" else "iOS Keychain"

    override fun signEs256(signingInput: ByteArray): ByteArray = memScoped {
        val data = signingInput.usePinned {
            CFDataCreate(null, it.addressOf(0).reinterpret(), signingInput.size.toLong())
        } ?: error("could not create signing data")
        try {
            val error = alloc<kotlinx.cinterop.CPointerVarOf<platform.CoreFoundation.CFErrorRef?>>()
            val signature = SecKeyCreateSignature(
                privateKey,
                kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                data,
                error.ptr,
            ) ?: error("SecKeyCreateSignature failed: ${error.value}")
            try {
                EcdsaDer.toJoseP256(signature.toByteArray())
            } finally {
                CFRelease(signature)
            }
        } finally {
            CFRelease(data)
        }
    }

    companion object {
        fun create(applicationTag: NSData): IosKeychainDeviceSigner {
            val secure = runCatching { generate(applicationTag, secureEnclave = true) }
            val key = secure.getOrElse { generate(applicationTag, secureEnclave = false) }
            val publicKey = SecKeyCopyPublicKey(key) ?: error("SecKeyCopyPublicKey failed")
            val external = memScoped {
                val error = alloc<kotlinx.cinterop.CPointerVarOf<platform.CoreFoundation.CFErrorRef?>>()
                SecKeyCopyExternalRepresentation(publicKey, error.ptr)
                    ?: error("SecKeyCopyExternalRepresentation failed: ${error.value}")
            }
            val x963 = external.toByteArray()
            CFRelease(external)
            CFRelease(publicKey)
            require(x963.size == 65 && x963[0] == 4.toByte()) { "unexpected P-256 public key" }
            return IosKeychainDeviceSigner(key, P256_SPKI_PREFIX + x963, secure.isSuccess)
        }

        private fun generate(applicationTag: NSData, secureEnclave: Boolean): SecKeyRef = memScoped {
            val privateAttributes = mapOf(
                kSecAttrIsPermanent to true,
                kSecAttrApplicationTag to applicationTag,
            )
            val attributes = mutableMapOf<Any?, Any?>(
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrKeySizeInBits to 256,
                kSecPrivateKeyAttrs to privateAttributes,
            )
            if (secureEnclave) attributes[kSecAttrTokenID] = kSecAttrTokenIDSecureEnclave
            val error = alloc<kotlinx.cinterop.CPointerVarOf<platform.CoreFoundation.CFErrorRef?>>()
            SecKeyCreateRandomKey(attributes as CFDictionaryRef, error.ptr)
                ?: error("SecKeyCreateRandomKey failed: ${error.value}")
        }

        private val P256_SPKI_PREFIX = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(),
            0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d,
            0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        )
    }
}

private fun platform.CoreFoundation.CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val bytes = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length) { bytes[it].toByte() }
}

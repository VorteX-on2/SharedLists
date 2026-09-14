package dev.sharedlists.server

import dev.sharedlists.client.DeviceSigner
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.openssl.jcajce.JcaPEMWriter

internal class TestDeviceSigner private constructor(
    private val keyPair: KeyPair,
) : DeviceSigner {
    val publicKey = keyPair.public

    override val keyFingerprint: String =
        MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).base64Url()

    override fun signEs256(signingInput: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(signingInput)
            derToJose(sign())
        }

    fun writePublicKeyPem(file: Path) {
        OutputStreamWriter(Files.newOutputStream(file)).use { writer ->
            JcaPEMWriter(writer).use { pemWriter -> pemWriter.writeObject(keyPair.public) }
        }
    }

    companion object {
        fun create(): TestDeviceSigner =
            TestDeviceSigner(
                KeyPairGenerator.getInstance("EC").run {
                    initialize(256)
                    generateKeyPair()
                },
            )
    }
}

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun derToJose(derSignature: ByteArray): ByteArray {
    val objects = ASN1Sequence.getInstance(derSignature).objects
    val values = generateSequence {
        if (objects.hasMoreElements()) objects.nextElement() else null
    }.toList()
    require(values.size == 2) { "ECDSA signature must contain R and S." }
    return values.flatMap { value -> joseInteger(BigInteger(value.toString())) }.toByteArray()
}

private fun joseInteger(value: BigInteger): List<Byte> {
    val source = value.toByteArray().dropWhile { it == 0.toByte() }
    require(source.size <= 32) { "ECDSA component exceeds P-256 width." }
    return List(32 - source.size) { 0.toByte() } + source
}

package dev.sharedlists.spike.client

import kotlin.time.Instant

data class StreamChallenge(
    val nonce: ByteArray,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val audience: String,
)

object StreamJwt {
    const val TYPE = "sharedlists-stream+jwt"

    fun keyId(publicKeySpkiDer: ByteArray, sha256: (ByteArray) -> ByteArray): String =
        sha256(publicKeySpkiDer).base64Url()

    fun create(
        signer: DeviceSigner,
        kid: String,
        challenge: StreamChallenge,
    ): String {
        require(challenge.expiresAt.epochSeconds - challenge.issuedAt.epochSeconds == 60L)
        require(challenge.nonce.size == 32)

        val keyUrn = "urn:sharedlists:key:$kid"
        val header = """{"alg":"ES256","typ":"$TYPE","kid":"$kid"}"""
        val payload =
            """{"iss":"$keyUrn","sub":"$keyUrn","aud":"${challenge.audience}","iat":${challenge.issuedAt.epochSeconds},"exp":${challenge.expiresAt.epochSeconds},"nonce":"${challenge.nonce.base64Url()}"}"""
        val signingInput = "${header.encodeToByteArray().base64Url()}.${payload.encodeToByteArray().base64Url()}"
        val signature = signer.signEs256(signingInput.encodeToByteArray())
        require(signature.size == 64) { "DeviceSigner must return the JOSE 64-byte R || S form" }
        return "$signingInput.${signature.base64Url()}"
    }
}

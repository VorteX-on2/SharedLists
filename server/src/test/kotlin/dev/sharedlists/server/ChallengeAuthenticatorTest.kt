package dev.sharedlists.server

import dev.sharedlists.client.StreamChallenge
import dev.sharedlists.client.StreamJwt
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChallengeAuthenticatorTest {
    private val signer = TestDeviceSigner.create()
    private val audience = "https://127.0.0.1:8443"
    private val now = Instant.parse("2026-09-14T12:00:00Z")

    @Test
    fun `accepts challenge-bound ES256 token exactly once`() {
        val authenticator = authenticator()
        val challenge = authenticator.issue(signer.keyFingerprint)
        val token = StreamJwt.create(signer, challenge.toStreamChallenge())

        assertEquals(signer.keyFingerprint, authenticator.authenticate(token))
        assertEquals(
            AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED,
            assertFailsWith<AuthenticationException> { authenticator.authenticate(token) }.failure,
        )
    }

    @Test
    fun `rejects an authentication token bound to a different audience`() {
        val authenticator = authenticator()
        val challenge = authenticator.issue(signer.keyFingerprint)
        val token = StreamJwt.create(
            signer,
            challenge.toStreamChallenge().copy(audience = "https://other.example:8443"),
        )

        assertEquals(
            AuthFailure.INVALID_AUTHENTICATION,
            assertFailsWith<AuthenticationException> { authenticator.authenticate(token) }.failure,
        )
    }

    @Test
    fun `rejects unknown enrollment before issuing a challenge`() {
        assertEquals(
            AuthFailure.DEVICE_NOT_ENROLLED,
            assertFailsWith<AuthenticationException> { authenticator().issue("not-enrolled") }.failure,
        )
    }

    private fun authenticator() =
        ChallengeAuthenticator(
            authorizedKeys = mapOf(signer.keyFingerprint to signer.publicKey as ECPublicKey),
            audience = audience,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun Challenge.toStreamChallenge() =
        StreamChallenge(
            audience = audience,
            expiresAt = expiresAt,
            issuedAt = issuedAt,
            nonce = nonce,
        )
}

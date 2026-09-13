package dev.sharedlists.spike.server

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChallengeAuthenticatorTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val kid = keyPair.public.encoded.sha256Kid()
    private val now = Instant.parse("2026-09-13T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val audience = "https://lists.example.test"

    @Test
    fun consumesAValidChallengeExactlyOnce() {
        val authenticator = authenticator()
        val challenge = authenticator.issue(kid)
        val jwt = token(challenge)

        assertEquals(kid, authenticator.authenticate(jwt))
        assertFailure(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED) {
            authenticator.authenticate(jwt)
        }
    }

    @Test
    fun newerChallengeInvalidatesThePreviousNonce() {
        val authenticator = authenticator()
        val first = token(authenticator.issue(kid))
        val second = token(authenticator.issue(kid))

        assertFailure(AuthFailure.INVALID_AUTHENTICATION) { authenticator.authenticate(first) }
        assertEquals(kid, authenticator.authenticate(second))
    }

    @Test
    fun rejectsUnknownAndExpiredKeys() {
        assertFailure(AuthFailure.DEVICE_NOT_ENROLLED) { authenticator().issue("unknown") }
        val mutableClock = MutableClock(now)
        val expiringAuthenticator = ChallengeAuthenticator(
            mapOf(kid to keyPair.public as ECPublicKey),
            audience,
            mutableClock,
        )
        val issued = expiringAuthenticator.issue(kid)
        mutableClock.now = now.plusSeconds(61)
        assertFailure(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED) {
            expiringAuthenticator.authenticate(token(issued))
        }
    }

    private fun authenticator() =
        ChallengeAuthenticator(mapOf(kid to keyPair.public as ECPublicKey), audience, clock)

    private fun token(challenge: Challenge): String {
        val keyUrn = "urn:sharedlists:key:$kid"
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType(TOKEN_TYPE))
                .keyID(kid)
                .build(),
            JWTClaimsSet.Builder()
                .issuer(keyUrn)
                .subject(keyUrn)
                .audience(audience)
                .issueTime(Date.from(challenge.issuedAt))
                .expirationTime(Date.from(challenge.expiresAt))
                .claim("nonce", challenge.nonce.base64Url())
                .build(),
        )
        jwt.sign(ECDSASigner(keyPair.private as ECPrivateKey))
        return jwt.serialize()
    }

    private fun assertFailure(expected: AuthFailure, block: () -> Unit) {
        assertEquals(expected, assertFailsWith<AuthenticationException>(block = block).failure)
    }
}

private class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

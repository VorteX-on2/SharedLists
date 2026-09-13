package dev.sharedlists.spike.server

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal const val TOKEN_TYPE = "sharedlists-stream+jwt"

internal enum class AuthFailure(val wireName: String) {
    DEVICE_NOT_ENROLLED("device not enrolled"),
    CHALLENGE_EXPIRED_OR_CONSUMED("challenge expired/consumed"),
    INVALID_AUTHENTICATION("invalid authentication"),
}

internal class AuthenticationException(
    val failure: AuthFailure,
) : RuntimeException(failure.wireName)

internal data class Challenge(
    val nonce: ByteArray,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val audience: String,
)

internal class ChallengeAuthenticator(
    private val authorizedKeys: Map<String, ECPublicKey>,
    private val audience: String,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val challenges = ConcurrentHashMap<String, Challenge>()

    fun issue(kid: String): Challenge {
        if (!authorizedKeys.containsKey(kid)) {
            throw AuthenticationException(AuthFailure.DEVICE_NOT_ENROLLED)
        }
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return Challenge(
            nonce = ByteArray(32).also(secureRandom::nextBytes),
            issuedAt = issuedAt,
            expiresAt = issuedAt.plusSeconds(60),
            audience = audience,
        ).also { challenges[kid] = it }
    }

    fun authenticate(compactJwt: String): String {
        val jwt = runCatching { SignedJWT.parse(compactJwt) }
            .getOrElse { throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION) }
        val header = jwt.header
        val kid = header.keyID ?: throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
        val publicKey = authorizedKeys[kid]
            ?: throw AuthenticationException(AuthFailure.DEVICE_NOT_ENROLLED)

        if (
            header.algorithm != JWSAlgorithm.ES256 ||
            header.type != JOSEObjectType(TOKEN_TYPE) ||
            header.includedParams != setOf("alg", "typ", "kid")
        ) {
            System.err.println("authentication rejected: protected header did not match pinned alg/typ/kid")
            throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
        }

        synchronized(challenges) {
            val challenge = challenges[kid]
                ?: throw AuthenticationException(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED)
            if (!clock.instant().isBefore(challenge.expiresAt)) {
                challenges.remove(kid, challenge)
                throw AuthenticationException(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED)
            }

            val claims = runCatching { jwt.jwtClaimsSet }
                .getOrElse { throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION) }
            val keyUrn = "urn:sharedlists:key:$kid"
            val claimsMatch =
                claims.issuer == keyUrn &&
                    claims.subject == keyUrn &&
                    claims.audience == listOf(audience) &&
                    claims.issueTime?.toInstant() == challenge.issuedAt &&
                    claims.expirationTime?.toInstant() == challenge.expiresAt &&
                    claims.getStringClaim("nonce") == challenge.nonce.base64Url()
            if (!claimsMatch) {
                System.err.println("authentication rejected: required claims did not match the issued challenge")
                throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
            }
            if (!runCatching { jwt.verify(ECDSAVerifier(publicKey)) }.getOrDefault(false)) {
                System.err.println("authentication rejected: ES256 signature verification failed")
                throw AuthenticationException(AuthFailure.INVALID_AUTHENTICATION)
            }

            if (!challenges.remove(kid, challenge)) {
                throw AuthenticationException(AuthFailure.CHALLENGE_EXPIRED_OR_CONSUMED)
            }
        }
        return kid
    }
}

internal fun ByteArray.sha256Kid(): String =
    MessageDigest.getInstance("SHA-256").digest(this).base64Url()

internal fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

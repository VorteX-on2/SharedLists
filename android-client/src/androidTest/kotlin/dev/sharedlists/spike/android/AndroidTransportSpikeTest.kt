package dev.sharedlists.spike.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedlists.spike.client.AndroidKeystoreDeviceSigner
import dev.sharedlists.spike.client.ForegroundSession
import dev.sharedlists.spike.client.SharedListsTransport
import dev.sharedlists.spike.client.StreamJwt
import dev.sharedlists.spike.protocol.SyncRequest
import dev.sharedlists.spike.protocol.invoke
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AndroidTransportSpikeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportKey() {
        AndroidKeystoreDeviceSigner.openOrCreate(KEY_ALIAS).use { signer ->
            val body = Base64.encodeToString(signer.publicKeySpkiDer, Base64.NO_WRAP)
                .chunked(64)
                .joinToString("\n")
            context.openFileOutput("device.pem", 0).bufferedWriter().use {
                it.write("-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n")
            }
            println("ANDROID_KEY kid=${kid(signer.publicKeySpkiDer)} custody=${signer.custody}")
        }
    }

    @Test
    fun authenticatedTransport() = runBlocking {
        val (certificate, audience) = endpointArguments()
        AndroidKeystoreDeviceSigner.openOrCreate(KEY_ALIAS).use { signer ->
            val keyId = kid(signer.publicKeySpkiDer)
            val transport = SharedListsTransport(
                host = "10.0.2.2",
                port = 19443,
                trustedCertificatePem = certificate,
                tlsAuthority = "localhost",
            )
            val challenge = transport.challenge(keyId)
            check(challenge.audience == audience)
            val token = StreamJwt.create(signer, keyId, challenge)
            transport.authenticated(token).use { authenticated ->
                val response = authenticated.sync(flow {
                    emit(SyncRequest { payload = "android-emulator" })
                }).first()
                check(response.payload == "android-emulator")
                check(response.authenticatedKid == keyId)
            }
            check(runCatching {
                transport.authenticated(token).use { replay ->
                    replay.sync(flow { emit(SyncRequest { payload = "replay" }) }).first()
                }
            }.isFailure)

            coroutineScope {
                val oldChallenge = transport.challenge(keyId)
                val old = transport.authenticated(StreamJwt.create(signer, keyId, oldChallenge))
                val oldStream = async {
                    runCatching {
                        old.sync(flow {
                            emit(SyncRequest { payload = "old" })
                            awaitCancellation()
                        }).collect {}
                    }
                }
                delay(250)

                val replacementChallenge = transport.challenge(keyId)
                transport.authenticated(StreamJwt.create(signer, keyId, replacementChallenge)).use { replacement ->
                    check(replacement.sync(flow {
                        emit(SyncRequest { payload = "replacement" })
                    }).first().payload == "replacement")
                }
                check(oldStream.await().isFailure)
                old.close()
            }

            coroutineScope {
                var released = false
                val lifecycleChallenge = transport.challenge(keyId)
                val authenticated = transport.authenticated(StreamJwt.create(signer, keyId, lifecycleChallenge))
                val session = ForegroundSession(this) {
                    authenticated.close()
                    released = true
                }
                session.foreground {
                    authenticated.sync(flow {
                        emit(SyncRequest { payload = "foreground" })
                        awaitCancellation()
                    }).collect {}
                }
                delay(250)
                check(session.isActive)
                session.background()
                check(!session.isActive)
                check(released)
            }
        }
        println(
            "PASS Android emulator TLS protobuf bidi, opaque Keystore signing, replay, " +
                "stream supersession, and foreground/background closure",
        )
    }

    @Test
    fun revokedKeyRejected() = runBlocking {
        val (certificate, _) = endpointArguments()
        AndroidKeystoreDeviceSigner.openOrCreate(KEY_ALIAS).use { signer ->
            val transport = SharedListsTransport(
                host = "10.0.2.2",
                port = 19443,
                trustedCertificatePem = certificate,
                tlsAuthority = "localhost",
            )
            check(runCatching { transport.challenge(kid(signer.publicKeySpkiDer)) }.isFailure)
        }
        println("PASS Android emulator revoked key rejected")
    }

    @Test
    fun deletingKeyCreatesANewDeviceIdentity() {
        val old = AndroidKeystoreDeviceSigner.openOrCreate(KEY_ALIAS)
        val oldKid = kid(old.publicKeySpkiDer)
        old.delete()
        val replacement = AndroidKeystoreDeviceSigner.openOrCreate(KEY_ALIAS)
        val replacementKid = kid(replacement.publicKeySpkiDer)
        check(oldKid != replacementKid)
        replacement.delete()
        println("PASS Android emulator key deletion creates a new identity")
    }

    private fun endpointArguments(): Pair<String, String> {
        val arguments = InstrumentationRegistry.getArguments()
        val certificate = String(
            Base64.decode(requireNotNull(arguments.getString("certBase64")), Base64.DEFAULT),
        )
        return certificate to requireNotNull(arguments.getString("audience"))
    }

    private fun kid(spki: ByteArray): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(spki),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private companion object {
        const val KEY_ALIAS = "sharedlists-issue-13-spike"
    }
}

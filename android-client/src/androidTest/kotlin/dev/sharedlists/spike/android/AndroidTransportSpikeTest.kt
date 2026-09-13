package dev.sharedlists.spike.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedlists.spike.client.AndroidKeystoreDeviceSigner
import dev.sharedlists.spike.client.SharedListsTransport
import dev.sharedlists.spike.client.StreamJwt
import dev.sharedlists.spike.protocol.SyncRequest
import dev.sharedlists.spike.protocol.invoke
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
        val arguments = InstrumentationRegistry.getArguments()
        val certificate = String(
            Base64.decode(requireNotNull(arguments.getString("certBase64")), Base64.DEFAULT),
        )
        val audience = requireNotNull(arguments.getString("audience"))
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
        }
        println("PASS Android emulator TLS protobuf bidi, opaque Keystore signing, replay, and closure")
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

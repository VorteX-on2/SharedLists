package dev.sharedlists.spike.windows

import dev.sharedlists.spike.client.SharedListsTransport
import dev.sharedlists.spike.client.StreamJwt
import dev.sharedlists.spike.protocol.SyncRequest
import dev.sharedlists.spike.protocol.invoke
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

fun main(args: Array<String>) = runBlocking {
    when (args.firstOrNull()) {
        "export" -> export(args)
        "run" -> run(args)
        "expect-revoked" -> expectRevoked(args)
        else -> error(
            "usage: export <thumbprint> <public-key.pem> | " +
                "run <thumbprint> <port> <tls-cert.pem> <audience> | " +
                "expect-revoked <thumbprint> <port> <tls-cert.pem> <audience>",
        )
    }
}

private fun export(args: Array<String>) {
    require(args.size == 3)
    WindowsCertificateStoreSigner.open(args[1]).use { signer ->
        val body = Base64.getMimeEncoder(64, "\n".encodeToByteArray())
            .encodeToString(signer.publicKeySpkiDer)
        Files.writeString(
            Path.of(args[2]),
            "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n",
        )
        println("EXPORTED kid=${kid(signer.publicKeySpkiDer)} custody=${signer.custody}")
    }
}

private suspend fun run(args: Array<String>) {
    require(args.size == 5)
    val signer = WindowsCertificateStoreSigner.open(args[1])
    val certificate = Files.readString(Path.of(args[3]))
    val transport = SharedListsTransport("localhost", args[2].toInt(), certificate)
    val keyId = kid(signer.publicKeySpkiDer)

    val firstChallenge = transport.challenge(keyId)
    check(firstChallenge.audience == args[4]) { "challenge audience does not match configured endpoint" }
    val replayToken = StreamJwt.create(signer, keyId, firstChallenge)
    transport.authenticated(replayToken).use { authenticated ->
        val response = authenticated.sync(flow { emit(SyncRequest { payload = "protobuf-over-tls" }) }).first()
        check(response.payload == "protobuf-over-tls")
        check(response.authenticatedKid == keyId)
    }
    val replayRejected = runCatching {
        transport.authenticated(replayToken).use { replay ->
            replay.sync(flow { emit(SyncRequest { payload = "replay" }) }).first()
        }
    }.isFailure
    check(replayRejected) { "server accepted a consumed challenge" }

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

        val replacementChallenge = transport.challenge(keyId)
        val replacement = transport.authenticated(StreamJwt.create(signer, keyId, replacementChallenge))
        val response = replacement.sync(flow { emit(SyncRequest { payload = "replacement" }) }).first()
        check(response.payload == "replacement")
        check(oldStream.await().isFailure) { "older stream was not superseded" }
        replacement.close()
        old.close()
    }

    signer.close()
    println("PASS TLS protobuf bidi, replay rejection, stream supersession, and foreground resource closure")
}

private suspend fun expectRevoked(args: Array<String>) {
    require(args.size == 5)
    WindowsCertificateStoreSigner.open(args[1]).use { signer ->
        val transport = SharedListsTransport(
            "localhost",
            args[2].toInt(),
            Files.readString(Path.of(args[3])),
        )
        check(runCatching { transport.challenge(kid(signer.publicKeySpkiDer)) }.isFailure) {
            "revoked key unexpectedly received a challenge"
        }
        println("PASS revoked key rejected")
    }
}

private fun kid(spki: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))

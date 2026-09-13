package dev.sharedlists.spike.client

interface DeviceSigner : AutoCloseable {
    val publicKeySpkiDer: ByteArray
    val custody: String

    fun signEs256(signingInput: ByteArray): ByteArray

    override fun close() = Unit
}

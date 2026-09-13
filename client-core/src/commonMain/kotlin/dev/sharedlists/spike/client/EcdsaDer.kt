package dev.sharedlists.spike.client

object EcdsaDer {
    fun toJoseP256(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "invalid ECDSA DER sequence" }
        var offset = 1
        val (sequenceLength, afterSequenceLength) = readLength(der, offset)
        offset = afterSequenceLength
        require(sequenceLength == der.size - offset) { "invalid ECDSA DER sequence length" }
        val (r, afterR) = readInteger(der, offset)
        val (s, afterS) = readInteger(der, afterR)
        require(afterS == der.size) { "trailing ECDSA DER data" }
        return normalize(r) + normalize(s)
    }

    private fun readInteger(input: ByteArray, offset: Int): Pair<ByteArray, Int> {
        require(offset < input.size && input[offset] == 0x02.toByte()) { "expected DER INTEGER" }
        val (length, start) = readLength(input, offset + 1)
        require(length in 1..33 && start + length <= input.size) { "invalid DER INTEGER length" }
        val value = input.copyOfRange(start, start + length)
        require(value[0].toInt() and 0x80 == 0) { "negative ECDSA integer" }
        return value to (start + length)
    }

    private fun readLength(input: ByteArray, offset: Int): Pair<Int, Int> {
        require(offset < input.size) { "missing DER length" }
        val first = input[offset].toInt() and 0xff
        if (first < 0x80) return first to offset + 1
        val octets = first and 0x7f
        require(octets in 1..2 && offset + octets < input.size) { "invalid DER length" }
        var length = 0
        repeat(octets) { length = (length shl 8) or (input[offset + 1 + it].toInt() and 0xff) }
        return length to offset + 1 + octets
    }

    private fun normalize(integer: ByteArray): ByteArray {
        val unsigned = if (integer.size == 33 && integer[0] == 0.toByte()) integer.copyOfRange(1, 33) else integer
        require(unsigned.size <= 32) { "P-256 component exceeds 32 bytes" }
        return ByteArray(32 - unsigned.size) + unsigned
    }
}

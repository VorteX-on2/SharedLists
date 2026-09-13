package dev.sharedlists.spike.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class EcdsaDerTest {
    @Test
    fun convertsDerIntegersToFixedWidthJoseSignature() {
        val r = byteArrayOf(0, 0x80.toByte()) + ByteArray(31) { 1 }
        val s = byteArrayOf(0x7f) + ByteArray(30) { 2 }
        val der = byteArrayOf(0x30, 0x44, 0x02, 0x21) + r + byteArrayOf(0x02, 0x1f) + s

        val jose = EcdsaDer.toJoseP256(der)

        assertContentEquals(r.copyOfRange(1, 33), jose.copyOfRange(0, 32))
        assertContentEquals(byteArrayOf(0) + s, jose.copyOfRange(32, 64))
    }

    @Test
    fun rejectsTrailingData() {
        assertFailsWith<IllegalArgumentException> {
            EcdsaDer.toJoseP256(byteArrayOf(0x30, 0x06, 0x02, 1, 1, 0x02, 1, 1, 0))
        }
    }
}

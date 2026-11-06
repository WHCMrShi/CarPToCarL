package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ControlCipherStreamTest {
    private val key = ByteArray(32) { it.toByte() }

    private fun legacyWire(
        plain: ByteArray,
        counterStart: Long = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        var counter = counterStart
        do {
            val part = plain.copyOfRange(offset, minOf(offset + 1422, plain.size))
            val aad = byteArrayOf(part.size.toByte(), (part.size ushr 8).toByte())
            out.write(aad)
            out.write(AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter++), part, aad))
            offset += part.size
        } while (offset < plain.size)
        return out.toByteArray()
    }

    @Test fun optimizedWireExactlyMatchesExistingFramingAtMtuBoundaries() {
        for (size in listOf(0, 1, 1421, 1422, 1423, 2844, 20000)) {
            val plain = ByteArray(size) { (it % 251).toByte() }
            assertArrayEquals(legacyWire(plain), ControlCipher(key, key).encrypt(plain))
        }
    }

    @Test fun streamingDecryptAcceptsEverySplitAndKeepsNonceAcrossMessages() {
        val a = ByteArray(3000) { it.toByte() }
        val b = byteArrayOf(3, 4, 5)
        val sender = ControlCipher(key, key)
        val wire = sender.encrypt(a) + sender.encrypt(b)
        for (split in listOf(0, 1, 2, 17, 1439, 1440, wire.size - 1, wire.size)) {
            val reader = ControlCipher(key, key).newReader()
            val out = ByteArrayOutputStream()
            val sink: (ByteArray) -> Unit = { out.write(it) }
            reader.offer(wire, 0, split, sink)
            reader.offer(wire, split, wire.size - split, sink)
            assertArrayEquals(a + b, out.toByteArray())
        }
    }

    @Test fun eachByteFragmentAndLargerReceiveFramesRemainSupported() {
        val body = ByteArray(5000) { (it % 127).toByte() }
        val aad = byteArrayOf(body.size.toByte(), (body.size ushr 8).toByte())
        val wire = aad + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), body, aad)
        val reader = ControlCipher(key, key).newReader()
        val out = ByteArrayOutputStream()
        for (i in wire.indices) reader.offer(wire, i, 1) { out.write(it) }
        assertArrayEquals(body, out.toByteArray())
    }

    @Test fun badAuthenticationNeverEmitsOrAdvancesNonce() {
        val owner = ControlCipher(key, key)
        val wire = legacyWire(byteArrayOf(1, 2))
        val broken = wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        var emitted = 0
        try {
            owner.newReader().offer(broken, 0, broken.size) { emitted++ }
            throw AssertionError("authentication should fail")
        } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
        }
        assertEquals(0, emitted)
        assertArrayEquals(byteArrayOf(1, 2), owner.decrypt(wire).data)
    }

    @Test fun encryptedFragmentedRtspMessagesReachSharedDecoderInOrder() {
        val sender = ControlCipher(key, key)
        val reader = ControlCipher(key, key).newReader()
        val parser = RtspStreamDecoder()
        val wire =
            sender.encrypt("RTSP/1.0 200 OK\r\nContent-Length: 3\r\n\r\nabc".toByteArray()) +
                sender.encrypt("POST /command RTSP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
        val messages = mutableListOf<RtspStreamDecoder.Message>()
        val sink: (ByteArray) -> Unit = { messages.addAll(parser.offer(it)) }
        for (offset in wire.indices step 7) reader.offer(wire, offset, minOf(7, wire.size - offset), sink)
        assertEquals(2, messages.size)
        assertArrayEquals("abc".toByteArray(), messages[0].body)
        assertTrue(messages[1].body.isEmpty())
    }
}

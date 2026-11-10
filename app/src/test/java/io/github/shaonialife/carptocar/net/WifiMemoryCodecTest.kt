package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession.WifiConfiguration
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class WifiMemoryCodecTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val record = VerifiedWifi("AA:BB:CC:DD:EE:FF", WifiConfiguration("q4", "private-password", 149, 2), 100L)
    @Test fun authenticatedRoundTripAndRandomNonce() {
        val key = key()
        val first = WifiMemoryCodec.encode(key, record)
        val second = WifiMemoryCodec.encode(key, record)
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("private-password"))
        assertEquals(record, WifiMemoryCodec.decode(key, record.target.lowercase(), first).copy(target = record.target))
        assertFalse(record.toString().contains(record.wifi.passphrase))
    }
    @Test fun changedTargetOrKeyCannotRestoreCredentials() {
        val key = key(); val bytes = WifiMemoryCodec.encode(key, record)
        assertThrows(Exception::class.java) { WifiMemoryCodec.decode(key, "11:22:33:44:55:66", bytes) }
        assertThrows(Exception::class.java) { WifiMemoryCodec.decode(key(), record.target, bytes) }
    }
    @Test fun modifiedOrTruncatedCiphertextFailsClosed() {
        val key = key(); val bytes = WifiMemoryCodec.encode(key, record)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { WifiMemoryCodec.decode(key, record.target, bytes) }
        assertThrows(Exception::class.java) { WifiMemoryCodec.decode(key, record.target, byteArrayOf(12)) }
    }
}

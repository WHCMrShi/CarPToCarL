package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PcmPacketSealerTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private fun reference() = AudioSender("127.0.0.1", 5000, key, {})

    @Test
    fun reusedCipherAndBuffersMatchLegacyWireAcrossWrapsAndChangingSamples() {
        val sealer = PcmPacketSealer(key, 100)
        val legacy = reference()
        val input = ByteArray(AudioSender.PACKET_BYTES + 11)
        var previousWire: ByteArray? = null
        repeat(1000) { packet ->
            input.indices.forEach { input[it] = (it * 13 + packet * 17).toByte() }
            val unchanged = input.copyOf()
            val pcm = input.copyOfRange(7, 7 + AudioSender.PACKET_BYTES)
            val seq = (65530 + packet) and 0xffff
            val timestamp = 0xfffffff0L + packet * 352L
            val nonce = 0x100000000L + packet
            val wire = sealer.seal(input, 7, seq, timestamp, nonce, packet == 0)
            assertArrayEquals(legacy.sealPacket(pcm, seq, timestamp, nonce, packet == 0), wire)
            assertArrayEquals(unchanged, input)
            if (previousWire != null) assertSame(previousWire, wire)
            previousWire = wire
            val opened = AirPlayCrypto.chachaOpen(
                key, AirPlayCrypto.nonce64(nonce), wire.copyOfRange(12, wire.size - 8), wire.copyOfRange(4, 12),
            )
            assertArrayEquals(pcm, AudioSender.bigEndianS16(opened))
        }
    }

    @Test
    fun nonceReuseAndCounterRollbackAreRejected() {
        val sealer = PcmPacketSealer(key, 100)
        val pcm = ByteArray(AudioSender.PACKET_BYTES)
        sealer.seal(pcm, 0, 0, 0, 9, true)
        for (counter in listOf(9L, 8L, -1L)) {
            try {
                sealer.seal(pcm, 0, 1, 352, counter, false)
                fail("accepted repeated or rolled-back nonce")
            } catch (_: IllegalArgumentException) { }
        }
        sealer.seal(pcm, 0, 1, 352, 10, false)
    }

    @Test
    fun differentStreamsOwnIndependentKeysAndBuffers() {
        val pcm = ByteArray(AudioSender.PACKET_BYTES)
        val first = PcmPacketSealer(key, 100).seal(pcm, 0, 0, 0, 0, true)
        val second = PcmPacketSealer(ByteArray(32) { 99 }, 100).seal(pcm, 0, 0, 0, 0, true)
        assertNotSame(first, second)
        assertFalse(first.contentEquals(second))
    }
    @Test
    fun senderReusedUdpPacketCarriesCorrectBytesAcrossSequentialSends() {
        val sender = reference()
        val source = ByteArray(AudioSender.PACKET_BYTES + 9)
        java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2_000
            java.net.DatagramSocket().use { client ->
                client.connect(receiver.localAddress, receiver.localPort)
                val type = AudioSender::class.java
                val running = type.getDeclaredField("running").apply { isAccessible = true }
                    .get(sender) as java.util.concurrent.atomic.AtomicBoolean
                type.getDeclaredField("socket").apply { isAccessible = true }.set(sender, client)
                val send = type.getDeclaredMethod("sendPayload", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                running.set(true)
                try {
                    repeat(3) { index ->
                        source.indices.forEach { source[it] = (it + index * 29).toByte() }
                        val pcm = source.copyOfRange(5, 5 + AudioSender.PACKET_BYTES)
                        assertEquals(true, send.invoke(sender, source, 5, AudioSender.PACKET_BYTES))
                        val received = java.net.DatagramPacket(ByteArray(2048), 2048)
                        receiver.receive(received)
                        assertArrayEquals(
                            sender.sealPacket(pcm, index, index * 352L, index.toLong(), index == 0),
                            received.data.copyOf(received.length),
                        )
                    }
                } finally {
                    running.set(false)
                }
            }
        }
    }
}

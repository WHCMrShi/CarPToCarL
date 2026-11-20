package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NavigationAudioSenderTest {
    @Test
    fun sendsMonoPcmOnType101WithNetworkByteOrderAndSampleTimestamps() = assertPcmRtp(1)

    @Test
    fun averages48kStereoChannelsWithoutResampling() = assertPcmRtp(2)

    @Test
    fun padsShortPromptWithZeros() = assertPcmRtp(1, sampleCount = 1)

    @Test
    fun padsOnlyTheLastPacketOfAPartialPrompt() = assertPcmRtp(1, sampleCount = 703)

    private fun assertPcmRtp(channels: Int, sampleCount: Int = 704) {
        val key = ByteArray(32) { it.toByte() }
        val host = "127.0.0.1"
        val loopback = InetAddress.getByName(host)
        val samplesPerPacket = 352
        val packetCount = (sampleCount + samplesPerPacket - 1) / samplesPerPacket
        val pcm = ByteBuffer.allocate(sampleCount * 2 * channels).order(ByteOrder.LITTLE_ENDIAN)
        val expected = ByteBuffer.allocate(packetCount * samplesPerPacket * 2).order(ByteOrder.BIG_ENDIAN)
        repeat(sampleCount) { index ->
            val sample = (index * 83 - 30_000).toShort()
            if (channels == 1) pcm.putShort(sample)
            else {
                pcm.putShort((sample - 1000).toShort())
                pcm.putShort((sample + 1000).toShort())
            }
            expected.putShort(sample)
        }

        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 2_000
            val stream = AirPlayClient.AudioStreamInfo(1L, receiver.localPort, 0, key)
            val idle = CountDownLatch(1)
            NavigationAudioSender(host, stream, { if (it.contains("9241 idle 60ms")) idle.countDown() }).use { sender ->
                sender.start()
                sender.offer(pcm.array(), pcm.capacity(), 48_000, channels, 16, this, 0)
                var firstTimestamp = 0
                repeat(packetCount) { index ->
                    val packet = DatagramPacket(ByteArray(2_048), 2_048)
                    receiver.receive(packet)
                    val wire = packet.data.copyOf(packet.length)
                    val header = ByteBuffer.wrap(wire)
                    assertEquals(12 + 2 * samplesPerPacket + 16 + 8, wire.size)
                    assertEquals(0x80, header.get().toInt() and 255)
                    assertEquals(if (index == 0) 0xe5 else 101, header.get().toInt() and 255)
                    assertEquals(index, header.short.toInt())
                    val timestamp = header.int
                    if (index == 0) firstTimestamp = timestamp else assertEquals(samplesPerPacket, timestamp - firstTimestamp)
                    assertEquals(0, header.int)
                    val nonce = AirPlayCrypto.nonce64(index + 1L)
                    assertArrayEquals(nonce.copyOfRange(4, 12), wire.copyOfRange(wire.size - 8, wire.size))
                    val receivedPcm = AirPlayCrypto.chachaOpen(key, nonce, wire.copyOfRange(12, wire.size - 8), wire.copyOfRange(4, 12))
                    val from = index * 2 * samplesPerPacket
                    assertArrayEquals(expected.array().copyOfRange(from, from + 2 * samplesPerPacket), receivedPcm)
                }
                assertTrue(idle.await(2, TimeUnit.SECONDS))
                receiver.soTimeout = 100
                assertThrows(SocketTimeoutException::class.java) {
                    receiver.receive(DatagramPacket(ByteArray(2_048), 2_048))
                }
            }
        }
    }
}

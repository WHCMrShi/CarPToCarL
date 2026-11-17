package io.github.shaonialife.carptocar.audio

import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.nio.channels.spi.SelectorProvider
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AudioSenderNonBlockingTest {
    @Test fun zeroWriteDropsWithoutRetryAndNextPacketKeepsAdvancedRtpAndNonce() {
        val sender = AudioSender("127.0.0.1", 5000, ByteArray(32), {})
        ScriptedChannel().use { channel ->
            val type = AudioSender::class.java
            fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
            field("socket").set(sender, channel)
            (field("running").get(sender) as AtomicBoolean).set(true)
            val send = type.getDeclaredMethod("sendPayload", ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val pcm = ByteArray(AudioSender.PACKET_BYTES)
            assertEquals(true, send.invoke(sender, pcm, 0, pcm.size))
            assertEquals(1, channel.calls)
            assertEquals(0L, field("packetsSent").getLong(sender))
            assertEquals(0L, field("bytesSent").getLong(sender))
            assertEquals(true, send.invoke(sender, pcm, 0, pcm.size))
            assertEquals(2, channel.calls)
            assertArrayEquals(sender.sealPacket(pcm, 1, 352, 1, false), channel.packet)
            assertEquals(1L, field("packetsSent").getLong(sender))
            assertEquals(channel.packet!!.size.toLong(), field("bytesSent").getLong(sender))
            assertEquals(false, send.invoke(sender, pcm, 0, pcm.size))
            assertEquals(3, channel.calls) // IOException retains the existing failure result; no retry.
            assertEquals(1L, field("packetsSent").getLong(sender))
        }
    }

    private class ScriptedChannel : DatagramChannel(SelectorProvider.provider()) {
        var calls = 0
        var packet: ByteArray? = null
        override fun write(src: ByteBuffer): Int = when (++calls) {
            1 -> 0
            2 -> src.remaining().also { count -> packet = ByteArray(count).also(src::get) }
            else -> throw IOException("test write failure")
        }
        override fun implCloseSelectableChannel() {}
        override fun implConfigureBlocking(block: Boolean) {}
        override fun bind(local: SocketAddress?): DatagramChannel = error("unused")
        override fun <T> setOption(name: SocketOption<T>, value: T): DatagramChannel = error("unused")
        override fun <T> getOption(name: SocketOption<T>): T = error("unused")
        override fun supportedOptions(): Set<SocketOption<*>> = emptySet()
        override fun socket(): DatagramSocket = error("unused")
        override fun isConnected() = true
        override fun connect(remote: SocketAddress): DatagramChannel = error("unused")
        override fun disconnect(): DatagramChannel = error("unused")
        override fun getRemoteAddress(): SocketAddress = error("unused")
        override fun getLocalAddress(): SocketAddress = error("unused")
        override fun receive(dst: ByteBuffer): SocketAddress = error("unused")
        override fun send(src: ByteBuffer, target: SocketAddress): Int = error("unused")
        override fun read(dst: ByteBuffer): Int = error("unused")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun join(group: InetAddress, interf: NetworkInterface): MembershipKey = error("unused")
        override fun join(group: InetAddress, interf: NetworkInterface, source: InetAddress): MembershipKey = error("unused")
    }
}

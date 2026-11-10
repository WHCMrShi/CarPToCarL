package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class PcmFrameBufferTest {
    @Test
    fun arbitraryFragmentsPreserveEveryByteAndDoNotRetainInputTail() {
        val random = Random(42)
        val source = ByteArray(AudioSender.PACKET_BYTES * 100).also { random.nextBytes(it) }
        val buffer = PcmFrameBuffer(AudioSender.PACKET_BYTES)
        val out = ByteArrayOutputStream()
        var offset = 0
        val emit = PcmFrameConsumer { data, start ->
            out.write(data, start, AudioSender.PACKET_BYTES)
            true
        }
        while (offset < source.size) {
            val count = minOf(1 + random.nextInt(10_000), source.size - offset)
            val input = ByteArray(count + 31)
            source.copyInto(input, 0, offset, offset + count)
            buffer.feed(input, count, emit)
            input.fill(0) // 不能保留调用方会重用的输入或越过有效长度。
            offset += count
        }
        assertArrayEquals(source, out.toByteArray())
    }

    @Test
    fun completeFramesBorrowOriginalArrayAtCorrectOffsets() {
        val frame = AudioSender.PACKET_BYTES
        val source = ByteArray(frame * 10)
        val buffer = PcmFrameBuffer(frame)
        var count = 0
        buffer.feed(source, source.size) { data, offset ->
            assertSame(source, data)
            assertEquals(count++ * frame, offset)
            true
        }
        assertEquals(10, count)
    }

    @Test
    fun stopOrResetDiscardsTailAndStopsCurrentBatch() {
        val buffer = PcmFrameBuffer(4)
        var count = 0
        buffer.feed(ByteArray(20), 20) { _, _ -> count++; false }
        assertEquals(1, count)
        buffer.feed(byteArrayOf(1, 2), 2) { _, _ -> fail("partial frame emitted"); true }
        buffer.clear()
        buffer.feed(byteArrayOf(3, 4, 5, 6), 4) { data, offset ->
            assertArrayEquals(byteArrayOf(3, 4, 5, 6), data.copyOfRange(offset, offset + 4))
            true
        }
    }
}

package io.github.shaonialife.carptocar.audio

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class Pcm441To480Test {
    private fun pcm(frames: Int, value: (Int, Int) -> Int): ByteArray = ByteArray(frames * 4).also { out ->
        repeat(frames) { f -> repeat(2) { ch ->
            val sample = value(f, ch)
            out[f * 4 + ch * 2] = sample.toByte()
            out[f * 4 + ch * 2 + 1] = (sample shr 8).toByte()
        } }
    }
    private fun sample(data: ByteArray, frame: Int, channel: Int): Int {
        val i = frame * 4 + channel * 2
        return ((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toInt()
    }

    @Test fun arbitraryBlocksIncludingSingleFramesGiveIdenticalOutput() {
        val input = pcm(10003) { f, ch -> ((f * 7919 + ch * 3571) and 65535) - 32768 }
        val expected = Pcm441To480().convert(input)
        for (sizes in listOf(listOf(1), listOf(1024), listOf(147), listOf(1, 37, 1024, 3, 441))) {
            val converter = Pcm441To480()
            val actual = ByteArrayOutputStream()
            var offset = 0
            var block = 0
            while (offset < input.size) {
                val end = minOf(input.size, offset + sizes[block++ % sizes.size] * 4)
                actual.write(converter.convert(input.copyOfRange(offset, end)))
                offset = end
            }
            assertArrayEquals(expected, actual.toByteArray())
        }
    }

    @Test fun rampMatchesExactFractionalPositionsForBothChannels() {
        val input = pcm(200) { f, ch -> if (ch == 0) -12000 + f * 100 else 10000 - f * 73 }
        val out = Pcm441To480().convert(input)
        repeat(out.size / 4) { f ->
            // Exact decimal oracle: binary Double turns some half-integers into x.499999...
            val position = (f.toBigDecimal() * 147.toBigDecimal()).divide(160.toBigDecimal())
            fun rounded(value: java.math.BigDecimal) =
                (value + java.math.BigDecimal("0.5")).setScale(0, java.math.RoundingMode.FLOOR).toInt()
            assertEquals(rounded((-12000).toBigDecimal() + position * 100.toBigDecimal()), sample(out, f, 0))
            assertEquals(rounded(10000.toBigDecimal() - position * 73.toBigDecimal()), sample(out, f, 1))
        }
    }

    @Test fun longStreamRetainsEveryFractionalRemainder() {
        val converter = Pcm441To480()
        val input = pcm(1024) { _, ch -> if (ch == 0) -32768 else 32767 }
        var total = 0L
        repeat(10000) { block ->
            val out = converter.convert(input)
            total += out.size / 4
            val inputFrames = (block + 1L) * 1024
            assertEquals((inputFrames - 1) * 160 / 147 + 1, total)
            assertEquals(-32768, sample(out, 0, 0))
            assertEquals(32767, sample(out, out.size / 4 - 1, 1))
        }
    }

    @Test fun pendingInterpolationWaitsForRealNextSampleAndResetDiscardsHistory() {
        val converter = Pcm441To480()
        assertArrayEquals(pcm(1) { _, _ -> -1600 }, converter.convert(pcm(1) { _, _ -> -1600 }))
        assertEquals(0, converter.convert(byteArrayOf()).size)
        val next = converter.convert(pcm(1) { _, _ -> 1600 })
        assertEquals(1, next.size / 4)
        assertEquals(1340, sample(next, 0, 0))
        converter.reset()
        val fresh = pcm(4) { f, _ -> 100 + f }
        assertArrayEquals(Pcm441To480().convert(fresh), converter.convert(fresh))
    }

    @Test fun senderUsesConverterOnlyFor44100PcmAndPreserves48000Bytes() {
        val sender = AudioSender("127.0.0.1", 5000, ByteArray(32), {})
        val align = AudioSender::class.java.getDeclaredMethod("alignPcm", ByteArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val input = pcm(1024) { f, ch -> f * (ch + 1) }
        assertSame(input, align.invoke(sender, input, input.size, 48000, 2))
        val reference = Pcm441To480()
        repeat(3) {
            assertArrayEquals(reference.convert(input), align.invoke(sender, input, input.size, 44100, 2) as ByteArray)
        }
    }
}

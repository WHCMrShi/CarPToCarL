package io.github.shaonialife.carptocar.audio

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class StreamingPcmResamplerTest {
    @Test fun fragmentedStereoHasIdenticalSamplesAndExactOneSecondDuration() {
        val input = ByteArray(48_000 * 4).also { Random(17).nextBytes(it) }
        val expected = StreamingPcmResampler(44_100).convert(input, input.size, 48_000, 2)
        val resampler = StreamingPcmResampler(44_100)
        val out = ByteArrayOutputStream()
        val random = Random(32)
        var at = 0
        while (at < input.size) {
            val size = minOf((1 + random.nextInt(1024)) * 4, input.size - at)
            val part = input.copyOfRange(at, at + size)
            out.write(resampler.convert(part, part.size, 48_000, 2))
            at += size
        }
        assertEquals(44_100 * 4, out.size())
        assertArrayEquals(expected, out.toByteArray())
    }
    @Test fun tenMinutesOf1024SampleBlocksNeverAccumulatesRoundingLoss() {
        val resampler = StreamingPcmResampler(44_100)
        val input = ByteArray(4096)
        val blocks = 48_000 * 600 / 1024
        var outputSamples = 0L
        repeat(blocks) { outputSamples += resampler.convert(input, input.size, 48_000, 2).size / 4 }
        assertEquals(blocks * 1024L * 44_100 / 48_000, outputSamples)
    }
    @Test fun resetAndFormatChangeDiscardOldPhaseAndSampleHistory() {
        val input = ByteArray(100).also { Random(7).nextBytes(it) }
        val resampler = StreamingPcmResampler(44_100)
        resampler.convert(input, input.size, 48_000, 2)
        resampler.reset()
        assertArrayEquals(StreamingPcmResampler(44_100).convert(input, input.size, 48_000, 2),
            resampler.convert(input, input.size, 48_000, 2))
        val mono = resampler.convert(input, input.size, 44_100, 1)
        for (at in mono.indices step 4) {
            assertEquals(mono[at], mono[at + 2]); assertEquals(mono[at + 1], mono[at + 3])
        }
        assertEquals(input.size * 2, mono.size)
    }
}

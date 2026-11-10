package io.github.shaonialife.carptocar.audio

import org.junit.Assert.*
import org.junit.Test

class AacPassthroughProbeTest {
    @Test fun silenceDoesNotConsumeProbeAndNonzeroInputCollectsOneOwnedBoundedWindow() {
        val probe = AacPassthroughProbe()
        repeat(100) { assertNull(probe.observe(ByteArray(4), 0, 4, listOf(byteArrayOf(9)))) }
        val encoded = byteArrayOf(7)
        assertNull(probe.observe(byteArrayOf(0, 1, 0, 0), 0, 4, listOf(encoded)))
        encoded[0] = 0
        repeat(14) { assertNull(probe.observe(ByteArray(4), 0, 4, listOf(byteArrayOf(8)))) }
        val result = probe.observe(ByteArray(4), 0, 4, listOf(byteArrayOf(8)))!!
        assertEquals(16, result.size); assertEquals(7.toByte(), result.first()[0])
        assertNull(probe.observe(byteArrayOf(1), 0, 1, listOf(byteArrayOf(8))))
    }
    @Test fun selectedSliceAndOversizedOutputAreBounded() {
        val probe = AacPassthroughProbe()
        assertNull(probe.observe(byteArrayOf(1, 0, 0), 1, 2, listOf(byteArrayOf(8))))
        assertTrue(probe.observe(byteArrayOf(1), 0, 1, listOf(ByteArray(8193)))!!.isEmpty())
        assertNull(probe.observe(byteArrayOf(1), 0, 1, listOf(byteArrayOf(8))))
    }
    @Test fun rawDecoderConfigMatchesLcStereoRates() {
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AacLcEncoder.audioSpecificConfig(48000, 2))
        assertArrayEquals(byteArrayOf(0x12, 0x10), AacLcEncoder.audioSpecificConfig(44100, 2))
    }
}

package io.github.shaonialife.carptocar.audio

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AacOutputPipelineTest {
    private class Encoder(private val startOk: Boolean = true, private val beforeEncode: () -> Unit = {}) : AacEncoderPort {
        override var isRunning = false
        var starts = 0
        var stops = 0
        val input = ByteArrayOutputStream()
        override fun start(): Boolean { starts++; isRunning = startOk; return startOk }
        override fun encode(pcm: ByteArray, size: Int, offset: Int): List<ByteArray> {
            if (!isRunning) return emptyList()
            beforeEncode()
            input.write(pcm, offset, size)
            return listOf(byteArrayOf(7))
        }
        override fun stop() { stops++; isRunning = false }
    }

    @Test fun encodeTimingSeparatesCpuFromWallDelayWithoutPerFrameLogs() {
        var wall = 1L
        var cpu = 0L
        val lines = mutableListOf<String>()
        val encoder = Encoder(beforeEncode = { wall += 1_000_000_000L; cpu += 20_000_000L })
        val pipeline = AacOutputPipeline(AacOutputSettings(48_000), { lines += it }, { true }, {},
            now = { wall }, cpuNow = { cpu }, createEncoder = { encoder })
        pipeline.start()
        repeat(5) { pipeline.feed(ByteArray(4096), 4096, 48_000, 2, false) }
        val reports = lines.filter { it.contains("AAC-ENCODE") }
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("wallMaxUs=1000000"))
        assertTrue(reports.single().contains("cpuMaxUs=20000"))
        pipeline.stop()
    }

    @Test fun streamOwnsFragmentsAndStopPreventsFurtherEncoding() {
        val encoder = Encoder()
        var sent = 0
        val pipeline = AacOutputPipeline(AacOutputSettings(48_000), {}, { sent++; true }, {}, cpuNow = { 0L }, createEncoder = { encoder })
        assertEquals(0, encoder.starts)
        assertTrue(pipeline.start())
        val expected = ByteArray(4096) { (it % 251).toByte() }
        val prefix = expected.copyOfRange(0, 28)
        pipeline.feed(prefix, prefix.size, 48_000, 2, false)
        prefix.fill(0)
        val rest = expected.copyOfRange(28, expected.size)
        pipeline.feed(rest, rest.size, 48_000, 2, false)
        assertArrayEquals(expected, encoder.input.toByteArray())
        assertEquals(1, sent)
        pipeline.stop()
        pipeline.feed(expected, expected.size, 48_000, 2, false)
        assertEquals(1, sent); assertEquals(1, encoder.stops)
    }
    @Test fun realLongDiscontinuityTriggersRecoveryButPauseAndSilenceDoNot() {
        var clock = 1L
        var recoveries = 0
        val pipeline = AacOutputPipeline(AacOutputSettings(48_000), {}, { true }, { recoveries++ },
            now = { clock }, cpuNow = { 0L }, createEncoder = { Encoder() })
        pipeline.start()
        val music = ByteArray(4096) { 1 }
        pipeline.feed(music, music.size, 48_000, 2, false)
        clock += 900_000_000
        pipeline.feed(music, music.size, 48_000, 2, false)
        clock += 900_000_000
        pipeline.feed(ByteArray(4096), 4096, 48_000, 2, true)
        assertEquals(0, recoveries)
        clock += 900_000_000
        pipeline.feed(music, music.size, 48_000, 2, true)
        assertEquals(1, recoveries)
        pipeline.stop()
    }
    @Test fun probeRemainsBoundedAndUsesSelectedRate() {
        var probes = 0
        val pipeline = AacOutputPipeline(AacOutputSettings(44_100), {}, { true }, {},
            probeWork = { it() }, decodeWindow = { window, rate ->
                assertEquals(16, window.size); assertEquals(44_100, rate); probes++; 10 to 100
            }, cpuNow = { 0L }, createEncoder = { config -> assertEquals(44_100, config.sampleRate); Encoder() })
        pipeline.start()
        repeat(40) { pipeline.feed(ByteArray(4096) { 1 }, 4096, 44_100, 2, false) }
        assertEquals(1, probes)
        pipeline.stop()
    }
    @Test fun failedStartReleasesCodecAndFailedSendStopsCurrentBatch() {
        val failed = Encoder(false)
        val unopened = AacOutputPipeline(AacOutputSettings(48_000), {}, { fail("not open"); false }, {}, cpuNow = { 0L }, createEncoder = { failed })
        assertFalse(unopened.start()); assertEquals(1, failed.stops)
        val encoder = Encoder()
        var packets = 0
        val pipeline = AacOutputPipeline(AacOutputSettings(48_000), {}, { packets++; false }, {}, cpuNow = { 0L }, createEncoder = { encoder })
        pipeline.start()
        pipeline.feed(ByteArray(4096 * 3), 4096 * 3, 48_000, 2, false)
        assertEquals(1, packets)
        assertEquals(4096, encoder.input.size())
        pipeline.stop()
    }
}

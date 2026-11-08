package io.github.shaonialife.carptocar.audio

import org.junit.Assert.*
import org.junit.Test

class AudioContinuityWindowTest {
    @Test fun capturesInputAndNetworkStallsWithoutLoggingEachPacket() {
        val window = AudioContinuityWindow()
        window.input(0)
        assertNull(window.output(1_000_000, 10_000))
        window.input(617_000_000)
        assertNull(window.output(620_000_000, 300_000_000))
        window.silence()
        val text = window.output(30_000_000_000, 1000)!!
        assertTrue(text.contains("inputGapMaxMs=617"))
        assertTrue(text.contains("sendMaxUs=300000"))
        assertTrue(text.contains("silenceSubmissions=1"))
        assertTrue(text.contains("udpPackets=3"))
        assertNull(window.summary(30_001_000_000))
    }

    @Test fun finalSummaryIncludesShortTestAndDoesNotRepeatOldMaxima() {
        val window = AudioContinuityWindow()
        window.input(0)
        window.output(100, 50)
        assertNotNull(window.summary(200))
        assertNull(window.summary(201))
        window.output(300, 1)
        val next = window.summary(400)!!
        assertTrue(next.contains("realFeeds=0"))
        assertTrue(next.contains("udpPackets=1"))
    }
}

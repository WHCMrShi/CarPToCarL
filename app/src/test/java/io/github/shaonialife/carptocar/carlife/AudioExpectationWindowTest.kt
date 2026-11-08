package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class AudioExpectationWindowTest {
    @Test fun longDesktopSilenceDoesNotMakeFirstPlaybackImmediatelyEligible() {
        val window = AudioExpectationWindow()
        window.observe(0, false)
        window.observe(58000, true)
        assertFalse(window.ready(58522, 20000))
        assertFalse(window.ready(77999, 20000))
        assertTrue(window.ready(78000, 20000))
    }

    @Test fun newFormatAndResumeEachGetTheirOwnGrace() {
        val window = AudioExpectationWindow()
        window.observe(0, true)
        assertTrue(window.ready(20000, 20000))
        window.format(20000)
        assertFalse(window.ready(20500, 20000))
        assertTrue(window.ready(40000, 20000))
        window.observe(40000, false)
        assertFalse(window.ready(90000, 20000))
        window.observe(90000, true)
        assertFalse(window.ready(90500, 20000))
        assertTrue(window.ready(110000, 20000))
    }

    @Test fun repeatedExpectedSamplesDoNotPostponeGenuineSilentStreamForever() {
        val window = AudioExpectationWindow()
        repeat(41) { window.observe(it * 500L, true) }
        assertTrue(window.ready(20000, 20000))
    }
}

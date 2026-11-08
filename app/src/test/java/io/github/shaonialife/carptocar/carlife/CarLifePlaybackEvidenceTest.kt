package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifePlaybackEvidenceTest {
    private fun snapshot(playing: Boolean?, changed: Long, matches: Boolean = true, observed: Long = changed) =
        CarLifePlaybackSnapshot(playing, observed, changed, matches, "test-player")

    @Test fun unrelatedPauseDoesNotOverrideEngineStart() {
        val evidence = CarLifePlaybackEvidence()
        evidence.engine(1000, true)
        assertTrue(evidence.expected(2000, snapshot(false, 1900, matches = false)))
        assertTrue(evidence.expected(2000, snapshot(false, 500, observed = 2000)))
    }
    @Test fun freshMatchingPauseCanOverrideOlderStartAndResumeWinsAgain() {
        val evidence = CarLifePlaybackEvidence()
        evidence.engine(1000, true)
        assertFalse(evidence.expected(2000, snapshot(false, 1900)))
        evidence.engine(2100, true)
        assertTrue(evidence.expected(2200, snapshot(false, 1900)))
        evidence.engine(2300, false)
        assertFalse(evidence.expected(2300, snapshot(true, 1000, observed = 2300)))
    }
    @Test fun advancingProgressWorksWithoutStartAndExpires() {
        val evidence = CarLifePlaybackEvidence()
        evidence.progress(0, 0); evidence.progress(1000, 0)
        assertFalse(evidence.expected(1000, null))
        evidence.progress(2000, 1000)
        assertTrue(evidence.expected(2000, snapshot(false, 500)))
        assertFalse(evidence.expected(7001, null))
        evidence.progress(8000, 0)
        assertFalse(evidence.expected(8000, null))
    }
    @Test fun unknownUnmatchedAndStaleSystemStatesCannotTriggerRecovery() {
        val evidence = CarLifePlaybackEvidence()
        assertFalse(evidence.expected(6000, snapshot(true, 0)))
        assertFalse(evidence.expected(6000, snapshot(null, 6000)))
        assertFalse(evidence.expected(6000, snapshot(true, 6000, matches = false)))
        assertTrue(evidence.expected(6000, snapshot(true, 6000)))
    }
    @Test fun freshPlaybackStillGetsItsOwnGrace() {
        val evidence = CarLifePlaybackEvidence(); val window = AudioExpectationWindow()
        evidence.engine(58000, true)
        window.observe(58000, evidence.expected(58000, null))
        assertFalse(window.ready(58522, 20000)); assertTrue(window.ready(78000, 20000))
        window.format(78000)
        assertFalse(window.ready(97999, 20000)); assertTrue(window.ready(98000, 20000))
    }
}
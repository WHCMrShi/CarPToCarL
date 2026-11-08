package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifePlaybackEvidenceTest {
    @Test fun missingNotificationListenerCanUseSystemMusicButPauseWins() {
        assertEquals(true, CarLifePlaybackEvidence.external(null, true))
        assertNull(CarLifePlaybackEvidence.external(null, false))
        assertEquals(false, CarLifePlaybackEvidence.external(false, true))
    }

    @Test fun onlyProgressAdvancementCreatesTemporaryPlaybackEvidence() {
        val evidence = CarLifePlaybackEvidence()
        evidence.progress(0, 0)
        evidence.progress(1000, 0)
        assertFalse(evidence.expected(1000, null, false))
        evidence.progress(2000, 1000)
        assertTrue(evidence.expected(2000, null, false))
        assertFalse(evidence.expected(2000, false, true))
        assertFalse(evidence.expected(7001, null, false))
        evidence.progress(8000, 0)
        assertFalse(evidence.expected(8000, null, false))
    }

    @Test fun freshGraceStillProtectsFirstPlaybackThenAllowsRecovery() {
        val evidence = CarLifePlaybackEvidence()
        val window = AudioExpectationWindow()
        window.observe(58000, evidence.expected(58000, true, false))
        assertFalse(window.ready(58522, 20000))
        assertTrue(window.ready(78000, 20000))
        window.format(78000)
        assertFalse(window.ready(98000 - 1, 20000))
        assertTrue(window.ready(98000, 20000))
    }
}

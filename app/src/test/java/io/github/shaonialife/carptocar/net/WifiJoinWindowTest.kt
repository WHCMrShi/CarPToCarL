package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiJoinWindowTest {
    private val window = WifiJoinWindow(1_000L, 15_000L, 20_000L)

    @Test fun retainsSameRequestAcrossTimeoutUntilGraceEnds() {
        assertTrue(window.waiting(15_999, false, false))
        assertFalse(window.inGrace(15_999))
        assertTrue(window.inGrace(16_000))
        assertTrue(window.waiting(16_000, false, false))
        assertTrue(window.waiting(35_999, false, false))
        assertFalse(window.waiting(36_000, false, false))
    }

    @Test fun systemRejectionEndsWaitImmediatelyIncludingGrace() {
        assertFalse(window.waiting(2_000, true, false))
        assertFalse(window.waiting(20_000, true, false))
    }

    @Test fun cancellationEndsWaitImmediatelyIncludingGrace() {
        assertFalse(window.waiting(2_000, false, true))
        assertFalse(window.waiting(20_000, false, true))
    }

    @Test fun zeroGraceKeepsOriginalDeadline() {
        val immediate = WifiJoinWindow(1_000, 15_000, 0)
        assertTrue(immediate.waiting(15_999, false, false))
        assertFalse(immediate.waiting(16_000, false, false))
        assertFalse(immediate.inGrace(16_000))
    }
}

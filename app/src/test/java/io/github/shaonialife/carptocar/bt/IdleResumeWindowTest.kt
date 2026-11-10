package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class IdleResumeWindowTest {
    @Test fun longGapGetsOnlyOneBoundedWindow() {
        val window = IdleResumeWindow()
        assertFalse(window.defer(0, true))
        assertTrue(window.defer(360000, true))
        assertTrue(window.defer(361999, true))
        assertFalse(window.defer(362000, true))
        assertFalse(window.defer(365000, true))
    }
    @Test fun cancellationAndNewStartupDiscardOldGrace() {
        val window = IdleResumeWindow()
        window.defer(0, true)
        assertTrue(window.defer(10000, true))
        assertFalse(window.defer(10001, false))
        assertFalse(window.defer(10002, true))
        window.reset()
        assertFalse(window.defer(100000, true))
    }
    @Test fun graceDoesNotChangeStartupOrDepartureDeadline() {
        val budget = IdleBudget(); val window = IdleResumeWindow()
        budget.start(0); window.defer(0, true)
        assertTrue(window.defer(200000, true))
        assertEquals(180000L, budget.deadline)
        assertTrue(budget.expired(202000, false))
        budget.disconnected(202000)
        assertEquals(180000L, budget.deadline)
    }
}

package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class IdleBudgetTest {
    @Test fun expiredStartupDoesNotConsumeRecoveryAfterSuccessfulStreaming() {
        val budget = IdleBudget()
        budget.start(0)
        assertFalse(budget.expired(200_000, true))
        assertFalse(budget.expired(300_000, false))
        assertEquals(330_000L, budget.deadline)
        assertFalse(budget.expired(329_999, false))
        assertTrue(budget.expired(330_000, false))
    }
    @Test fun repeatedDisconnectDoesNotExtendRecoveryAndReturnCancelsIt() {
        val budget = IdleBudget()
        budget.start(0)
        budget.disconnected(1_000)
        budget.disconnected(5_000)
        assertEquals(31_000L, budget.deadline)
        assertFalse(budget.expired(30_000, true))
        assertEquals(0L, budget.deadline)
        assertFalse(budget.expired(50_000, false))
        assertEquals(80_000L, budget.deadline)
    }
    @Test fun recreatedServiceWithoutAnyDeadlineStillHasAFiniteBudget() {
        val budget = IdleBudget()
        assertFalse(budget.expired(1_000, false))
        assertTrue(budget.expired(181_000, false))
        assertTrue(IdleBudget(30_000).expired(35_000, false))
    }
}

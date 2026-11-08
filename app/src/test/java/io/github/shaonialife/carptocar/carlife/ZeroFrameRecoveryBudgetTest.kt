package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class ZeroFrameRecoveryBudgetTest {
    @Test fun onlyColdZeroFrameSessionsWithProjectionCanEscalate() {
        val budget = ZeroFrameRecoveryBudget()
        assertFalse(budget.acquire(90_000, false, 5, false))
        assertFalse(budget.acquire(29_999, false, 2, true))
        assertFalse(budget.acquire(90_000, false, 1, true))
        assertFalse(budget.acquire(90_000, true, 5, true))
        assertTrue(budget.acquire(30_000, false, 2, true))
    }

    @Test fun budgetSurvivesHuRecreationAndResetsOnlyAfterRecoveryOrExplicitStart() {
        val budget = ZeroFrameRecoveryBudget()
        assertTrue(budget.acquire(30_000, false, 2, true))
        assertTrue(budget.acquire(30_000, false, 2, true))
        repeat(100) { assertFalse(budget.acquire(600_000, false, 20, true)) }
        budget.reset()
        assertTrue(budget.acquire(30_000, false, 2, true))
    }
}

package io.github.shaonialife.carptocar.audio

import org.junit.Assert.*
import org.junit.Test

class AacRecoveryBudgetTest {
    @Test fun rebuildingSenderDoesNotReplenishAutomaticBudgetOrSkipCooldown() {
        val gate = AacRecoveryBudget()
        val owner = Any()
        gate.reserve(owner, 0, false)!!.finish()
        assertNull(gate.reserve(owner, 29_999, false))
        gate.reserve(owner, 30_000, false)!!.finish()
        assertNull(gate.reserve(owner, 60_000, false))
        assertNotNull(gate.reserve(owner, 60_000, true))
    }
    @Test fun oldCompletionCannotReleaseNewSessionRecoveryAndBusyIsExclusive() {
        val gate = AacRecoveryBudget()
        val oldOwner = Any()
        val old = gate.reserve(oldOwner, 0, true)!!
        assertNull(gate.reserve(oldOwner, 5_000, true))
        val newOwner = Any()
        val current = gate.reserve(newOwner, 5_000, true)!!
        old.finish()
        assertFalse(old.current); assertTrue(current.current)
        assertNull(gate.reserve(newOwner, 10_000, false))
        current.finish()
        assertNull(gate.reserve(newOwner, 6_999, true))
        assertNotNull(gate.reserve(newOwner, 7_000, true))
    }
}

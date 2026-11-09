package io.github.shaonialife.carptocar.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Q4StallPolicyTest {
    @Test fun recoveryDefaultsOffEvenForLongStall() {
        assertFalse(StallRecoveryPrefs.DEFAULT_RECOVERY_ENABLED)
        assertFalse(StallJudge.q4ShouldReinit(600_000, false, false, Long.MAX_VALUE))
    }

    @Test fun noFirstFrameAndCallHeartbeatsNeverTriggerRecovery() {
        assertFalse(StallJudge.q4ShouldReinit(StallJudge.frameAgeMs(0, 50_000), true, false, Long.MAX_VALUE))
        assertFalse(StallJudge.q4ShouldReinit(StallJudge.frameAgeMs(49_000, 50_000), true, false, Long.MAX_VALUE))
        assertFalse(StallJudge.q4ShouldReinit(7_999, true, false, Long.MAX_VALUE))
    }

    @Test fun activeCallAndRetryCooldownSuppressIntervention() {
        assertFalse(StallJudge.q4ShouldReinit(60_000, true, true, Long.MAX_VALUE))
        assertFalse(StallJudge.q4ShouldReinit(60_000, true, false, 7_999))
        assertTrue(StallJudge.q4ShouldReinit(8_000, true, false, 8_000))
        assertTrue(StallJudge.q4ShouldReinit(600_000, true, false, Long.MAX_VALUE))
    }
}

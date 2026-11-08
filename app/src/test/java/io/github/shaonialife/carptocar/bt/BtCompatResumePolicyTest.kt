package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class BtCompatResumePolicyTest {
    @Test fun explicitResumeReinitializesAllTerminalStatesButRestorationDoesNot() {
        for (suppressed in listOf(false, true)) for (cancelled in listOf(false, true)) {
            assertFalse(BtCompatResumePolicy.needsNewConnection(false, suppressed, cancelled))
            assertEquals(suppressed || cancelled, BtCompatResumePolicy.needsNewConnection(true, suppressed, cancelled))
        }
    }
}

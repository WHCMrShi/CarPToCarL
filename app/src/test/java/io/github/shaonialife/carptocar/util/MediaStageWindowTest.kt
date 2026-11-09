package io.github.shaonialife.carptocar.util

import org.junit.Assert.*
import org.junit.Test

class MediaStageWindowTest {
    @Test fun normalTrafficReportsOnlyEveryThirtySeconds() {
        val window = MediaStageWindow("test", 50_000_000)
        assertNull(window.record(1, 10, 20))
        assertNull(window.record(29_000_000_001, 10, 20))
        val report = window.record(30_000_000_001, 10, 20)!!
        assertTrue(report.contains("count=3 slow=0 discontinuities=0"))
        assertNull(window.finish(31_000_000_001))
    }

    @Test fun sustainedDiscontinuitiesAreCountedWithoutFloodingAndResetPerWindow() {
        val window = MediaStageWindow("test", 50_000_000)
        repeat(500) { assertNull(window.record(1L + it * 1_000_000, 0, 100_000_000, gap = true)) }
        val report = window.record(5_000_000_001, 1_000_000, 100_000_000, 2_000_000, 3_000_000, true)!!
        assertTrue(report.contains("count=501 slow=501 discontinuities=501"))
        assertTrue(report.contains("lockWaitMaxUs=1000 workInclusiveMaxUs=100000 sealMaxUs=2000 sendMaxUs=3000"))
        assertNull(window.record(6_000_000_001, 0, 1))
        assertTrue(window.finish(7_000_000_001)!!.contains("count=1 slow=0 discontinuities=0"))
    }

    @Test fun lockWaitAloneCanTriggerASlowSummary() {
        val window = MediaStageWindow("test", 50_000_000)
        assertNull(window.record(1, 60_000_000, 1))
        assertNotNull(window.record(5_000_000_001, 0, 1))
    }
}

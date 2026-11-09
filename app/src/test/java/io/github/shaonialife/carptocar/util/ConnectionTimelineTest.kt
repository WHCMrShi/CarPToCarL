package io.github.shaonialife.carptocar.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ConnectionTimelineTest {
    private val now = AtomicLong(100)
    private val logs = mutableListOf<String>()
    private val timeline = ConnectionTimeline(now::get, logs::add)
    private val stage = ConnectionTimeline.Stage.PAIRING

    @Test fun duplicateBeginPreservesOriginAndAttemptCount() {
        timeline.begin()
        now.set(150)
        timeline.measure(stage) { true }
        timeline.begin()
        now.set(200)
        timeline.measure(stage) { false }
        assertEquals(1, logs.count { it.endsWith("event=BEGIN") })
        assertTrue(logs.last().contains("attempt=2"))
        assertTrue(logs.last().contains("elapsedMs=100"))
        assertTrue(logs.last().endsWith("outcome=false"))
    }

    @Test fun originalReturnAndExceptionArePreserved() {
        val result = Any()
        assertSame(result, timeline.measure(stage) { result })
        val failure = InterruptedException("cancel")
        try { timeline.measure(stage) { throw failure }; fail() }
        catch (caught: InterruptedException) { assertSame(failure, caught) }
        assertTrue(logs.last().endsWith("outcome=THREW"))
    }

    @Test fun retiredStageCannotFinishInNextRound() {
        timeline.begin()
        timeline.measure(stage) {
            timeline.end()
            timeline.begin()
        }
        assertFalse(logs.any { it.contains("stage=PAIRING") && it.contains("event=END") })
        timeline.measure(stage) { null }
        assertTrue(logs.last().contains("attempt=1"))
        assertTrue(logs.last().endsWith("outcome=NULL"))
    }

    @Test fun overlappingStagesKeepIndividualDurationsAndSharedElapsedTime() {
        timeline.begin()
        timeline.measure(stage) {
            now.set(110)
            timeline.measure(ConnectionTimeline.Stage.WIFI_REUSE) { now.set(130) }
            now.set(150)
        }
        assertTrue(logs.any { it.contains("WIFI_REUSE") && it.contains("durationMs=20 elapsedMs=30") })
        assertTrue(logs.last().contains("durationMs=50 elapsedMs=50"))
    }

    @Test fun diagnosticSinkFailureCannotChangeBusinessResultOrException() {
        val broken = ConnectionTimeline(now::get) { throw IllegalStateException("disk") }
        broken.begin()
        assertEquals(42, broken.measure(stage) { 42 })
        val failure = IllegalArgumentException("original")
        try { broken.measure(stage) { throw failure }; fail() }
        catch (caught: IllegalArgumentException) { assertSame(failure, caught) }
        broken.end()
    }

    @Test fun passiveWorkIsExplicitlyUnscoped() {
        timeline.measure(stage) { true }
        assertTrue(logs.last().contains("round=0 active=false"))
    }
}

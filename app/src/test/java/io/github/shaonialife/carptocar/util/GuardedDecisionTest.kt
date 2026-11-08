package io.github.shaonialife.carptocar.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GuardedDecisionTest {
    @Test fun connectionSuccessRetiresQueuedPromptAndRejectsLateRetry() {
        val needed = AtomicBoolean(true)
        val decision = GuardedDecision<Boolean>(needed::get)
        assertTrue(decision.pending)
        needed.set(false)
        assertFalse(decision.pending)
        assertNull(decision.await(100))
        needed.set(true)
        assertFalse(decision.complete(true))
        assertNull(decision.await(100))
    }

    @Test fun cancellationWhileAwaitingReleasesWorker() {
        val needed = AtomicBoolean(true)
        val decision = GuardedDecision<Boolean>(needed::get)
        val finished = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { assertNull(decision.await(10_000)); finished.countDown() }.apply { start() }
        try {
            needed.set(false)
            assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { needed.set(false); worker.join(2_000) }
    }

    @Test fun firstAnswerWinsAndTimeoutCannotBeRevived() {
        val decision = GuardedDecision<Boolean> { true }
        assertTrue(decision.complete(false))
        assertFalse(decision.complete(true))
        assertEquals(false, decision.await(100))
        val timedOut = GuardedDecision<Boolean> { true }
        assertNull(timedOut.await(0))
        assertFalse(timedOut.complete(true))
    }
}

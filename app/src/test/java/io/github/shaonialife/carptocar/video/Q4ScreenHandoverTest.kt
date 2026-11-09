package io.github.shaonialife.carptocar.video

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Q4ScreenHandoverTest {
    private fun ticket(policy: Q4ScreenHandover): Q4ScreenHandover.Ticket {
        policy.arm("late-source")
        return policy.written(policy.sourceGeneration(), true, true)!!
    }
    @Test fun cachedGopAndFollowingLiveFramesDoNotArmRecovery() {
        val policy = Q4ScreenHandover()
        repeat(30) { assertNull(policy.written(policy.sourceGeneration(), true, true)) }
    }
    @Test fun waitingRequiresBothRealIdrAndWrittenConfiguration() {
        val policy = Q4ScreenHandover()
        policy.arm("waiting-for-source")
        assertNull(policy.written(policy.sourceGeneration(), false, true))
        assertNull(policy.written(policy.sourceGeneration(), true, false))
        val event = policy.written(policy.sourceGeneration(), true, true)!!
        assertEquals("waiting-for-source", event.reason)
        assertNull(policy.written(policy.sourceGeneration(), true, true))
    }
    @Test fun modesThenRecordExecuteOncePerSourceGeneration() {
        val policy = Q4ScreenHandover()
        val event = ticket(policy)
        val calls = mutableListOf<String>()
        assertEquals(Q4ScreenHandover.Result.ACTIVATED, policy.recover(event, { true },
            { calls += "modes"; true }, { calls += "record"; true }))
        assertEquals(listOf("modes", "record"), calls)
        assertEquals(Q4ScreenHandover.Result.STALE, policy.recover(event, { true }, { error("duplicate") }, { error("duplicate") }))
    }
    @Test fun stoppedReplacedAndForeignSourceTicketsCannotActivate() {
        val policy = Q4ScreenHandover()
        val stopped = ticket(policy)
        policy.retire()
        assertEquals(Q4ScreenHandover.Result.STALE, policy.recover(stopped, { true }, { error("retired") }, { error("retired") }))
        val replaced = ticket(policy)
        val current = ticket(policy)
        assertEquals(Q4ScreenHandover.Result.STALE, policy.recover(replaced, { true }, { error("old source") }, { error("old source") }))
        val other = Q4ScreenHandover()
        val sameGenerationOwner = Q4ScreenHandover()
        ticket(sameGenerationOwner)
        val foreign = ticket(other)
        assertEquals(Q4ScreenHandover.Result.STALE, sameGenerationOwner.recover(foreign, { true }, { error("wrong owner") }, { error("wrong owner") }))
        assertEquals(Q4ScreenHandover.Result.ACTIVATED, policy.recover(current, { true }, { true }, { true }))
    }
    @Test fun endedControlOrChangedOwnerSkipsNetworkWork() {
        val policy = Q4ScreenHandover()
        assertEquals(Q4ScreenHandover.Result.STALE, policy.recover(ticket(policy), { false },
            { error("closed owner") }, { error("closed owner") }))
    }
    @Test fun sourceReplacementDuringModesPreventsRecordWithoutBlockingCancellation() {
        val policy = Q4ScreenHandover()
        val event = ticket(policy)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<Q4ScreenHandover.Result>()
        val worker = Thread {
            result.set(policy.recover(event, { true }, {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); true
            }, { error("old source must not RECORD") }))
        }.apply { start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val cancelled = CountDownLatch(1)
            val cancel = Thread { policy.arm("new-source"); cancelled.countDown() }.apply { start() }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            release.countDown(); worker.join(3000); cancel.join(3000)
            assertEquals(Q4ScreenHandover.Result.STALE, result.get())
        } finally { release.countDown(); worker.join(3000) }
    }
    @Test fun modesFailureDoesNotRecordAndDoesNotRetryOnEveryFrame() {
        val policy = Q4ScreenHandover()
        val event = ticket(policy)
        assertEquals(Q4ScreenHandover.Result.MODES_FAILED, policy.recover(event, { true }, { false }, { error("modes failed") }))
        assertNull(policy.written(policy.sourceGeneration(), true, true))
        assertEquals(Q4ScreenHandover.Result.STALE, policy.recover(event, { true }, { error("retry") }, { error("retry") }))
    }
    @Test fun recordFailureIsReportedAndNewSourceCanTryAgain() {
        val policy = Q4ScreenHandover()
        assertEquals(Q4ScreenHandover.Result.RECORD_FAILED, policy.recover(ticket(policy), { true }, { true }, { false }))
        assertEquals(Q4ScreenHandover.Result.ACTIVATED, policy.recover(ticket(policy), { true }, { true }, { true }))
    }
    @Test fun obsoleteWrittenFrameCannotClaimOrConsumeNewGenerationRecovery() {
        val policy = Q4ScreenHandover()
        policy.arm("old-source")
        val oldGeneration = policy.sourceGeneration()
        val oldTicket = policy.written(oldGeneration, true, true)!!
        policy.retire()
        assertEquals(Q4ScreenHandover.Result.STALE, policy.recover(oldTicket, { true },
            { error("retired ticket") }, { error("retired ticket") }))
        policy.arm("new-source")
        assertNull(policy.written(oldGeneration, true, true))
        assertNotNull(policy.written(policy.sourceGeneration(), true, true))
    }

}

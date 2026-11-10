package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.util.WorkEpoch
import org.junit.Assert.*
import org.junit.Test

/** The actual request gate and work tickets used by remembered/fresh paths, with controlled completion order. */
class WifiMemoryRecoveryTest {
    @Test fun manualResetWakesOldWaiterAndOldCompletionCannotClearNewRequest() {
        val gate = WifiJoinGate()
        val cached = gate.decide("q4", 0) as WifiJoinGate.Decision.Run
        val waiter = gate.decide("q4", 1) as WifiJoinGate.Decision.Wait
        val epoch = WorkEpoch(); val stale = epoch.capture()
        epoch.end(); gate.reset()
        assertEquals(false, waiter.ticket.await(1))
        val fresh = gate.decide("q4", 2) as WifiJoinGate.Decision.Run
        assertFalse(stale.runIfCurrent { error("old credentials published") })
        gate.finish(cached.ticket, true, 3)
        assertEquals("q4", gate.runningSsid())
        gate.finish(fresh.ticket, true, 4)
        assertEquals(true, fresh.ticket.await(1))
    }
    @Test fun resetAndRestartRejectOldSaveAndJoinCompletions() {
        val epoch = WorkEpoch(); val old = epoch.capture()
        epoch.end(); val new = epoch.replace()
        assertFalse(old.runIfCurrent { error("clear undone by old completion") })
        assertTrue(new.isCurrent)
    }
    @Test fun unverifiedInviteIsPendingButCannotAuthorizeCachedOrPreTcpContinuation() {
        val invites = PendingCtrlInvite({ 0L })
        invites.begin(); val epoch = invites.currentGeneration()!!
        assertTrue(invites.record(epoch, verifiedTarget = false))
        assertNotNull(invites.peekAge(epoch))
        assertNull(invites.recentAge(epoch))
        assertNotNull(invites.consume(epoch))
        assertNull(invites.recentAge(epoch))
    }
    @Test fun freshAndCachedSameNetworkShareOneRequest() {
        val gate = WifiJoinGate()
        val cached = gate.decide("q4", 0) as WifiJoinGate.Decision.Run
        val fresh = gate.decide("q4", 1) as WifiJoinGate.Decision.Wait
        assertSame(cached.ticket, fresh.ticket)
        gate.finish(cached.ticket, true, 2)
        assertEquals(true, fresh.ticket.await(1))
    }
}

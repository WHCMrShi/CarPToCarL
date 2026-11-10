package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class PreTcpHandoverTest {
    @Test fun repeatedClosesDoNotExtendWindowAndExpiryIsTerminal() {
        var now = 0L
        val window = PreTcpHandover({ now })
        assertNull(window.retain(false))
        assertEquals(PreTcpHandover.Claim(5000, true), window.retain(true))
        now = 4999
        assertEquals(PreTcpHandover.Claim(1, false), window.retain(true))
        now = 5000
        assertNull(window.retain(true))
        now = 10000
        assertNull(window.retain(true))
    }
    @Test fun lossOrCancellationCannotUseExistingGrace() {
        val window = PreTcpHandover({ 0L })
        assertNotNull(window.retain(true))
        assertNull(window.retain(false))
    }
    @Test fun consumedInviteRemainsRecentButIsNotConsumableAgain() {
        var now = 10L
        val invite = PendingCtrlInvite({ now })
        invite.begin(); val epoch = invite.currentGeneration()!!
        invite.record(epoch)
        now = 100
        assertEquals(90L, invite.consume(epoch))
        assertNull(invite.consume(epoch))
        assertEquals(90L, invite.recentAge(epoch))
        now = 30011
        assertNull(invite.recentAge(epoch))
    }
    @Test fun oldRoundAndExplicitInvalidationRemoveRecentProof() {
        val invite = PendingCtrlInvite({ 0L })
        invite.begin(); val old = invite.currentGeneration()!!; invite.record(old)
        invite.invalidate(old); assertNull(invite.recentAge(old))
        invite.record(old); invite.end(); invite.begin()
        assertNull(invite.recentAge(old))
        assertNull(invite.recentAge(invite.currentGeneration()!!))
    }
}

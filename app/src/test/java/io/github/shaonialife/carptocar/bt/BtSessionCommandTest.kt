package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.util.WorkEpoch
import org.junit.Assert.*
import org.junit.Test

class BtSessionCommandTest {
    @Test fun onlyReadyCurrentOwnerCanSend() {
        val links = BtLinkOwnership { 0L }
        val owner = links.claim("inbound")!!
        var writes = 0
        val command = BtSessionCommand(owner, { true }) { writes++; true }
        assertFalse(command.run("before ready"))
        owner.markReady()
        assertTrue(command.run("ready"))
        owner.finish()
        assertFalse(command.run("finished"))
        links.claim("outbound")!!.markReady()
        assertFalse(command.run("successor"))
        assertEquals(1, writes)
    }

    @Test fun readableOldBindingCannotBorrowRestartedConnection() {
        val work = WorkEpoch()
        val ticket = work.capture()
        val links = BtLinkOwnership { 0L }
        val owner = links.claim("inbound")!!.also { it.markReady() }
        var writes = 0
        val command = BtSessionCommand(owner, { ticket.isCurrent }) { writes++; true }
        work.end(); work.begin()
        assertFalse(command.run("stale connection"))
        assertEquals(0, writes)
    }

    @Test fun sameUserConnectionCanWakeItsNewBtOwner() {
        val work = WorkEpoch(); val ticket = work.capture()
        val links = BtLinkOwnership { 0L }
        val old = links.claim("inbound")!!.also { it.markReady() }
        val oldCommand = BtSessionCommand(old, { ticket.isCurrent }) { fail("old send"); true }
        old.retire()
        val next = links.claim("outbound")!!.also { it.markReady() }
        var writes = 0
        val nextCommand = BtSessionCommand(next, { ticket.isCurrent }) { writes++; true }
        assertFalse(oldCommand.run("old"))
        assertTrue(nextCommand.run("next"))
        assertEquals(1, writes)
    }
}

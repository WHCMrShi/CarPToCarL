package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class BtLinkOwnershipTest {
    @Test fun stoppedInboundCannotPublishOrCloseItsSuccessor() {
        val links = BtLinkOwnership { 1L }
        val old = links.claim("inbound")!!
        assertTrue(links.inbound())
        old.retire()
        val next = links.claim("outbound")!!
        next.markReady()
        assertFalse(old.runIfCurrent { error("late publish") })
        assertFalse(old.finish())
        assertFalse(old.isCurrent)
        assertTrue(next.isCurrent)
        assertTrue(links.ready())
        assertFalse(links.inbound())
    }
    @Test fun onlyOneDirectionCanOwnSessionAndCompletionReleasesGate() {
        val links = BtLinkOwnership { 8L }
        val owner = links.claim("inbound")!!
        assertNull(links.claim("outbound"))
        assertNull(links.claim("inbound"))
        owner.markReady()
        assertTrue(owner.finish())
        assertFalse(links.ready())
        assertFalse(links.inbound())
        assertTrue(owner.isCurrent)
        val next = links.claim("outbound")!!
        assertFalse(owner.isCurrent)
        assertFalse(owner.finish())
        assertTrue(next.isCurrent)
    }
}

package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.util.WorkEpoch
import org.junit.Assert.*
import org.junit.Test

class BtLinkRecoveryTest {
    private class Fixture {
        var now = 1_000L
        var accepted = false
        var wifiLive = true
        val links = BtLinkOwnership { now }
        val recovery = BtLinkRecovery({ now }, 180_000L)
        fun claim(direction: String = "outbound") = links.claim(direction)!!
        fun expect(owner: BtLinkOwnership.Token) = recovery.expectHandoff(owner) { accepted && wifiLive }
        fun close(owner: BtLinkOwnership.Token, peer: String = "car") {
            assertTrue(owner.finish())
            recovery.noteLost(peer, owner)
        }
    }

    @Test fun acceptedCommandThenCloseSuppressesOnlyThisOwnerInEitherDirection() {
        for (direction in listOf("outbound", "inbound")) {
            val f = Fixture()
            val owner = f.claim(direction)
            f.expect(owner)
            f.accepted = true
            f.close(owner)
            assertTrue(f.recovery.decision(true).expectedHandoff)
            assertNull(f.recovery.target(true))
            assertNull(f.recovery.target(false))
            // A later BT link is a different owner, even when the peer address is unchanged.
            val replacement = f.claim()
            f.close(replacement)
            assertFalse(f.recovery.expectedHandoff())
            assertEquals("car", f.recovery.target(true))
        }
    }

    @Test fun closeBeforeResponseRemainsRecoverableUntilAcceptance() {
        val f = Fixture()
        val owner = f.claim()
        f.expect(owner)
        f.close(owner)
        assertEquals("car", f.recovery.target(true))
        f.accepted = true
        assertNull(f.recovery.target(true))
        assertTrue(f.recovery.expectedHandoff())
    }

    @Test fun noCommandOrUnacceptedCommandKeepsOriginalRecoveryWindow() {
        for (requested in listOf(false, true)) {
            val f = Fixture()
            val owner = f.claim()
            if (requested) f.expect(owner)
            f.close(owner)
            assertEquals("car", f.recovery.target(true))
            f.now += 180_000
            assertEquals("car", f.recovery.target(true))
            f.now++
            assertNull(f.recovery.target(true))
            assertFalse(f.recovery.decision(true).expectedHandoff)
        }
    }

    @Test fun wifiFailureResumesOriginalWindowWithoutRestartingDeadline() {
        val f = Fixture()
        val owner = f.claim()
        f.expect(owner)
        f.accepted = true
        f.close(owner)
        f.now += 100_000
        assertNull(f.recovery.target(true))
        f.wifiLive = false
        assertEquals("car", f.recovery.target(true))
        assertEquals(1_000L, f.recovery.lostAt)
        f.now += 80_001
        assertNull(f.recovery.target(true))
    }

    @Test fun retiredWorkOrSetupCannotSuppressRecovery() {
        for (retireConnection in listOf(false, true)) {
            val f = Fixture()
            val work = WorkEpoch()
            val setup = WorkEpoch()
            val connectionTicket = work.capture()
            val setupTicket = setup.capture()
            val owner = f.claim()
            f.recovery.expectHandoff(owner) { connectionTicket.isCurrent && setupTicket.isCurrent }
            f.close(owner)
            assertNull(f.recovery.target(true))
            if (retireConnection) work.replace() else setup.replace()
            assertEquals("car", f.recovery.target(true))
        }
    }

    @Test fun lateOldOwnerCannotReplaceNewLossOrExpectation() {
        val f = Fixture()
        val old = f.claim()
        f.close(old, "old-car")
        val next = f.claim()
        f.expect(next)
        f.close(next, "new-car")
        f.recovery.expectHandoff(old) { true }
        f.recovery.noteLost("old-car", old)
        assertEquals("new-car", f.recovery.target(true))
        f.accepted = true
        assertNull(f.recovery.target(true))
    }

    @Test fun staleExpiryCleanupCannotClearNewOwnerLoss() {
        val f = Fixture()
        val old = f.claim()
        f.close(old)
        f.now += 180_001
        val expired = f.recovery.decision(true)
        val next = f.claim()
        f.close(next, "new-car")
        f.recovery.clearLoss(expired.owner)
        assertEquals("new-car", f.recovery.target(true))
    }

    @Test fun decisionKeepsSuppressionAndTargetFromTheSameObservation() {
        val f = Fixture()
        val owner = f.claim()
        f.expect(owner)
        f.accepted = true
        f.close(owner)
        val suppressed = f.recovery.decision(true)
        f.wifiLive = false
        assertNull(suppressed.target)
        assertTrue(suppressed.expectedHandoff) // caller must not mistake this null for expiry
        assertEquals("car", f.recovery.decision(true).target)
    }

    @Test fun ownershipChangesDuringReadCannotReturnOldRecoveryTarget() {
        val f = Fixture()
        val old = f.claim()
        f.close(old, "old-car")
        f.recovery.expectHandoff(old) {
            val next = f.claim()
            f.close(next, "new-car")
            false
        }
        assertNull(f.recovery.target(true))
        assertEquals("new-car", f.recovery.target(true))
    }

    @Test fun stopClearsExpectationEvenBeforeLateSuccess() {
        val f = Fixture()
        val owner = f.claim()
        f.expect(owner)
        f.close(owner)
        f.recovery.reset()
        f.accepted = true
        assertNull(f.recovery.address)
        assertFalse(f.recovery.expectedHandoff())
        f.recovery.noteLost("car", owner)
        assertEquals("car", f.recovery.target(true))
    }

    @Test fun acceptanceInvalidatesAlreadyCapturedDialOperation() {
        val f = Fixture()
        val owner = f.claim()
        f.expect(owner)
        f.close(owner)
        val operation = BtProfileOperationScope().capture(f.links) {
            BtDialPolicy.allows("car", "car", f.links.inbound(), true, f.recovery.target(true))
        }
        assertTrue(operation.isCurrent())
        f.accepted = true
        assertFalse(operation.isCurrent())
    }
}

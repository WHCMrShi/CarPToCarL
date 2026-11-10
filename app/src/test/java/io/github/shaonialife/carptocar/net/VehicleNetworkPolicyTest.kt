package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class VehicleNetworkPolicyTest {
    @Test fun passwordAndUnrelatedPeerCannotAdmitRequest() {
        assertFalse(VehicleNetworkPolicy.present("car", false, "headset", false))
        assertFalse(VehicleNetworkPolicy.present("car", false, null, false))
        assertTrue(VehicleNetworkPolicy.present("car", false, "CAR", false))
        assertTrue(VehicleNetworkPolicy.present("car", true, null, false))
        assertTrue(VehicleNetworkPolicy.present("car", false, null, true))
    }

    @Test fun deadlineDoesNotExtendAndCancellationDuringObservationWins() {
        var now = 0L
        assertFalse(VehicleNetworkPolicy.awaitPresence({ true }, { false }, { now }, { now += it }, 2_500))
        assertEquals(2_500L, now)
        var valid = true
        assertFalse(VehicleNetworkPolicy.awaitPresence({ valid }, { valid = false; true }, { 0 }, { fail() }))
    }

    @Test fun latePresenceReleasesSameWaitAndInterruptPreservesCancellation() {
        var now = 0L
        assertTrue(VehicleNetworkPolicy.awaitPresence({ true }, { now >= 1_000 }, { now }, { now += it }))
        assertEquals(1_000L, now)
        try {
            assertFalse(VehicleNetworkPolicy.awaitPresence({ true }, { false }, { 0 }, { throw InterruptedException() }))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun oldOrRedactedObservationsCannotAuthorizeNewNetwork() {
        val identities = LiveWifiIdentity<Int>()
        val old = identities.restart()
        identities.update(old, 1, "car")
        assertTrue(identities.matches("car", listOf(1)))
        assertFalse(identities.matches("car", listOf(2)))
        identities.update(old, 1, "<unknown ssid>")
        assertFalse(identities.matches("car", listOf(1)))
        val current = identities.restart()
        identities.update(old, 1, "car")
        assertFalse(identities.matches("car", listOf(1)))
        identities.update(current, 2, "car")
        identities.lost(old, 2)
        assertTrue(identities.matches("car", listOf(2)))
        identities.lost(current, 2)
        assertFalse(identities.matches("car", listOf(2)))
    }

    @Test fun unsuccessfulMigrationRetriesButCompletedMigrationDoesNotDisruptLiveNetwork() {
        val migration = OwnedSuggestionMigration()
        var calls = 0
        assertFalse(migration.ensure { calls++; false })
        assertTrue(migration.ensure { calls++; true })
        assertTrue(migration.ensure { fail("must not remove suggestions again during same connector lifetime"); false })
        assertEquals(2, calls)
    }
}

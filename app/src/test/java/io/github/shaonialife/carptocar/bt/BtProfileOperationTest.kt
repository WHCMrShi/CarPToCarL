package io.github.shaonialife.carptocar.bt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BtProfileOperationTest {
    private val links = BtLinkOwnership { 1L }
    private val scope = BtProfileOperationScope()

    @Test fun unchangedOperationKeepsProfileAndDeviceFallbackOrder() {
        val events = mutableListOf<String>()
        val submitted = disconnectProfilesForOperation(
            scope.capture(links), { listOf("HFP", "A2DP") },
            { profile -> { events += profile; Unit } },
            { timeout, current -> assertTrue(current()); events += "wait:$timeout"; false },
            { events += "device" },
        )
        assertEquals(listOf("HFP", "A2DP"), submitted)
        assertEquals(listOf("HFP", "A2DP", "wait:1500", "device", "wait:500"), events)
    }

    @Test fun inboundArrivingDuringWaitPreventsDeviceDisconnectAndSecondDial() {
        val operation = scope.capture(links)
        var deviceCalls = 0
        disconnectProfilesForOperation(
            operation, { listOf(1) }, { { Unit } },
            { _, current -> links.claim("inbound"); assertFalse(current()); false },
            { deviceCalls++ },
        )
        assertEquals(0, deviceCalls)
        assertFalse(operation.isCurrent())
        assertNull(links.claimObserved("outbound", operation.observation))
        assertTrue(links.inbound())
    }

    @Test fun completedInterveningSessionCannotReviveOldRetry() {
        val old = scope.capture(links)
        val inbound = links.claim("inbound")!!
        inbound.finish()
        assertFalse(old.isCurrent())
        assertNull(links.claimObserved("outbound", old.observation))
        val fresh = scope.capture(links)
        assertTrue(fresh.isCurrent())
        assertNotNull(links.claimObserved("outbound", fresh.observation))
    }

    @Test fun blockedProxyLookupCannotDisconnectNewOwner() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val operation = scope.capture(links)
        var profileCalls = 0
        var deviceCalls = 0
        var failure: Throwable? = null
        val worker = Thread {
            try {
                disconnectProfilesForOperation(
                    operation, { listOf(1, 2) },
                    { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); { profileCalls++; Unit } },
                    { _, _ -> false }, { deviceCalls++ },
                )
            } catch (error: Throwable) { failure = error } finally { finished.countDown() }
        }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            links.claim("inbound")!!.markReady()
        } finally { release.countDown() }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        failure?.let { throw it }
        assertEquals(0, profileCalls)
        assertEquals(0, deviceCalls)
    }

    @Test fun ownerChangeDuringStatusLookupPreventsAllActions() {
        val operation = scope.capture(links)
        assertTrue(disconnectProfilesForOperation(
            operation, { links.claim("inbound"); listOf(1) },
            { error("must not query another proxy") }, { _, _ -> error("must not wait") },
            { error("must not disconnect") },
        ).isEmpty())
    }

    @Test fun stopOrTargetRoundChangeCancelsRemainingProfileAndFallback() {
        val operation = scope.capture(links)
        val sent = mutableListOf<Int>()
        disconnectProfilesForOperation(
            operation, { listOf(1, 2) },
            { profile -> { sent += profile; scope.invalidate() } },
            { _, _ -> error("obsolete wait") }, { error("obsolete fallback") },
        )
        assertEquals(listOf(1), sent)
        assertFalse(operation.isCurrent())
        assertTrue(scope.capture(links).isCurrent())
    }

    @Test fun sameNegotiatingOwnerStillAllowsProfileReleaseButNeverDeviceFallback() {
        links.claim("inbound")!!
        val operation = scope.capture(links)
        var calls = 0
        val submitted = disconnectProfilesForOperation(
            operation, { listOf(1) }, { { calls++; Unit } },
            { _, _ -> false }, { error("active RFCOMM must not be disconnected") },
        )
        assertEquals(listOf(1), submitted)
        assertEquals(1, calls)
        assertTrue(operation.isCurrent())
    }

    @Test fun callerWorkCancellationDuringWaitSuppressesFallback() {
        var current = true
        val operation = scope.capture(links) { current }
        disconnectProfilesForOperation(
            operation, { listOf(1) }, { { Unit } },
            { _, _ -> current = false; false }, { error("cancelled fallback") },
        )
        assertFalse(operation.isCurrent())
    }

    @Test fun delayedPairingCleanupDoesNotRecaptureNewSession() {
        val operation = scope.capture(links)
        disconnectProfilesForOperation(operation, { emptyList<Int>() }, { null }, { _, _ -> true }, {})
        links.claim("outbound")!!.finish()
        assertTrue(disconnectProfilesForOperation(
            operation, { error("second cleanup must not run") }, { _: Int -> null }, { _, _ -> false }, {},
        ).isEmpty())
    }

    @Test fun retiredSameOwnerInvalidatesOperation() {
        val owner = links.claim("inbound")!!
        val operation = scope.capture(links)
        owner.retire()
        assertFalse(operation.isCurrent())
    }
}

package io.github.shaonialife.carptocar.bt

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BtProfileOperationTest {
    private val links = BtLinkOwnership { 1L }
    private val scope = BtProfileOperationScope()
    private fun <P> online(vararg profiles: P) = profiles.associateWith { BtProfileState.CONNECTED }
    private val accepted = BtDisconnectRequest.ACCEPTED

    @Test fun unchangedOperationKeepsProfileAndDeviceFallbackOrder() {
        val events = mutableListOf<String>()
        val result = disconnectProfilesForOperation(
            scope.capture(links), { online("HFP", "A2DP") },
            { profile -> { events += profile; accepted } },
            { _, timeout, current -> assertTrue(current()); events += "wait:$timeout"; online("HFP", "A2DP") },
            { events += "device"; accepted },
        )
        assertEquals(setOf("HFP", "A2DP"), result.requests.keys)
        assertEquals(listOf("HFP", "A2DP", "wait:1500", "device", "wait:500"), events)
    }

    @Test fun inboundArrivingDuringWaitPreventsDeviceDisconnectAndSecondDial() {
        val operation = scope.capture(links)
        var deviceCalls = 0
        disconnectProfilesForOperation(
            operation, { online(1) }, { { accepted } },
            { _, _, current -> links.claim("inbound"); assertFalse(current()); online(1) },
            { deviceCalls++; accepted },
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
                    operation, { online(1, 2) },
                    { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); { profileCalls++; accepted } },
                    { _, _, _ -> online(1) }, { deviceCalls++; accepted },
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
            operation, { links.claim("inbound"); online(1) },
            { error("must not query another proxy") }, { _, _, _ -> error("must not wait") },
            { error("must not disconnect") },
        ).requests.isEmpty())
    }

    @Test fun stopOrTargetRoundChangeCancelsRemainingProfileAndFallback() {
        val operation = scope.capture(links)
        val sent = mutableListOf<Int>()
        disconnectProfilesForOperation(
            operation, { online(1, 2) },
            { profile -> { sent += profile; scope.invalidate(); accepted } },
            { _, _, _ -> error("obsolete wait") }, { error("obsolete fallback") },
        )
        assertEquals(listOf(1), sent)
        assertFalse(operation.isCurrent())
        assertTrue(scope.capture(links).isCurrent())
    }

    @Test fun sameNegotiatingOwnerStillAllowsProfileReleaseButNeverDeviceFallback() {
        links.claim("inbound")!!
        val operation = scope.capture(links)
        var calls = 0
        val result = disconnectProfilesForOperation(
            operation, { online(1) }, { { calls++; accepted } },
            { _, _, _ -> online(1) }, { error("active RFCOMM must not be disconnected") },
        )
        assertEquals(setOf(1), result.requests.keys)
        assertEquals(1, calls)
        assertTrue(operation.isCurrent())
    }

    @Test fun callerWorkCancellationDuringWaitSuppressesFallback() {
        var current = true
        val operation = scope.capture(links) { current }
        disconnectProfilesForOperation(
            operation, { online(1) }, { { accepted } },
            { _, _, _ -> current = false; online(1) }, { error("cancelled fallback") },
        )
        assertFalse(operation.isCurrent())
    }

    @Test fun delayedPairingCleanupDoesNotRecaptureNewSession() {
        val operation = scope.capture(links)
        disconnectProfilesForOperation(operation, { emptyMap<Int, BtProfileState>() }, { null }, { _, _, _ -> emptyMap() }, { accepted })
        links.claim("outbound")!!.finish()
        assertTrue(disconnectProfilesForOperation(
            operation, { error("second cleanup must not run") }, { _: Int -> null }, { _, _, _ -> online(1) }, { accepted },
        ).requests.isEmpty())
    }

    @Test fun retiredSameOwnerInvalidatesOperation() {
        val owner = links.claim("inbound")!!
        val operation = scope.capture(links)
        owner.retire()
        assertFalse(operation.isCurrent())
    }
}

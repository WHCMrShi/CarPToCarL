package io.github.shaonialife.carptocar.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class WifiNetworkLifecycleTest {
    private class Harness {
        val registrations = mutableListOf<Any>()
        val releases = mutableListOf<Any>()
        val bindings = mutableListOf<String?>()
        var physical: String? = null
        var rejectBind = false
        val lifecycle = WifiNetworkLifecycle<Any, String>(
            unregister = releases::add,
            bindProcess = {
                if (rejectBind) false else {
                    bindings.add(it)
                    physical = it
                    true
                }
            },
        )
        val request = Any()
        val observer = Any()
        fun start() {
            assertTrue(lifecycle.ensureObserver(observer, { true }) { registrations.add(observer) })
            assertTrue(lifecycle.startRequest(request, { true }) { registrations.add(request) })
        }
        fun requestBind(network: String) = assertTrue(lifecycle.bindRequest(request, network) { true })
        fun reuse(network: String): Any {
            val probe = Any()
            assertTrue(lifecycle.startProbe(probe) { true })
            assertTrue(lifecycle.bindProbe(probe, network) { true })
            lifecycle.endProbe(probe)
            return probe
        }
    }

    @Test fun reuseOfRequestedNetworkKeepsAssociationAndNeverUnbinds() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        h.reuse("834")
        assertSame(h.request, h.lifecycle.requestOwner())
        assertSame(h.observer, h.lifecycle.observerOwner())
        assertEquals(listOf("834", "834"), h.bindings)
        assertTrue(h.releases.isEmpty())
        assertEquals("834", h.lifecycle.usable { it == h.physical })
    }

    @Test fun q4LostNetworkThenReuseDoesNotRemoveTheStillRegisteredRequest() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        val loss = h.lifecycle.observerLost(h.observer, "834")!!
        assertTrue(loss())
        assertNull(h.lifecycle.network())
        assertSame(h.request, h.lifecycle.requestOwner())
        h.reuse("836")
        assertFalse(loss())
        assertNull(h.lifecycle.requestLost(h.request, "834"))
        assertEquals("836", h.physical)
        assertTrue(h.releases.isEmpty())
    }

    @Test fun timedOutOrRejectedReuseDoesNotReleaseRequestOrExistingBinding() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        repeat(20) {
            val probe = Any()
            assertTrue(h.lifecycle.ensureObserver(Any(), { true }) { fail("observer must be shared") })
            assertTrue(h.lifecycle.startProbe(probe) { true })
            // No identity-authorized candidate arrived before this waiter's deadline.
            h.lifecycle.endProbe(probe)
        }
        assertEquals(listOf(h.observer, h.request), h.registrations)
        assertEquals(listOf("834"), h.bindings)
        assertTrue(h.releases.isEmpty())
    }

    @Test fun oldProbeFinallyCannotEndItsSuccessorOrBindLate() {
        val h = Harness()
        h.start()
        val old = Any()
        val next = Any()
        h.lifecycle.startProbe(old) { true }
        h.lifecycle.startProbe(next) { true }
        h.lifecycle.endProbe(old)
        assertSame(next, h.lifecycle.probeOwner())
        assertFalse(h.lifecycle.bindProbe(old, "old") { true })
        assertTrue(h.lifecycle.bindProbe(next, "new") { true })
        assertEquals(listOf("new"), h.bindings)
    }

    @Test fun retainedRequestCannotStealVerifiedReuseBindingOrWithdrawIt() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        h.reuse("836")
        assertFalse(h.lifecycle.bindRequest(h.request, "835") { true })
        assertNull(h.lifecycle.requestLost(h.request, "834"))
        assertNull(h.lifecycle.releaseRequest(h.request))
        assertEquals("836", h.physical)
        assertEquals(listOf(h.request), h.releases)
        assertNotNull(h.lifecycle.observerLost(h.observer, "836"))
        assertNull(h.physical)
    }

    @Test fun sameNetworkAdoptionTransfersBindingWithoutRequestLossClearingIt() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        h.reuse("834")
        assertTrue(h.lifecycle.bindRequest(h.request, "834") { true })
        assertNull(h.lifecycle.requestLost(h.request, "834"))
        assertNotNull(h.lifecycle.observerLost(h.observer, "834"))
        assertNull(h.lifecycle.observerLost(h.observer, "834"))
        assertEquals(1, h.bindings.count { it == null })
    }

    @Test fun unavailableRequestWithdrawsOnlyItsOwnBinding() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        val loss = h.lifecycle.releaseRequest(h.request)!!
        assertTrue(loss())
        assertNull(h.lifecycle.requestOwner())
        assertSame(h.observer, h.lifecycle.observerOwner())
        assertNull(h.physical)
        assertFalse(h.lifecycle.bindRequest(h.request, "late") { true })
        h.reuse("836")
        assertFalse(loss())
    }

    @Test fun newActiveRequestExplicitlyReplacesOldRequestButKeepsObserver() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        val next = Any()
        assertTrue(h.lifecycle.startRequest(next, { true }) { h.registrations.add(next) })
        assertEquals(listOf(h.request), h.releases)
        assertSame(h.observer, h.lifecycle.observerOwner())
        assertFalse(h.lifecycle.withRequest(h.request) { fail("retired callback") })
        assertNull(h.lifecycle.releaseRequest(h.request))
        assertTrue(h.lifecycle.bindRequest(next, "new") { true })
        assertNull(h.lifecycle.requestLost(h.request, "new"))
        assertEquals("new", h.physical)
    }

    @Test fun terminalCleanupRetiresAllResourcesOnceAndRejectsLateCallbacks() {
        val h = Harness()
        h.start()
        val probe = h.reuse("834")
        h.lifecycle.release()
        h.lifecycle.release()
        assertEquals(listOf(h.request, h.observer), h.releases)
        assertEquals(listOf("834", null), h.bindings)
        assertFalse(h.lifecycle.withObserver(h.observer) { fail("retired observer") })
        assertFalse(h.lifecycle.bindRequest(h.request, "late") { true })
        assertFalse(h.lifecycle.bindProbe(probe, "late") { true })
        assertNull(h.lifecycle.network())
        assertNull(h.lifecycle.probeOwner())
    }

    @Test fun retiredObserverCannotClearAReplacementConnectionsNetwork() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        h.lifecycle.release()
        val nextObserver = Any()
        val nextRequest = Any()
        h.lifecycle.ensureObserver(nextObserver, { true }) {}
        h.lifecycle.startRequest(nextRequest, { true }) {}
        h.lifecycle.bindRequest(nextRequest, "836") { true }
        assertNull(h.lifecycle.observerLost(h.observer, "836"))
        assertNull(h.lifecycle.releaseRequest(h.request))
        assertEquals("836", h.physical)
    }

    @Test fun completedOldRequestCannotReturnSuccessorsBinding() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        // The old waiter has observed completion; a new request wins before it reads a result.
        assertSame(h.request, h.lifecycle.requestOwner())
        val next = Any()
        h.lifecycle.startRequest(next, { true }) {}
        h.lifecycle.bindRequest(next, "836") { true }
        assertNull(h.lifecycle.requestResult(h.request, { true }) { fail("old waiter reads new network"); true })
        assertEquals("836", h.lifecycle.requestResult(next, { true }) { it == h.physical })
        assertNull(h.lifecycle.releaseRequest(h.request))
        assertEquals("836", h.physical)
    }

    @Test fun completedOldProbeCannotReturnAfterReplacementOrNetworkLoss() {
        val h = Harness()
        h.start()
        val old = Any()
        h.lifecycle.startProbe(old) { true }
        h.lifecycle.bindProbe(old, "834") { true }
        assertEquals("834", h.lifecycle.probeResult(old) { true })
        val next = Any()
        h.lifecycle.startProbe(next) { true }
        assertNull(h.lifecycle.probeResult(old) { true })
        h.lifecycle.bindProbe(next, "836") { true }
        assertNull(h.lifecycle.probeResult(next) { false })
        assertEquals("836", h.lifecycle.probeResult(next) { true })
        h.lifecycle.observerLost(h.observer, "836")
        assertNull(h.lifecycle.probeResult(next) { true })
    }

    @Test fun requestCompletionMayUseVerifiedReuseWithoutCancellingAssociation() {
        val h = Harness()
        h.start()
        h.reuse("836")
        assertEquals("836", h.lifecycle.requestResult(h.request, { true }) { it == h.physical })
        assertTrue(h.releases.isEmpty())
        assertNull(h.lifecycle.requestResult(h.request, { false }) { true })
        assertNull(h.lifecycle.requestResult(h.request, { true }) { false })
    }

    @Test fun failedBindOrCancelledProbeCannotPublishOrReplaceBinding() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        val probe = Any()
        h.lifecycle.startProbe(probe) { true }
        h.rejectBind = true
        assertFalse(h.lifecycle.bindProbe(probe, "836") { true })
        h.rejectBind = false
        assertFalse(h.lifecycle.bindProbe(probe, "836") { false })
        assertEquals("834", h.lifecycle.network())
        assertEquals(listOf("834"), h.bindings)
    }

    @Test fun requestRegistrationDoesNotProveNetworkOrProcessUsability() {
        val h = Harness()
        h.start()
        assertNull(h.lifecycle.usable { true })
        var valid = true
        assertTrue(h.lifecycle.bindRequest(h.request, "834") { valid })
        assertNull(h.lifecycle.usable { false })
        valid = false
        assertNull(h.lifecycle.usable { true })
        assertSame(h.request, h.lifecycle.requestOwner())
    }

    @Test fun delayedLossRecoveryIsInvalidAfterStopOrWorkCancellation() {
        val h = Harness()
        h.start()
        var valid = true
        h.lifecycle.bindRequest(h.request, "834") { valid }
        val loss = h.lifecycle.observerLost(h.observer, "834")!!
        valid = false
        assertFalse(loss())
        valid = true
        h.lifecycle.release()
        assertFalse(loss())
    }

    @Test fun cancelledRegistrationDoesNotReplaceCurrentResources() {
        val h = Harness()
        h.start()
        h.requestBind("834")
        assertFalse(h.lifecycle.startRequest(Any(), { false }) { fail("cancelled registration") })
        assertFalse(h.lifecycle.startProbe(Any()) { false })
        assertTrue(h.releases.isEmpty())
        assertEquals("834", h.physical)
    }

    @Test fun observerRegistrationFailureDoesNotCancelActiveRequest() {
        val h = Harness()
        h.lifecycle.startRequest(h.request, { true }) {}
        h.requestBind("834")
        try {
            h.lifecycle.ensureObserver(h.observer, { true }) { throw IllegalStateException("registration failed") }
            fail("expected registration failure")
        } catch (_: IllegalStateException) {
            assertSame(h.request, h.lifecycle.requestOwner())
            assertNull(h.lifecycle.observerOwner())
            assertEquals(listOf(h.observer), h.releases)
            assertEquals("834", h.physical)
        }
    }

    @Test fun requestRegistrationFailureDoesNotLeakOwner() {
        val h = Harness()
        try {
            h.lifecycle.startRequest(h.request, { true }) { throw IllegalStateException("registration failed") }
            fail("expected registration failure")
        } catch (_: IllegalStateException) {
            assertNull(h.lifecycle.requestOwner())
            assertEquals(listOf(h.request), h.releases)
        }
    }

    @Test fun bindingAndTerminalCleanupAreSerialized() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val effects = mutableListOf<String?>()
        val lifecycle = WifiNetworkLifecycle<Any, String>({}, {
            if (it != null) {
                entered.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
            }
            effects.add(it)
            true
        })
        val request = Any()
        lifecycle.startRequest(request, { true }) {}
        val binder = Thread {
            try { lifecycle.bindRequest(request, "834") { true } } catch (error: Throwable) { failure.set(error) }
        }
        val stop = Thread {
            try { lifecycle.release() } catch (error: Throwable) { failure.set(error) }
        }
        binder.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        stop.start()
        proceed.countDown()
        binder.join(5_000)
        stop.join(5_000)
        assertFalse(binder.isAlive)
        assertFalse(stop.isAlive)
        assertNull(failure.get())
        assertEquals(listOf("834", null), effects)
        assertNull(lifecycle.network())
    }
}

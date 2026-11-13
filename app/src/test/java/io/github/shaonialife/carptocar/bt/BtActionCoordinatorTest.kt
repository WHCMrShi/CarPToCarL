package io.github.shaonialife.carptocar.bt

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BtActionCoordinatorTest {
    private val links = BtLinkOwnership { 1L }
    private val scope = BtProfileOperationScope()
    private val coordinator = BtActionCoordinator()
    private val workers = Executors.newFixedThreadPool(2)
    private fun CountDownLatch.awaitChecked() = assertTrue("worker timed out", await(3, TimeUnit.SECONDS))
    private fun waitLog(latch: CountDownLatch): (String) -> Unit = { if (it.contains("waiting")) latch.countDown() }

    @After fun stopWorkers() {
        workers.shutdownNow()
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
    }

    @Test fun guardAndPairingWaitForDialThenRequeryInsteadOfRepeatingRelease() {
        val dial = coordinator.acquire(scope.capture(links), "dial")!!
        val waiting = CountDownLatch(2)
        val requests = AtomicInteger()
        var state = BtProfileState.CONNECTED // Accessed only while holding the action lease.
        val futures = listOf("guard", "pair").map { source ->
            val operation = scope.capture(links)
            workers.submit(Callable {
                coordinator.acquire(operation, source, waitLog(waiting))!!.use {
                    disconnectProfilesForOperation(
                        operation, { mapOf("HFP" to state) },
                        { { requests.incrementAndGet(); BtDisconnectRequest.ACCEPTED } },
                        { _, _, _ -> state = BtProfileState.DISCONNECTED; mapOf("HFP" to state) },
                        { error("already offline") },
                    )
                }
            })
        }
        try {
            waiting.awaitChecked()
            assertEquals(0, requests.get())
        } finally { dial.close() }
        val results = futures.map { it.get(3, TimeUnit.SECONDS) }
        assertEquals(1, requests.get())
        assertEquals(1, results.count { it.requests.isNotEmpty() })
        assertTrue(results.all { it.after["HFP"] == BtProfileState.DISCONNECTED })
    }

    @Test fun dialWaitsForReleaseObservationAndDeviceFallbackToFinish() {
        val observing = CountDownLatch(1)
        val finishObservation = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val events = mutableListOf<String>()
        val release = workers.submit(Callable {
            val operation = scope.capture(links)
            coordinator.acquire(operation, "guard")!!.use {
                disconnectProfilesForOperation(
                    operation, { mapOf(1 to BtProfileState.CONNECTED) },
                    { { events += "request"; BtDisconnectRequest.ACCEPTED } },
                    { _, timeout, _ ->
                        events += "observe:$timeout"
                        if (timeout == 1500L) { observing.countDown(); finishObservation.awaitChecked() }
                        mapOf(1 to BtProfileState.CONNECTED)
                    },
                    { events += "device"; BtDisconnectRequest.REJECTED },
                )
            }
        })
        observing.awaitChecked()
        val dial = workers.submit(Callable {
            coordinator.acquire(scope.capture(links), "dial", waitLog(waiting))!!.use { events += "dial" }
        })
        try { waiting.awaitChecked(); assertFalse(dial.isDone) } finally { finishObservation.countDown() }
        release.get(3, TimeUnit.SECONDS)
        dial.get(3, TimeUnit.SECONDS)
        assertEquals(listOf("request", "observe:1500", "device", "observe:500", "dial"), events)
    }

    @Test fun inboundCancelsCapturedDialWithoutWaitingAndOldWorkNeverRevives() {
        val oldOperation = scope.capture(links)
        val dial = coordinator.acquire(oldOperation, "dial")!!
        val closed = AtomicInteger()
        val registration = dial.cancelWith { closed.incrementAndGet() }
        val waiting = CountDownLatch(1)
        val oldGuard = workers.submit(Callable { coordinator.acquire(oldOperation, "guard", waitLog(waiting)) })
        waiting.awaitChecked()
        val inbound = links.claim("inbound")!! // Deliberately does not acquire the action lease.
        coordinator.cancelObsoleteAction()
        assertEquals(1, closed.get())
        inbound.finish()
        assertNull(oldGuard.get(3, TimeUnit.SECONDS))
        assertNull(links.claimObserved("outbound", oldOperation.observation))
        registration.close()
        dial.close()
        // A failed inbound can still be followed by a fresh outbound session.
        val fresh = scope.capture(links)
        coordinator.acquire(fresh, "recovery")!!.use {
            assertNotNull(links.claimObserved("outbound", fresh.observation))
        }
    }

    @Test fun inboundBeforeSocketRegistrationStillCancelsThatSocket() {
        val dial = coordinator.acquire(scope.capture(links), "dial")!!
        val closed = AtomicInteger()
        links.claim("inbound")!!
        coordinator.cancelObsoleteAction()
        dial.cancelWith { closed.incrementAndGet() }.use {
            coordinator.cancelObsoleteAction()
            assertEquals(1, closed.get())
        }
        dial.close()
    }

    @Test fun successfulDialKeepsReservationUntilOwnerClaimInvalidatesQueuedCleanup() {
        val operation = scope.capture(links)
        val dial = coordinator.acquire(operation, "dial")!!
        val waiting = CountDownLatch(1)
        val cleanup = workers.submit(Callable { coordinator.acquire(scope.capture(links), "pair", waitLog(waiting)) })
        waiting.awaitChecked()
        assertFalse(cleanup.isDone)
        assertNotNull(links.claimObserved("outbound", operation.observation))
        dial.close()
        assertNull(cleanup.get(3, TimeUnit.SECONDS))
    }

    @Test fun stopOrTargetChangeCancelsOldSocketButHoldsLeaseUntilOldActionReturns() {
        val oldOperation = scope.capture(links)
        val old = coordinator.acquire(oldOperation, "old")!!
        val oldClosed = AtomicInteger()
        old.cancelWith { oldClosed.incrementAndGet() }
        scope.invalidate()
        coordinator.cancelObsoleteAction()
        assertEquals(1, oldClosed.get())
        assertFalse(oldOperation.isCurrent())
        val waiting = CountDownLatch(1)
        val fresh = workers.submit(Callable { coordinator.acquire(scope.capture(links), "new", waitLog(waiting)) })
        waiting.awaitChecked()
        assertFalse(fresh.isDone)
        old.close()
        val successor = fresh.get(3, TimeUnit.SECONDS)!!
        val successorClosed = AtomicInteger()
        successor.cancelWith { successorClosed.incrementAndGet() }
        old.close() // Late cleanup must not release the successor's lease.
        scope.invalidate()
        coordinator.cancelObsoleteAction()
        assertEquals(1, successorClosed.get())
        assertEquals(1, oldClosed.get())
        successor.close()
    }

    @Test fun interruptedWaiterKeepsInterruptAndDoesNotReleaseAnotherAction() {
        val active = coordinator.acquire(scope.capture(links), "active")!!
        val waiting = CountDownLatch(1)
        val done = CountDownLatch(1)
        var interrupted = false
        var result: BtActionCoordinator.Lease? = null
        val waiter = Thread {
            try {
                result = coordinator.acquire(scope.capture(links), "waiter", waitLog(waiting))
                interrupted = Thread.currentThread().isInterrupted
            } finally { done.countDown() }
        }
        waiter.start()
        try { waiting.awaitChecked(); waiter.interrupt(); done.awaitChecked() } finally {
            waiter.interrupt()
            active.close()
        }
        assertNull(result)
        assertTrue(interrupted)
        coordinator.acquire(scope.capture(links), "next")!!.close()
    }

    @Test fun interruptedNotifierDoesNotCancelAValidAction() {
        val lease = coordinator.acquire(scope.capture(links) { !Thread.currentThread().isInterrupted }, "dial")!!
        val closed = AtomicInteger()
        lease.cancelWith { closed.incrementAndGet() }
        try {
            Thread.currentThread().interrupt()
            coordinator.cancelObsoleteAction()
            assertEquals(0, closed.get())
        } finally { Thread.interrupted(); lease.close() }
    }

    @Test fun lateFirstDialCleanupCannotUnregisterSecondDialCancellation() {
        val lease = coordinator.acquire(scope.capture(links), "outbound-retry")!!
        val firstClosed = AtomicInteger()
        val retryClosed = AtomicInteger()
        val firstRegistration = lease.cancelWith { firstClosed.incrementAndGet() }
        firstRegistration.close() // Failed first dial is already closed by its own caller.
        lease.cancelWith { retryClosed.incrementAndGet() }
        firstRegistration.close() // A stale completion must not unregister the current socket.
        links.claim("inbound")!!
        coordinator.cancelObsoleteAction()
        assertEquals(0, firstClosed.get())
        assertEquals(1, retryClosed.get())
        lease.close()
    }
}

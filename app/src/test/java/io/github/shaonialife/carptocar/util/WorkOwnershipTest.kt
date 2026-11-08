package io.github.shaonialife.carptocar.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkOwnershipTest {
    @Test fun anOldSetupFinalizerCannotClearANewerAttemptsBusyState() {
        val connection = WorkEpoch()
        val user = connection.capture()
        val attempts = WorkEpoch()
        val old = attempts.capture()
        val next = attempts.replace()
        var busy = true
        user.runIfCurrent { old.runIfCurrent { busy = false } }
        assertTrue(busy)
        assertTrue(next.isCurrent)
        user.runIfCurrent { next.runIfCurrent { busy = false } }
        assertFalse(busy)
    }
    @Test fun stoppedQueuedWorkCannotPublishIntoTheNextConnection() {
        val epoch = WorkEpoch()
        val old = epoch.capture()
        epoch.end(); epoch.begin()
        var publications = 0
        assertFalse(old.runIfCurrent { publications++ })
        assertTrue(epoch.capture().runIfCurrent { publications++ })
        assertEquals(1, publications)
    }
    @Test fun staleReleaseAndRegistrationCannotDisposeTheNewRequest() {
        val slot = OwnedSlot<Any>()
        val epoch = WorkEpoch()
        val oldTicket = epoch.capture()
        val old = Any(); val next = Any()
        val disposed = mutableListOf<Any>()
        slot.replace(old, { true }, disposed::add)
        epoch.end(); epoch.begin()
        slot.replace(next, { true }, disposed::add)
        assertFalse(slot.clear(old, disposed::add))
        assertFalse(slot.replace(old, { oldTicket.isCurrent }, disposed::add))
        assertFalse(slot.runIfCurrent(old) { fail("old callback rebound the process") })
        assertSame(next, slot.get())
        assertEquals(listOf(old), disposed)
    }
    @Test fun replacementWaitsUntilAnAuthorizedSideEffectFinishes() {
        val slot = OwnedSlot<Any>()
        val first = Any(); val next = Any()
        slot.replace(first, { true }, {})
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val replaced = AtomicBoolean(false)
        val worker = Thread {
            slot.runIfCurrent(first) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        }
        worker.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val replacer = Thread { slot.replace(next, { true }, {}); replaced.set(true) }
        replacer.start()
        assertFalse(replaced.get())
        release.countDown(); worker.join(3_000); replacer.join(3_000)
        assertFalse(worker.isAlive); assertFalse(replacer.isAlive)
        assertTrue(replaced.get()); assertSame(next, slot.get())
    }
}

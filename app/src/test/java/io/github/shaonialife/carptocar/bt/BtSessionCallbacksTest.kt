package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.util.WorkEpoch
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BtSessionCallbacksTest {
    private val endpoint = Iap2SenderSession.CarPlayEndpoint("q4", "fixed", 149, 2, 7000, null, null, null, emptyList())
    private val wifi = Iap2SenderSession.WifiConfiguration("q4", "fixed", 149, 2)

    @Test fun oldConnectionCallbacksCannotBorrowRestartedConnectionsTicket() {
        val work = WorkEpoch(); val captured = work.capture(); val links = BtLinkOwnership { 0L }
        val owner = links.claim("inbound")!!; val writes = AtomicInteger()
        val callbacks = BtSessionCallbacks(isCurrent = { captured.isCurrent },
            onEndpoint = { _, _ -> writes.incrementAndGet() }, onWifi = { _, _ -> writes.incrementAndGet() },
            onUpdate = { _, _ -> writes.incrementAndGet() }, onClosed = { writes.incrementAndGet() },
            onConnected = { writes.incrementAndGet() })
        assertTrue(owner.isCurrent) // The first ownership check has passed.
        work.end(); work.begin()
        callbacks.endpoint(endpoint, owner); callbacks.wifi(wifi, owner); callbacks.update(endpoint, owner)
        callbacks.closed(owner); callbacks.connected(owner)
        assertEquals(0, writes.get())
    }

    @Test fun restartAfterDispatchCheckStillRejectsCommitUsingCapturedTicket() {
        val work = WorkEpoch(); val captured = work.capture(); val links = BtLinkOwnership { 0L }
        val owner = links.claim("outbound")!!; val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val writes = AtomicInteger()
        val callbacks = BtSessionCallbacks(isCurrent = { captured.isCurrent }, onEndpoint = { _, link ->
            entered.countDown(); check(resume.await(3, TimeUnit.SECONDS))
            captured.runIfCurrent { if (link.isCurrent) writes.incrementAndGet() }
        })
        val failure = AtomicReference<Throwable>()
        val thread = Thread { runCatching { callbacks.endpoint(endpoint, owner) }.onFailure(failure::set) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            work.end(); work.begin()
        } finally { resume.countDown(); thread.join(3000) }
        assertFalse(thread.isAlive); assertNull(failure.get()); assertEquals(0, writes.get())
        val next = work.capture()
        val fresh = BtSessionCallbacks(isCurrent = { next.isCurrent }, onEndpoint = { _, link ->
            next.runIfCurrent { if (link.isCurrent) writes.incrementAndGet() }
        })
        fresh.endpoint(endpoint, owner)
        assertEquals(1, writes.get())
    }

    @Test fun sameConnectionNewBtOwnerCannotAcceptOlderOwnersInput() {
        val work = WorkEpoch(); val captured = work.capture(); val links = BtLinkOwnership { 0L }
        val old = links.claim("inbound")!!; val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val writes = AtomicInteger()
        val callbacks = BtSessionCallbacks(isCurrent = { captured.isCurrent }, onWifi = { _, link ->
            entered.countDown(); check(resume.await(3, TimeUnit.SECONDS))
            captured.runIfCurrent { if (link.isCurrent) writes.incrementAndGet() }
        })
        val failure = AtomicReference<Throwable>()
        val thread = Thread { runCatching { callbacks.wifi(wifi, old) }.onFailure(failure::set) }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            old.retire(); assertNotNull(links.claim("outbound"))
        } finally { resume.countDown(); thread.join(3000) }
        assertFalse(thread.isAlive); assertNull(failure.get()); assertEquals(0, writes.get())
    }

    @Test fun acceptedWifiSetupSurvivesNormalBtHandoffAndNextBtOwner() {
        val work = WorkEpoch(); val captured = work.capture(); val setup = WorkEpoch()
        val links = BtLinkOwnership { 0L }; val owner = links.claim("inbound")!!
        var accepted: WorkEpoch.Ticket? = null; var closes = 0
        val callbacks = BtSessionCallbacks(isCurrent = { captured.isCurrent }, onEndpoint = { _, link ->
            captured.runIfCurrent { if (link.isCurrent) accepted = setup.replace() }
        }, onClosed = { closes++ })
        callbacks.endpoint(endpoint, owner)
        assertTrue(owner.finish()); callbacks.closed(owner)
        assertNotNull(links.claim("outbound"))
        assertTrue(accepted!!.isCurrent); assertTrue(captured.isCurrent); assertEquals(1, closes)
    }

    @Test fun retiredBtOwnerCannotPublishAnyCallbackEvenWhileUserWorkRemainsCurrent() {
        val links = BtLinkOwnership { 0L }; val old = links.claim("outbound")!!; val writes = AtomicInteger()
        val callbacks = BtSessionCallbacks(onEndpoint = { _, _ -> writes.incrementAndGet() },
            onWifi = { _, _ -> writes.incrementAndGet() }, onUpdate = { _, _ -> writes.incrementAndGet() },
            onClosed = { writes.incrementAndGet() }, onConnected = { writes.incrementAndGet() })
        old.retire(); links.claim("inbound")
        callbacks.endpoint(endpoint, old); callbacks.wifi(wifi, old); callbacks.update(endpoint, old)
        callbacks.closed(old); callbacks.connected(old)
        assertEquals(0, writes.get())
    }
    @Test fun ignoredInputLogsItsOriginalConnectionWithoutDeliveringIt() {
        val work = WorkEpoch(); val ticket = work.capture()
        val owner = BtLinkOwnership { 0L }.claim("inbound")!!
        val original = ticket.diagnosticId
        var delivered = false
        val logs = mutableListOf<String>()
        val callbacks = BtSessionCallbacks(isCurrent = { ticket.isCurrent },
            onWifi = { _, _ -> delivered = true }, diagnosticConnection = original, diagnosticLog = logs::add)
        work.end(); work.begin()
        callbacks.wifi(wifi, owner)
        assertFalse(delivered)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("connection=$original") && logs.single().contains("kind=wifi"))
        assertTrue(logs.single().contains("connectionCurrent=false"))
        assertEquals(original, ticket.diagnosticId)
    }

}

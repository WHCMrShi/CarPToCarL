package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class SourceKeyFrameInitTest {
    private class Harness(ordered: Boolean = true) {
        var now = 100L
        var current = true
        var onWait: (() -> Unit)? = null
        val writes = mutableListOf<Long>()
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                writes += now
                super.write(bytes, off, len)
            }
        }
        val hu = CarLifeHuClient(log = {}, onVideoFrame = { _, _, _ -> }, orderedInit = ordered,
            encoderInitClock = { now }, keyFrameWait = { now += it; onWait?.invoke() })
        init {
            (field("running") as AtomicBoolean).set(true)
            CarLifeHuClient::class.java.getDeclaredField("cmdOut").apply { isAccessible = true }.set(hu, output)
        }
        private fun field(name: String): Any? = CarLifeHuClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(hu)
        fun initialize(force: Boolean = false): Boolean = CarLifeHuClient::class.java
            .getDeclaredMethod("sendEncoderInit", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(hu, force) as Boolean
        fun request(): Boolean = hu.requestSourceKeyFrame { current }
        fun stop() { (field("running") as AtomicBoolean).set(false) }
    }

    @Test fun recentOrdinaryInitPreventsImmediateDuplicateButRequestIsNotLost() {
        val h = Harness()
        assertTrue(h.initialize())
        assertTrue(h.request())
        assertEquals(listOf(100L, 5_100L), h.writes)
    }
    @Test fun realRequestGateCancelsWaitAtArrivalBeforeTheDownstreamWrite() {
        val h = Harness(); h.initialize()
        val gate = io.github.shaonialife.carptocar.video.SourceKeyFrameRequest()
        val ticket = gate.begin(1, h.now)!!
        h.onWait = { gate.arrived(1) }
        val sent = h.hu.requestSourceKeyFrame { gate.needsForward(ticket, 1) }
        assertFalse(sent)
        assertTrue(gate.forwarded(ticket, sent))
        assertSame(ticket, gate.written(1))
        assertEquals(listOf(100L), h.writes)
    }
    @Test fun arrivingIdrCompletesPendingRequestWithoutAnotherInit() {
        val h = Harness(); h.initialize()
        h.onWait = { h.current = false }
        assertFalse(h.request())
        assertEquals(listOf(100L), h.writes)
    }
    @Test fun anotherRecoveryInitExtendsCooldownWithoutUnboundedWaiting() {
        val h = Harness(); h.initialize()
        h.onWait = { if (h.now == 1_100L) h.initialize(force = true) }
        assertFalse(h.request())
        assertEquals(listOf(100L, 1_100L), h.writes)
        assertEquals(5_100L, h.now)
        h.now = 6_100L
        assertTrue(h.request())
        assertEquals(listOf(100L, 1_100L, 6_100L), h.writes)
    }
    @Test fun sourceRequestCannotPreemptOrderedStartup() {
        val h = Harness()
        h.onWait = { h.initialize(); h.current = false }
        assertFalse(h.request())
        assertEquals(listOf(200L), h.writes)
    }
    @Test fun stopDuringCooldownCannotWriteAnotherCommand() {
        val h = Harness(); h.initialize(); h.onWait = { h.stop() }
        assertFalse(h.request())
        assertEquals(listOf(100L), h.writes)
    }
    @Test fun interruptedWaitRestoresCancellationFlag() {
        val h = Harness(); h.initialize(); h.onWait = { throw InterruptedException() }
        try {
            assertFalse(h.request())
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(100L), h.writes)
        } finally { Thread.interrupted() }
    }
    @Test fun unorderedSourceWithoutPriorInitCanRequestImmediately() {
        val h = Harness(ordered = false)
        assertTrue(h.request())
        assertEquals(listOf(100L), h.writes)
    }
}

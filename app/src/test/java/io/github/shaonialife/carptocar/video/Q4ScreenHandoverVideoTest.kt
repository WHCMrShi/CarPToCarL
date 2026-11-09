package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Q4ScreenHandoverVideoTest {
    private val csd = byteArrayOf(0,0,0,1,0x67,66,0,30,0,0,0,1,0x68,1)
    private val idr = byteArrayOf(0,0,0,1,0x65,1)
    private val pFrame = byteArrayOf(0,0,0,1,0x61,1)
    private fun sender(output: ByteArrayOutputStream = ByteArrayOutputStream()): VideoSender {
        val sender = VideoSender("127.0.0.1", 5000, StreamFrameCipher.ChaCha(ByteArray(32)), 1412, 654, log = {}, uptimeMillis = { 1_000L })
        val socket = object : java.net.Socket() { override fun getOutputStream(): java.io.OutputStream = output }
        VideoSender::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(sender, socket)
        assertTrue(sender.startPassthrough())
        return sender
    }
    @Test fun carlifeFirstCachedGopAndNextLiveIdrKeepExistingStartup() {
        val sender = sender()
        val calls = AtomicInteger()
        sender.onSourceHandover = { calls.incrementAndGet() }
        try {
            assertTrue(sender.submitSourceH264(csd + idr, true))
            assertTrue(sender.submitSourceH264(idr, true))
            assertEquals(2L, sender.framesSentTotal)
            assertEquals(0, calls.get())
        } finally { sender.stop() }
    }
    @Test fun noticeConfigAloneCannotQualifyLateSourceForRecovery() {
        val sender = sender()
        val calls = AtomicInteger()
        sender.onSourceHandover = { calls.incrementAndGet() }
        try {
            assertTrue(sender.submitH264(csd + idr, true)) // production notice submission path
            sender.armSourceHandover("notice-to-real-source")
            assertFalse(sender.submitSourceH264(pFrame, false))
            assertFalse(sender.submitSourceH264(idr, true)) // no source SPS/PPS or seeded source CSD
            assertEquals(0, calls.get())
            assertTrue(sender.submitSourceH264(csd + idr, true))
            assertEquals(1, calls.get())
            assertTrue(sender.submitSourceH264(idr, true))
            assertEquals(1, calls.get())
        } finally { sender.stop() }
    }
    @Test fun sourceRestartNeedsFreshKeyframeAndDoesNotResetCipherCounter() {
        val sender = sender()
        val events = mutableListOf<Q4ScreenHandover.Ticket>()
        sender.onSourceHandover = { events += it }
        try {
            assertTrue(sender.submitSourceH264(csd + idr, true))
            sender.onSourceRestart("test")
            assertFalse(sender.submitSourceH264(pFrame, false))
            assertTrue(sender.submitSourceH264(csd + idr, true))
            assertEquals(1, events.size)
            assertEquals(2L, sender.framesSentTotal)
            val field = VideoSender::class.java.getDeclaredField("frameCounter").apply { isAccessible = true }
            assertEquals(2L, field.getLong(sender))
        } finally { sender.stop() }
    }
    @Test fun asynchronousQueueAdmissionIsNotDeliveryAndRecoveryWaitsForCompletedWrite() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val notified = CountDownLatch(1)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length >= 128 && bytes[offset + 4] == 0.toByte()) {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
                super.write(bytes, offset, length)
            }
        }
        val sender = sender(output)
        sender.onSourceHandover = { assertTrue(sender.framesSentTotal > 0); notified.countDown() }
        try {
            sender.armSourceHandover("late-source")
            assertTrue(sender.enqueueSourceH264(csd + idr, 0L) { })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse(notified.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(notified.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); sender.stop() }
    }
}

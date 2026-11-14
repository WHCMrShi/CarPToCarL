package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TouchLocalRecoveryTest {
    private class Recording : OutputStream() {
        val frames = LinkedBlockingQueue<ByteArray>()
        override fun write(value: Int) = error("frame required")
        override fun write(b: ByteArray, off: Int, len: Int) { frames.add(b.copyOfRange(off, off + len)) }
        fun next() = checkNotNull(frames.poll(3, TimeUnit.SECONDS))
    }
    private val broken = object : OutputStream() {
        override fun write(value: Int): Unit = throw IOException("partial write")
    }
    private fun client(log: (String) -> Unit = {}): CarLifeHuClient {
        val hu = CarLifeHuClient(log = log, onVideoFrame = { _, _, _ -> })
        CarLifeHuClient::class.java.getDeclaredField("running").apply { isAccessible = true }
            .let { (it.get(hu) as AtomicBoolean).set(true) }
        return hu
    }
    @Test fun realClientReconnectsOnlyTouchAndDoesNotReplayFailedGesture() {
        val ready = CountDownLatch(1)
        val out = Recording()
        val hu = client { if (it.contains("local=ready")) ready.countDown() }
        hu.connectTouch = { object : Socket() { override fun getOutputStream() = out } }
        try {
            hu.startTouchForwarding(broken)
            hu.touchDown(10, 20)
            hu.touchMove(15, 25)
            hu.touchUp(15, 25)
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            assertTrue(hu.isRunning)
            assertArrayEquals(CarLifeProtocol.packCommand(CarLifeProtocol.TOUCH_ACTION,
                CarLifeProtocol.touchAction(3, 0, 0)), out.next())
            hu.touchMove(30, 40); hu.touchUp(30, 40) // stale tail is discarded
            hu.touchDown(50, 60); hu.touchUp(50, 60)
            assertArrayEquals(CarLifeProtocol.packCommand(CarLifeProtocol.TOUCH_DOWN,
                CarLifeProtocol.touchPoint(50, 60)), out.next())
            assertArrayEquals(CarLifeProtocol.packCommand(CarLifeProtocol.TOUCH_UP,
                CarLifeProtocol.touchPoint(50, 60)), out.next())
            assertTrue(out.frames.isEmpty())
        } finally { hu.stop() }
    }
    @Test fun retiringClientDuringConnectClosesLateSocketWithoutSendingCancel() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closed = CountDownLatch(1)
        val out = Recording(); val hu = client()
        hu.connectTouch = {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            object : Socket() {
                override fun getOutputStream() = out
                override fun close() { closed.countDown() }
            }
        }
        try {
            hu.startTouchForwarding(broken); hu.touchAction(261, 10, 20)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            hu.stop(); release.countDown()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertTrue(out.frames.isEmpty()); assertFalse(hu.isRunning)
        } finally { release.countDown(); hu.stop() }
    }
    @Test fun cancellationFailureFallsBackToWholeClientRecovery() {
        val failed = CountDownLatch(1)
        val hu = client { if (it.contains("已停止本轮连接")) failed.countDown() }
        hu.connectTouch = { object : Socket() { override fun getOutputStream() = broken } }
        try {
            hu.startTouchForwarding(broken); hu.touchDown(1, 2)
            assertTrue(failed.await(3, TimeUnit.SECONDS)); assertFalse(hu.isRunning)
        } finally { hu.stop() }
    }
}

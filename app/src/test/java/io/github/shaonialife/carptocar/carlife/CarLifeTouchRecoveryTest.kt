package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CarLifeTouchRecoveryTest {
    /** 只模拟握手完成状态，避免启动 Android 时钟/真实固定端口；生产故障回调和 stop 原样执行。 */
    private fun connectedClient(log: (String) -> Unit = {}): CarLifeHuClient {
        val client = CarLifeHuClient(log = log, onVideoFrame = { _, _, _ -> })
        val field = CarLifeHuClient::class.java.getDeclaredField("running")
        field.isAccessible = true
        (field.get(client) as AtomicBoolean).set(true)
        return client
    }

    private class RecordingOutput : OutputStream() {
        val frames = LinkedBlockingQueue<ByteArray>()

        override fun write(value: Int) {
            error("expected frame write")
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            frames.add(bytes.copyOfRange(offset, offset + length))
        }

        fun next(): ByteArray = checkNotNull(frames.poll(3, TimeUnit.SECONDS))
    }

    @Test fun actualFailureCallbackStopsClientSoOwnerCanReplaceItAndNewClicksWork() {
        val failed = CountDownLatch(1)
        val old = connectedClient { failed.countDown() }
        val output = RecordingOutput()
        var replacement: CarLifeHuClient? = null
        try {
            old.startTouchForwarding(
                object : OutputStream() {
                    override fun write(value: Int): Unit = throw IOException("broken touch stream")

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Unit = throw IOException("broken touch stream")
                },
            )
            old.touchDown(1, 2)
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            // CarLifeHijack 守候循环正是以 isRunning 结束旧轮次；修复前这里一直为 true。
            assertFalse(old.isRunning)
            old.touchUp(1, 2)
            replacement = connectedClient()
            replacement.startTouchForwarding(output)
            replacement.touchDown(50, 60)
            replacement.touchUp(50, 60)
            assertArrayEquals(
                CarLifeProtocol.packCommand(
                    CarLifeProtocol.TOUCH_DOWN,
                    CarLifeProtocol.touchPoint(50, 60),
                ),
                output.next(),
            )
            assertArrayEquals(
                CarLifeProtocol.packCommand(
                    CarLifeProtocol.TOUCH_UP,
                    CarLifeProtocol.touchPoint(50, 60),
                ),
                output.next(),
            )
            assertEquals(0, old.sentTouchCount)
            assertTrue(replacement.isRunning)
        } finally {
            old.stop()
            replacement?.stop()
        }
    }

    @Test fun failureWhileRetirementSendsUpAlsoStopsProductionClient() {
        val failed = CountDownLatch(1)
        val downWritten = CountDownLatch(1)
        val client = connectedClient { failed.countDown() }
        try {
            client.startTouchForwarding(
                object : OutputStream() {
                    private var writes = 0

                    override fun write(value: Int) {
                        error("expected frame write")
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        if (++writes == 1) downWritten.countDown() else throw IOException("failed release")
                    }
                },
            )
            client.touchDown(10, 20)
            assertTrue(downWritten.await(3, TimeUnit.SECONDS))
            client.retireTouchInput()
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertFalse(client.isRunning)
        } finally {
            client.stop()
        }
    }

    @Test fun cancelledStartupDoesNotOpenPortsOrPublishRunningState() {
        val client =
            CarLifeHuClient(
                log = { throw AssertionError("startup must stop before connecting") },
                onVideoFrame = { _, _, _ -> },
            )
        assertFalse(client.start { false })
        assertFalse(client.isRunning)
    }
}

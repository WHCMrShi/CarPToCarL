package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FeatureDispatchTest {
    private class Harness {
        var waited = 0L
        var onWait: (() -> Unit)? = null
        val commands = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        val initialized = CountDownLatch(1)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                val type = ByteBuffer.wrap(bytes, off + 4, 4).int
                commands += type
                if (type == CarLifeProtocol.HU_VIDEO_ENC_INIT) initialized.countDown()
                super.write(bytes, off, len)
            }
        }
        val hu = CarLifeHuClient(
            log = { logs += it }, onVideoFrame = { _, _, _ -> }, orderedInit = true,
            encoderInitClock = { 100L },
            featureDispatchWait = { waited += it; onWait?.invoke() },
        )
        init {
            running().set(true)
            setField("versionMatched", true)
            setField("cmdOut", output)
        }
        private fun setField(name: String, value: Any) = CarLifeHuClient::class.java
            .getDeclaredField(name).apply { isAccessible = true }.set(hu, value)
        private fun running() = CarLifeHuClient::class.java.getDeclaredField("running")
            .apply { isAccessible = true }.get(hu) as AtomicBoolean
        fun stop() { running().set(false) }
        fun receive(type: Int) = CarLifeHuClient::class.java
            .getDeclaredMethod("handleMessage", Int::class.javaPrimitiveType, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(hu, type, ByteArray(0))
        fun waitForDispatch() = CarLifeHuClient::class.java.getDeclaredMethod("waitAfterFeatureConfig")
            .apply { isAccessible = true }.invoke(hu) as Boolean
        fun startAndAwaitInit() {
            receive(CarLifeProtocol.MD_INFO)
            assertTrue("Ordered startup must complete without a feature ACK", initialized.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun micWakeupIsAnObservationAndDoesNotSendAResponse() {
        val h = Harness()
        try {
            assertEquals(65570, CarLifeProtocol.MD_MIC_RECORD_WAKEUP_START)
            repeat(2) { h.receive(65570) }
            assertTrue(h.commands.isEmpty())
            assertEquals(1, h.logs.size)
            assertTrue(h.logs.single().contains("麦克风唤醒"))
            assertFalse(h.logs.single().contains("引擎已接受"))
        } finally { h.stop() }
    }

    @Test fun orderedStartupSendsFeatureBeforeInitWithoutAnyMicEvent() {
        val h = Harness()
        try {
            h.startAndAwaitInit()
            assertEquals(2_000L, h.waited)
            assertEquals(listOf(CarLifeProtocol.HU_INFO, CarLifeProtocol.HU_FEATURE_CONFIG,
                CarLifeProtocol.HU_VIDEO_ENC_INIT), h.commands)
        } finally { h.stop() }
    }

    @Test fun micWakeupDuringDispatchDoesNotReleaseInitializationEarly() {
        val h = Harness()
        h.onWait = { h.receive(65570) }
        try {
            h.startAndAwaitInit()
            assertEquals(2_000L, h.waited)
            assertEquals(1, h.commands.count { it == CarLifeProtocol.HU_VIDEO_ENC_INIT })
        } finally { h.stop() }
    }

    @Test fun stoppingDuringDispatchCancelsTheWait() {
        val h = Harness()
        h.onWait = { h.stop() }
        assertFalse(h.waitForDispatch())
        assertEquals(50L, h.waited)
        assertTrue(h.commands.isEmpty())
    }

    @Test fun interruptedDispatchRestoresTheInterruptFlag() {
        val h = Harness()
        h.onWait = { throw InterruptedException() }
        try {
            assertFalse(h.waitForDispatch())
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(h.commands.isEmpty())
        } finally { Thread.interrupted(); h.stop() }
    }
}

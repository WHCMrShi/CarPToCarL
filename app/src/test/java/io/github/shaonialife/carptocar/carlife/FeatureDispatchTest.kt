package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FeatureDispatchTest {
    private class Harness(ordered: Boolean = true, matched: Boolean = true) {
        var waited = 0L
        var now = 100L
        var onWait: (() -> Unit)? = null
        var failFeatureWrite = false
        var failInitWrite = false
        val commands = CopyOnWriteArrayList<Int>()
        val packets = CopyOnWriteArrayList<ByteArray>()
        val logs = CopyOnWriteArrayList<String>()
        val initialized = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                val type = ByteBuffer.wrap(bytes, off + 4, 4).int
                if ((type == CarLifeProtocol.HU_FEATURE_CONFIG && failFeatureWrite) ||
                    (type == CarLifeProtocol.HU_VIDEO_ENC_INIT && failInitWrite)) {
                    failed.countDown()
                    throw java.io.IOException("command write failed")
                }
                commands += type
                packets += bytes.copyOfRange(off, off + len)
                if (type == CarLifeProtocol.HU_VIDEO_ENC_INIT) initialized.countDown()
                super.write(bytes, off, len)
            }
        }
        val hu = CarLifeHuClient(
            log = { logs += it }, onVideoFrame = { _, _, _ -> }, orderedInit = ordered,
            requestedFps = 60,
            encoderInitClock = { now }, keyFrameWait = { now += it },
            featureDispatchWait = { waited += it; onWait?.invoke() },
        )
        init {
            running().set(true)
            setField("versionMatched", matched)
            setField("cmdOut", output)
        }
        fun setField(name: String, value: Any) = CarLifeHuClient::class.java
            .getDeclaredField(name).apply { isAccessible = true }.set(hu, value)
        private fun running() = CarLifeHuClient::class.java.getDeclaredField("running")
            .apply { isAccessible = true }.get(hu) as AtomicBoolean
        fun stop() { hu.stop() }
        fun initialize(force: Boolean = false) = CarLifeHuClient::class.java
            .getDeclaredMethod("sendEncoderInit", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(hu, force) as Boolean
        fun receive(type: Int, payload: ByteArray = ByteArray(0)) = CarLifeHuClient::class.java
            .getDeclaredMethod("handleMessage", Int::class.javaPrimitiveType, ByteArray::class.java)
            .apply { isAccessible = true }.invoke(hu, type, payload)
        fun waitForDispatch() = CarLifeHuClient::class.java.getDeclaredMethod("waitAfterFeatureConfig")
            .apply { isAccessible = true }.invoke(hu) as Boolean
        fun startAndAwaitInit() {
            receive(CarLifeProtocol.MD_INFO)
            awaitInit()
        }
        fun awaitInit() {
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

    @Test fun everyModeBlocksRecoveryAndForceUntilTheFirstFeatureIntervalEnds() {
        for (ordered in listOf(true, false)) {
            val h = Harness(ordered)
            h.onWait = {
                assertFalse(h.initialize(force = true))
                h.receive(CarLifeProtocol.VIDEO_ENC_DONE)
                assertFalse(h.hu.requestSourceKeyFrame { true })
                assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
            }
            try {
                assertFalse(h.initialize(force = true)) // handshake not ready yet
                h.startAndAwaitInit()
                assertEquals(if (ordered) 2_000L else 2_400L, h.waited)
                assertEquals(1, h.commands.count { it == CarLifeProtocol.HU_VIDEO_ENC_INIT })
                assertTrue(h.commands.indexOf(CarLifeProtocol.HU_FEATURE_CONFIG) <
                    h.commands.indexOf(CarLifeProtocol.HU_VIDEO_ENC_INIT))
            } finally { h.stop() }
        }
    }

    @Test fun deviceInfoBeforeVersionCannotStartEncodingAndVersionEventResumesIt() {
        val h = Harness(matched = false)
        try {
            h.receive(CarLifeProtocol.MD_INFO)
            h.receive(CarLifeProtocol.VIDEO_ENC_DONE)
            assertFalse(h.initialize(force = true))
            assertEquals(listOf(CarLifeProtocol.HU_INFO), h.commands)
            h.receive(CarLifeProtocol.MD_VERSION_MATCH, byteArrayOf(8, 1))
            h.awaitInit()
            assertEquals(1, h.commands.count { it == CarLifeProtocol.HU_VIDEO_ENC_INIT })
        } finally { h.stop() }
    }

    @Test fun earlyFeatureRequestIsAnsweredAfterVersionWithoutWaitingForDeviceInfo() {
        val h = Harness(matched = false)
        try {
            h.receive(CarLifeProtocol.MD_FEATURE_CONFIG)
            assertTrue(h.commands.isEmpty())
            h.receive(CarLifeProtocol.MD_VERSION_MATCH, byteArrayOf(8, 1))
            h.awaitInit()
            assertEquals(1, h.commands.count { it == CarLifeProtocol.HU_FEATURE_CONFIG })
            val packet = h.packets.single { ByteBuffer.wrap(it, 4, 4).int == CarLifeProtocol.HU_FEATURE_CONFIG }
            val payload = packet.copyOfRange(8, packet.size)
            assertArrayEquals(CarLifeProtocol.featureConfig(), payload)
            val config = CarLifeProtobuf.parse(payload)
            val items = config.items.filter { it.number == 2 && it.wire == 2 }
                .map { CarLifeProtobuf.parse(it.bytes!!) }
            assertEquals(items.size, config.int(1))
            val voiceMic = items.single { it.bytes(1)?.decodeToString() == "VOICE_MIC" }
            assertEquals(1, voiceMic.int(2))
        } finally { h.stop() }
    }

    @Test fun invalidVersionRepliesDoNotAdvanceNegotiation() {
        val h = Harness(matched = false)
        try {
            h.receive(CarLifeProtocol.MD_FEATURE_CONFIG)
            h.receive(0x10002)
            h.receive(0x10002, byteArrayOf(8, 1, 16))
            h.receive(0x10002, byteArrayOf(8, -127, -128, -128, -128, 16)) // 2^32 + 1
            assertTrue(h.commands.isEmpty())
            assertFalse(h.initialize(force = true))
            h.receive(0x10002, byteArrayOf(8, 2))
            assertFalse(h.hu.isRunning)
            assertTrue(h.commands.isEmpty())
            assertTrue(h.logs.any { it.contains("stopped reason=version-mismatch") })
        } finally { h.stop() }
    }

    @Test fun connectionExceptionLogsDocumentedReasonWithoutSendingCommands() {
        val h = Harness(matched = false)
        try {
            h.receive(0x10037, byteArrayOf(8, 1))
            h.receive(0x10037, byteArrayOf(8, 2))
            h.receive(0x10037, byteArrayOf(8, 3))
            assertEquals(3, h.logs.size)
            assertTrue(h.logs[0].contains("exceptionType=1）：手机端视频编码器初始化失败"))
            assertTrue(h.logs[1].contains("exceptionType=2）：用户拒绝屏幕共享权限"))
            assertTrue(h.logs[2].contains("exceptionType=3）：手机端正在请求屏幕共享权限"))
            assertTrue(h.commands.isEmpty())
            assertTrue(h.hu.isRunning)
        } finally { h.stop() }
    }

    @Test fun heartbeatWritesVideoHeaderAndHudContinuesOnCommandChannel() {
        val h = Harness(matched = false)
        try {
            @Suppress("UNCHECKED_CAST")
            val sockets = CarLifeHuClient::class.java.getDeclaredField("sockets")
                .apply { isAccessible = true }.get(h.hu) as MutableMap<Int, Socket>
            val tick = CarLifeHuClient::class.java.getDeclaredMethod("heartbeatTick", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val output = ByteArrayOutputStream()
            sockets[8240] = object : Socket() { override fun getOutputStream() = output }
            tick.invoke(h.hu, 1_000L)
            assertTrue(h.commands.isEmpty())
            assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 3, -24, 0, 2, 0, 2), output.toByteArray())

            h.receive(0x10002, byteArrayOf(8, 1))
            val broken = object : Socket() {
                override fun getOutputStream(): OutputStream = throw IOException("video closed")
            }
            sockets[8240] = broken
            tick.invoke(h.hu, 2_000L)
            assertTrue(broken.isClosed)
            assertTrue(h.hu.isRunning)
            assertEquals(listOf(CarLifeProtocol.HU_STATISTICS, CarLifeHudProtocol.SUBSCRIBE), h.commands)

            output.reset()
            sockets[8240] = object : Socket() { override fun getOutputStream() = output }
            tick.invoke(h.hu, 3_000L)
            assertEquals(12, output.size())
            assertEquals(0x20002, ByteBuffer.wrap(output.toByteArray(), 8, 4).int)
            assertFalse(h.commands.contains(CarLifeProtocol.HEARTBEAT))
        } finally { h.stop() }
    }

    @Test fun repeatedRequestsAndDeviceInfoDoNotRestartOrShortenTheFirstInterval() {
        val h = Harness()
        h.onWait = {
            h.receive(CarLifeProtocol.MD_FEATURE_CONFIG)
            h.receive(CarLifeProtocol.MD_INFO)
        }
        try {
            h.startAndAwaitInit()
            assertEquals(2_000L, h.waited)
            assertEquals(1, h.commands.count { it == CarLifeProtocol.HU_VIDEO_ENC_INIT })
            assertEquals(41, h.commands.count { it == CarLifeProtocol.HU_FEATURE_CONFIG })
            h.receive(CarLifeProtocol.MD_FEATURE_CONFIG) // late request still gets a response
            assertEquals(CarLifeProtocol.HU_FEATURE_CONFIG, h.commands.last())
            assertEquals(1, h.commands.count { it == CarLifeProtocol.HU_VIDEO_ENC_INIT })
        } finally { h.stop() }
    }

    @Test fun latestCarPlayCanvasIsUsedAtomicallyBeforeFirstInitWithoutEarlyResend() {
        val h = Harness()
        h.onWait = {
            h.hu.setRequestedResolution(1600, 720)
            assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
        }
        try {
            h.startAndAwaitInit()
            val packet = h.packets.single { ByteBuffer.wrap(it, 4, 4).int == CarLifeProtocol.HU_VIDEO_ENC_INIT }
            assertArrayEquals(CarLifeProtocol.videoEncoderInfo(1600, 720, 60, needAdapterScreen = true),
                packet.copyOfRange(8, packet.size))
        } finally { h.stop() }
    }

    @Test fun preconnectionUsesTheKnownCanvasWithoutAnyCarPlayEvent() {
        val h = Harness()
        try {
            h.startAndAwaitInit()
            val packet = h.packets.single { ByteBuffer.wrap(it, 4, 4).int == CarLifeProtocol.HU_VIDEO_ENC_INIT }
            assertArrayEquals(CarLifeProtocol.videoEncoderInfo(1412, 654, 60, needAdapterScreen = true),
                packet.copyOfRange(8, packet.size))
        } finally { h.stop() }
    }

    @Test fun failedFeatureWriteNeverSendsInitOrClaimsTheFeatureWasWritten() {
        val h = Harness()
        h.failFeatureWrite = true
        try {
            h.receive(CarLifeProtocol.MD_INFO)
            assertTrue(h.failed.await(5, TimeUnit.SECONDS))
            synchronized(h.hu) {
                assertFalse(h.initialize(force = true))
                assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
            }
        } finally { h.stop() }
    }

    @Test fun retiredFirstWorkerCannotWriteIntoANewerEpoch() {
        val h = Harness()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var worker: Thread? = null
        h.onWait = { worker = Thread.currentThread(); entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
        try {
            h.receive(CarLifeProtocol.MD_INFO)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.setField("initEpoch", Any())
            resume.countDown()
            worker!!.join(5_000)
            assertFalse(worker!!.isAlive)
            assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
        } finally { resume.countDown(); h.stop() }
    }

    @Test fun failedInitWriteDoesNotConsumeFirstInitOrTheDedupeWindow() {
        val h = Harness()
        h.failInitWrite = true
        try {
            h.receive(CarLifeProtocol.MD_INFO)
            assertTrue(h.failed.await(5, TimeUnit.SECONDS))
            synchronized(h.hu) {
                fun field(name: String) = CarLifeHuClient::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(h.hu)
                assertEquals(false, field("firstInitSent"))
                assertNull(field("lastEncInitSig"))
                assertEquals(0L, field("lastEncInitAt"))
                assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
            }
        } finally { h.stop() }
    }

    @Test fun stoppedFirstWorkerCannotSendInitAfterItsWaitReturns() {
        val h = Harness()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var worker: Thread? = null
        h.onWait = { worker = Thread.currentThread(); entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
        try {
            h.receive(CarLifeProtocol.MD_INFO)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.stop()
            resume.countDown()
            worker!!.join(5_000)
            assertFalse(worker!!.isAlive)
            assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
        } finally { resume.countDown(); h.stop() }
    }

    @Test fun interruptedFirstWorkerCannotSendInitAndPreservesItsInterrupt() {
        val h = Harness()
        val entered = CountDownLatch(1)
        var worker: Thread? = null
        h.onWait = { worker = Thread.currentThread(); entered.countDown(); throw InterruptedException() }
        try {
            h.receive(CarLifeProtocol.MD_INFO)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker!!.join(5_000)
            assertFalse(worker!!.isAlive)
            assertTrue(worker!!.isInterrupted)
            assertFalse(h.commands.contains(CarLifeProtocol.HU_VIDEO_ENC_INIT))
        } finally { h.stop() }
    }
}

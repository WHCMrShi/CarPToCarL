package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel
import io.github.shaonialife.carptocar.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 发送端会话对 mock 配件端的端到端回环测试：
 * 内存双工流两端各跑一个链路通道，mock 按 DiPlay/xcertplay 的配件侧时序应答，
 * 验证 Phase 1 全序列（识别→MFi 跳过→HID→WiFi 配置→CarPlay 启动→通知）。
 */
class Iap2SenderSessionTest {
    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 奥迪 MMI 0000 形态：识别帧只声明发 0x5703/0xea03（不发 0x4301），Phase 1
     * 因此不等待；车机在保活期间（Phase 1 之后）才补发 0x4301。
     * 旧实现把这条消息当 keep-alive 只记长度，配对永远不会重启——本用例锁住
     * 「晚到 0x4301 必须解析成端点并回调」这一行为。
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, audiDeclarations = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // 车机声明不发 0x4301：Phase 1 必须立即返回空端点，而不是白等 3s 窗口
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("晚到 0x4301 未触发端点回调", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * 凯迪拉克 CT5（GM/Cinemo，对应上游 cardeck PR #19）：新协议车机声明会发 0x4301，
     * 但窗口内未到——**不拆链**，返回空端点进入保活；保活泵把晚到的 0x4301 解析成
     * 完整端点经 onEndpointUpdate 回调（奥迪 MMI 9310 晚到 2 分 35 秒的同族形态）。
     *
     * 旧实现在这里抛 IOException 拆链重连，CT5 实测 6 轮循环车机始终静默、
     * 车机屏「无法连接到 CarPlay 请重试」——本用例锁住新的不拆链行为。
     */
    @Test
    fun newProtocolCarWithoutCredentialsKeepsLinkForLateStartSession() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, newProtocol = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:BE:EF:01",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val endpoint = session.run(timeoutMillis = 20_000L)
        // 没有任何 AP 信息，但车机声明会发 0x4301 —— 返回空端点而不是抛异常
        assertEquals("", endpoint.ssid)

        val pumpThread = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pumpThread.isDaemon = true
        pumpThread.start()
        assertTrue("晚到 0x4301 未在 10s 内经 onEndpointUpdate 回调", updateLatch.await(10, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals("secret-passphrase", late.passphrase)
        assertEquals(49152, late.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
        pumpThread.join(2_000)
    }

    /**
     * Audi MMI 0000 形态：0x4301 的地址字段按定长 NUL 填充（IPv6 后 22 个、IPv4 后 5 个）。
     * 旧实现只去掉**一个**尾部 NUL，得到 `"10.174.189.1\0\0\0\0\0"`——它 contains('.')=true、
     * contains(':')=false，能骗过所有「是不是 IPv4」的判断，但 Android 的 getaddrinfo 解不了，
     * 表现为 `Unable to resolve host "10.174.189.1"`（2026-10-18 实测：配对成功后视频端口
     * 与 keepAlive 全挂、会话 10 秒被拆）。
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // 每个地址都必须干净：没有 NUL、没有空白
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "地址含 NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "地址含首尾空白：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // 端口解析不受填充影响（同一帧里的 u32 参数）
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    @Test
    fun uniqueSubscriptionDuringMfiOrHidSurvivesHandshakeAndEarlyStopWins() {
        for (duringMfi in listOf(false, true)) {
            for (earlyStop in listOf(false, true)) {
                val pair = InMemoryStreamPair()
                val phone = Iap2LinkChannel(pair.sideA, name = "early-phone")
                val car = Iap2LinkChannel(pair.sideB, name = "early-car")
                val source = AtomicReference(CarPlayNowPlaying("song", "artist", null, 180_000, 2, 14_000, appName = "player"))
                val session = Iap2SenderSession(phone, {}, nowPlayingProvider = { source.get() })
                val failure = AtomicReference<Throwable>()
                val accessory = MockAccessory(car, onlyEarlyNowPlaying = true,
                    nowPlayingDuringMfi = duringMfi, stopEarlyNowPlaying = earlyStop)
                val mock = Thread {
                    try { accessory.run() } catch (error: Throwable) { failure.set(error) }
                }.apply { isDaemon = true; start() }
                var pump: Thread? = null
                val parser = Iap2Csm.Parser()
                fun receive(timeoutMs: Long): Iap2Csm.Frame? {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                    while (System.nanoTime() < deadline) {
                        val bytes = car.recvControl(20) ?: continue
                        parser.offer(bytes).firstOrNull { it.messageId == 0x5001 }?.let { return it }
                    }
                    return null
                }
                try {
                    session.run(10_000)
                    mock.join(3000)
                    assertTrue(!mock.isAlive)
                    failure.get()?.let { throw AssertionError("mock 握手失败", it) }
                    // 提前记账，但不得在握手中抢发 0x5001。
                    assertTrue(receive(50) == null)
                    pump = Thread { session.pumpIncomingForever() }.apply { isDaemon = true; start() }
                    if (earlyStop) {
                        assertTrue(receive(100) == null)
                        car.sendControl(Iap2Csm.encode(0x5000))
                    }
                    val first = receive(3000)
                    assertNotNull("唯一提前订阅不得丢失 stageMfi=$duringMfi earlyStop=$earlyStop", first)
                    val group = Iap2Csm.parseParams(first!!.body).single { it.id == 1 }
                    val params = Iap2Csm.parseParams(group.value)
                    assertEquals(14_000, java.nio.ByteBuffer.wrap(params.single { it.id == 1 }.value).int)
                    source.set(source.get().copy(elapsedMs = 15_000))
                    session.pushNowPlaying()
                    assertNotNull(receive(3000))
                } finally {
                    phone.close(); car.close()
                    mock.join(3000); pump?.join(3000)
                }
            }
        }
    }

    @Test fun bluetoothArtworkUsesNegotiatedFileSessionAndPausesWithoutRepeatingImage() {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "art-phone")
        val car = Iap2LinkChannel(pair.sideB, name = "art-car")
        io.github.shaonialife.carptocar.media.ArtworkRepository().use { repo ->
            val expected = ByteArray(3000) { it.toByte() }.also {
                it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte()
            }
            val source = repo.source("track", 1) { expected }
            source.request()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!source.request().complete && System.nanoTime() < end) Thread.sleep(5)
            val value = AtomicReference(CarPlayNowPlaying("song", "artist", "album", 180000, 2, 1000,
                appName = "player", artworkSource = source))
            val session = Iap2SenderSession(phone, {}, nowPlayingProvider = { value.get() })
            val failure = AtomicReference<Throwable>()
            val mock = Thread {
                try { MockAccessory(car).run() } catch (error: Throwable) { failure.set(error) }
            }.apply { isDaemon = true; start() }
            val complete = CountDownLatch(1)
            val received = java.io.ByteArrayOutputStream()
            val transfers = java.util.concurrent.atomic.AtomicInteger()
            car.onFileTransfer = { bytes ->
                if (bytes[1].toInt() == 4) {
                    transfers.incrementAndGet()
                    car.sendFileTransfer(byteArrayOf(bytes[0], 1))
                } else {
                    synchronized(received) { received.write(bytes, 2, bytes.size - 2) }
                    if (bytes[1].toInt() and 0x40 != 0) {
                        car.sendFileTransfer(byteArrayOf(bytes[0], 5))
                        complete.countDown()
                    }
                }
            }
            var pump: Thread? = null
            try {
                session.run(10000)
                mock.join(3000)
                failure.get()?.let { throw AssertionError("mock handshake failed", it) }
                pump = Thread { session.pumpIncomingForever() }.apply { isDaemon = true; start() }
                car.sendControl(Iap2Csm.encode(0x5000, Iap2Csm.group(0, Iap2Csm.param(26, ByteArray(0)))))
                assertTrue(complete.await(5, TimeUnit.SECONDS))
                org.junit.Assert.assertArrayEquals(expected, synchronized(received) { received.toByteArray() })
                value.set(value.get().copy(elapsedMs = 2000))
                session.pushNowPlaying()
                // Confirm the progress update reached the peer before checking transfer count.
                val parser = Iap2Csm.Parser()
                var progressArrived = false
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!progressArrived && System.nanoTime() < limit) {
                    car.recvControl(20)?.let { bytes ->
                        for (frame in parser.offer(bytes).filter { it.messageId == 0x5001 }) {
                            val playback = Iap2Csm.parseParams(frame.body).single { it.id == 1 }
                            val elapsed = Iap2Csm.parseParams(playback.value).single { it.id == 1 }
                            progressArrived = java.nio.ByteBuffer.wrap(elapsed.value).int == 2000
                        }
                    }
                }
                assertTrue(progressArrived)
                assertEquals(1, transfers.get())
                val waiting = CountDownLatch(1)
                val cancelled = CountDownLatch(1)
                val transferId = java.util.concurrent.atomic.AtomicInteger(-1)
                car.onFileTransfer = { bytes ->
                    when (bytes[1].toInt() and 255) {
                        4 -> { transferId.set(bytes[0].toInt() and 255); waiting.countDown() }
                        2 -> if ((bytes[0].toInt() and 255) == transferId.get()) cancelled.countDown()
                    }
                }
                car.sendControl(Iap2Csm.encode(0x5000, Iap2Csm.group(0, Iap2Csm.param(26, ByteArray(0)))))
                assertTrue(waiting.await(3, TimeUnit.SECONDS)) // transfer remains active: no START reply
                car.sendControl(Iap2Csm.encode(0x5002))
                assertTrue("STOP must send file CANCEL even after dispatcher unsubscribe", cancelled.await(3, TimeUnit.SECONDS))
            } finally {
                phone.close(); car.close(); mock.join(3000); pump?.join(3000)
            }
        }
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /** 奥迪形态：识别帧声明只发 0x5703/0xea03（不发 StartHID/0x4301）。 */
        private val audiDeclarations: Boolean = false,
        /** Phase 1 结束后再补发 0x4301（车机窗口晚开的真实时序）。 */
        private val lateStartSession: Boolean = false,
        /**
         * 地址字段按真实车机的定长填充（Audi MMI 0000 实测：IPv6 后跟 22 个 NUL、
         * IPv4 后跟 5 个）。用于锁定「必须去掉全部尾部 NUL」这一行为。
         */
        private val padAddresses: Boolean = false,
        /**
         * 凯迪拉克 CT5（GM/Cinemo）形态：清单完整但无 0x5702/0x5703，
         * 且应等到 Phase 1 结束（保活期）才补发 0x4301。
         */
        private val newProtocol: Boolean = false,
        private val onlyEarlyNowPlaying: Boolean = false,
        private val nowPlayingDuringMfi: Boolean = false,
        private val stopEarlyNowPlaying: Boolean = false,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        /** 收到过的「车机可收清单未声明」的消息（证明只记录不拦截）。 */
        private val seenUndeclared = HashSet<Int>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, "MockCarPlay"),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                if (audiDeclarations) {
                    Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03))
                } else if (newProtocol) {
                    // 凯迪拉克 CT5（GM/Cinemo）实测清单：发 11 项含 0x4301、收 9 项，
                    // 两个清单都不含 0x57xx —— AP 信息只能在 0x4301 里
                    Iap2Csm.u16List(
                        6,
                        intArrayOf(0xaa06, 0x6800, 0x6802, 0x6803, 0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x5000, 0x5002, 0x4301),
                    ) +
                        Iap2Csm.u16List(
                            7,
                            intArrayOf(0x6801, 0x4c01, 0x4c04, 0x5001, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4300),
                        )
                } else {
                    ByteArray(0)
                },
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            if (onlyEarlyNowPlaying && nowPlayingDuringMfi) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                if (stopEarlyNowPlaying) send(Iap2MessageIds.STOP_NOW_PLAYING_UPDATES)
            }
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // 设备状态问候**紧跟 0xAA05**（2026-10-26 起；上游 PR #17 的结论：
            // 车机据这批消息判定发送端已就绪，晚发会被当成还没起来）。
            // 2026-10-27 起取消「按声明裁剪」：0x4E0D 即使不在可收清单里也照发
            // （清单只能证明「有」不能证明「没有」，见 Iap2SenderSession.recordUndeclaredReceive）。
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)

            // 订阅 + StartHID（奥迪形态不发，发送端应直接跳过等待）
            if (!audiDeclarations) {
                if (!onlyEarlyNowPlaying || !nowPlayingDuringMfi) {
                    send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                    if (stopEarlyNowPlaying) send(Iap2MessageIds.STOP_NOW_PLAYING_UPDATES)
                }
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi 配置：GM/Cinemo 形态的清单里没有 0x5702，发送端因此根本不会发它
            if (!newProtocol) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
            }
            // 迟到一条订阅请求（真实车机 bring-up 常见：0x5000 与 0x5703 相邻到达）。
            // 它必须被暂存而不是在 expectOrNull 里被反复重读——旧实现把非预期帧塞回
            // 「自己下一轮优先读取」的队列，同一帧无限循环（2026-10-18 日志刷屏根因）。
            if (!onlyEarlyNowPlaying) send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
            // GM/Cinemo 形态：没有凭据可发（0x4301 才是唯一来源）
            if (!newProtocol) {
                send(
                    Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                    Iap2Csm.string(1, "MockAP"),
                    Iap2Csm.string(2, "secret-passphrase"),
                    Iap2Csm.u8(3, 2),
                    Iap2Csm.u8(4, 36),
                )
            }

            // CarPlay 启动（问候已在 0xAA05 之后收过，此处不再重复期望）
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            if (!lateStartSession) {
                sendStartSession()
            }

            if (newProtocol) {
                // 凯迪拉克的可收清单里没有 0x4E0D（无线可用）与 0x4E0E（传输标识），
                // 但 2026-10-27 起**不再按声明裁剪**（清单只能证明「有」不能证明「没有」，
                // 上游 0.1.28 奥迪 3315 定谳）：这两条必须照发，只留一条日志。
                // 窗口给够 8.6 秒（> 新协议 0x4301 的 8s 等待窗），期间把收到的帧都记下来，
                // 窗口过后再补发 0x4301——这样才真正落在「Phase 1 已结束、晚到补发」的时序上。
                assertUndeclaredFramesStillSent(8_600)
                sendStartSession()
            } else {
                expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
                if (lateStartSession) {
                    // 车机打开 CarPlay 窗口后补发（Phase 1 早已结束）
                    Thread.sleep(150)
                    sendStartSession()
                }
            }
        }

        /**
         * 断言「车机声明不能收」的消息仍然照发（只记录不拦截）。
         *
         * 当前唯一来源是凯迪拉克 CT5（GM/Cinemo）的可收清单
         * `0x6801 0x4c01 0x4c04 0x5001 0x4e09 0x4e0a 0x4e0b 0x4e0c 0x4300`——
         * 其中没有 0x4E0D（无线可用）与 0x4E0E（传输标识）。
         * 2026-10-27 起这两条**必须照发**：上游 0.1.28 的真车证据（奥迪 3315 清单只有
         * 5 项、不含 0x5702 却完整支持）证明「没声明 ≠ 不能收」。
         */
        private fun assertUndeclaredFramesStillSent(windowMillis: Long) {
            val deadlineAt = System.nanoTime() + windowMillis * 1_000_000L
            while (true) {
                val remaining = (deadlineAt - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) break
                recvFrame(remaining) ?: continue
            }
            val missing = UNDECLARED_FOR_CADILLAC - seenUndeclared
            check(missing.isEmpty()) {
                "取消裁剪后这些消息仍应照发，但没等到：" +
                    missing.joinToString(" ") { "0x%04x".format(it) } +
                    "（实际收到 " + seenUndeclared.joinToString(" ") { "0x%04x".format(it) } + "）"
            }
        }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** 地址参数：真实车机按定长 NUL 填充；默认（不填充）与之前行为一致。 */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // 至少保留一个 NUL 结尾（CSM 字符串语义），其余补到固定宽度
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        /**
         * 期望收到 [messageId]。
         *
         * 允许其间穿插「设备状态问候 / 传输标识 / 可用性」这类**可随时补发**的消息：
         * 2026-10-26 起问候紧跟 `0xAA05` 发送（上游 PR #17 的结论），而等待 0x4301
         * 期间还会周期性催问——两者都会打乱「严格一问一答」的脚本。这里改成
         * 「跳过非目标消息」并记数，跳太多仍然判定失败（防止真的抓错帧）。
         */
        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            val deadlineAt = System.nanoTime() + 10_000_000_000L
            var skipped = 0
            while (System.nanoTime() < deadlineAt) {
                val frame =
                    recvFrame(10_000)
                        ?: error("mock timed out waiting 0x${messageId.toString(16)}")
                if (frame.messageId == messageId) {
                    println("MOCK got 0x${frame.messageId.toString(16)}")
                    return
                }
                if (frame.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += frame.messageId
                skipped++
                println("MOCK skip 0x${frame.messageId.toString(16)}（在等 0x${messageId.toString(16)}）")
                check(skipped <= 64) {
                    "mock 等 0x${messageId.toString(16)} 期间跳过了 $skipped 条消息——脚本可能已错位"
                }
            }
            error("mock timed out waiting 0x${messageId.toString(16)}")
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                if (it.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += it.messageId
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            frames.first().let { if (it.messageId in UNDECLARED_FOR_CADILLAC) seenUndeclared += it.messageId }
            return frames.first()
        }

        private companion object {
            /** 凯迪拉克 CT5 可收清单里缺的两条（见 [assertUndeclaredFramesStillSent]）。 */
            val UNDECLARED_FOR_CADILLAC =
                setOf(
                    Iap2MessageIds.WIRELESS_CARPLAY_UPDATE,
                    Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER,
                )
        }
    }
}

package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AirPlayLifecycleTest {
    private fun client(port: Int): AirPlayClient =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, port, null, null, null, listOf("127.0.0.1")),
            {},
        )

    private fun connect(client: AirPlayClient): Boolean {
        val method = AirPlayClient::class.java.getDeclaredMethod("ensureConnected", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(client, 2000) as Boolean
    }

    private fun executor(client: AirPlayClient): ExecutorService {
        val field = AirPlayClient::class.java.getDeclaredField("controlIapTx")
        field.isAccessible = true
        return field.get(client) as ExecutorService
    }

    private fun setup(port: Int) =
        AirPlayClient.SetupInfo(
            port,
            0,
            null,
            emptyList(),
            1412,
            654,
            displayUuid = null,
            maxFps = 60,
        )

    private fun assertPeerClosed(peer: java.net.Socket) {
        peer.soTimeout = 2000
        try {
            assertEquals(-1, peer.getInputStream().read())
        } catch (_: java.net.SocketException) {
            // 取消含未读数据的连接允许返回 RST。
        }
    }

    private fun timingServer(client: AirPlayClient): TimingServer {
        val field = AirPlayClient::class.java.getDeclaredField("timingServer")
        field.isAccessible = true
        return field.get(client) as TimingServer
    }

    @Test fun disposeClosesTimingSocketAndReaderButOrdinaryClosePreservesThem() {
        val client = client(1)
        val timing = timingServer(client)
        val previousThreads = Thread.getAllStackTraces().keys
        try {
            // 未连接控制端口时 SETUP 返回 null，但仍经过真实的计时服务启动入口。
            assertTrue(client.runSetup() == null)
            val field = TimingServer::class.java.getDeclaredField("socket").apply { isAccessible = true }
            val socket = field.get(timing) as java.net.DatagramSocket
            val reader = Thread.getAllStackTraces().keys.single {
                it.name == "airplay-timing" && it !in previousThreads
            }
            val port = timing.port
            assertTrue(port > 0)
            client.close()
            assertEquals(port, timing.port)
            assertFalse(socket.isClosed)
            client.dispose()
            client.dispose()
            reader.join(3000)
            assertTrue(socket.isClosed)
            assertFalse(reader.isAlive)
            assertEquals(0, timing.port)
            assertTrue(client.runSetup() == null)
            assertEquals(0, timing.port)
        } finally {
            client.dispose()
        }
    }

    @Test fun setupRacingDisposeCannotLeaveTimingServiceRunning() {
        repeat(30) {
            val client = client(1)
            val gate = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val setup = Thread {
                try {
                    gate.await()
                    client.runSetup()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            try {
                setup.start()
                gate.countDown()
                client.dispose()
                setup.join(3000)
                assertFalse(setup.isAlive)
                failure.get()?.let { throw AssertionError("SETUP/退役竞争异常", it) }
                assertEquals(0, timingServer(client).port)
            } finally {
                client.dispose()
                setup.join(3000)
            }
        }
    }

    @Test fun ordinaryCloseAllowsRetryButDisposePermanentlyStopsExecutorAndReconnect() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val client = client(server.localPort)
            try {
                assertTrue(connect(client))
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    client.close()
                    assertPeerClosed(peer)
                    assertFalse(executor(client).isShutdown)
                }
                assertTrue(connect(client))
                server.accept().use { peer ->
                    peer.soTimeout = 2000
                    client.dispose()
                    assertPeerClosed(peer)
                }
                assertTrue(executor(client).isShutdown)
                assertFalse(connect(client))
                client.postControlIap(byteArrayOf(1)) // 终态晚到的消息不抛 RejectedExecutionException。
            } finally {
                client.dispose()
            }
        }
    }

    @Test fun eventSocketIsClosedAndReplacementConnectionReceivesNewEvents() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { control ->
            ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { events ->
                val client = client(control.localPort)
                val oldCalls = AtomicInteger()
                val received = CountDownLatch(1)
                try {
                    assertTrue(connect(client))
                    control.accept().use { oldControl ->
                        assertTrue(client.connectEventChannel(null, setup(events.localPort)) { oldCalls.incrementAndGet() })
                        events.accept().use { oldEvent ->
                            oldEvent.soTimeout = 2000
                            oldEvent.getOutputStream().write("POST /command RTSP/1.0\r\nContent-Length: 10\r\n\r\nabc".toByteArray())
                            client.close()
                            assertPeerClosed(oldEvent)
                            assertEquals(0, oldCalls.get())
                        }
                        oldControl.soTimeout = 2000
                        assertPeerClosed(oldControl)
                    }
                    assertTrue(connect(client))
                    control.accept().use {
                        assertTrue(
                            client.connectEventChannel(null, setup(events.localPort)) {
                                if (it["type"] ==
                                    "test"
                                ) {
                                    received.countDown()
                                }
                            },
                        )
                        events.accept().use { fresh ->
                            val body = BinaryPlist.encode(linkedMapOf<String, Any?>("type" to "test"))
                            fresh.getOutputStream().write(
                                "POST /command RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body,
                            )
                            assertTrue(received.await(3, TimeUnit.SECONDS))
                            assertEquals(0, oldCalls.get())
                        }
                    }
                } finally {
                    client.dispose()
                }
            }
        }
    }

    @Test fun reconnectCannotPublishWhilePreviousCloseIsStillCleaningResources() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val client = client(server.localPort)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var closer: Thread? = null
            try {
                assertTrue(connect(client))
                server.accept().use { peer ->
                    val field = AirPlayClient::class.java.getDeclaredField("resources")
                    field.isAccessible = true
                    val scope = field.get(client) as io.github.shaonialife.carptocar.util.ConnectionResources
                    scope.own(
                        AutoCloseable {
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                        },
                    )
                    closer = Thread { client.close() }.also { it.start() }
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    assertFalse(connect(client))
                    release.countDown()
                    closer!!.join(3000)
                    assertFalse(closer!!.isAlive)
                    assertPeerClosed(peer)
                }
                assertTrue(connect(client))
                server.accept().use { peer ->
                    client.close()
                    assertPeerClosed(peer)
                }
            } finally {
                release.countDown()
                closer?.join(3000)
                client.dispose()
            }
        }
    }

    @Test fun controlEofRetiresItsWorkersAndSocketButStillAllowsPairingRetry() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val client = client(server.localPort)
            try {
                assertTrue(connect(client))
                val socketField = AirPlayClient::class.java.getDeclaredField("socket").apply { isAccessible = true }
                val readerField = AirPlayClient::class.java.getDeclaredField("readerThread").apply { isAccessible = true }
                val start =
                    AirPlayClient::class.java
                        .getDeclaredMethod("startReader", java.net.Socket::class.java)
                        .apply { isAccessible = true }
                val peer = server.accept()
                start.invoke(client, socketField.get(client))
                val reader = readerField.get(client) as Thread
                peer.close()
                reader.join(3000)
                assertFalse(reader.isAlive)
                assertTrue(socketField.get(client) == null)
                assertFalse(executor(client).isShutdown)
                assertTrue(connect(client))
                server.accept().use { replacement ->
                    client.close()
                    assertPeerClosed(replacement)
                }
            } finally {
                client.dispose()
            }
        }
    }
}

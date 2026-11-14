package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.bt.BtLinkOwnership
import io.github.shaonialife.carptocar.bt.BtLinkRecovery
import io.github.shaonialife.carptocar.util.ConnectionResources
import io.github.shaonialife.carptocar.util.DeviceIdentity
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DisableBluetoothTest {
    private class Fixture : AutoCloseable {
        val server = ServerSocket(0)
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val responseLogged = CountDownLatch(1)
        val requestLogged = CountDownLatch(1)
        val requestQueued = CountDownLatch(1)
        val gateChanged = CountDownLatch(1)
        val requestFailed = CountDownLatch(1)
        val client = AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, server.localPort, null, null, null, listOf("127.0.0.1")),
            { logs += it
                if (it.startsWith("BT-DISABLE: response")) responseLogged.countDown()
                if (it.startsWith("BT-DISABLE: request generation")) requestLogged.countDown()
                if (it.startsWith("BT-DISABLE: queued")) requestQueued.countDown()
                if (it.contains("readiness-changed-before-write")) gateChanged.countDown()
                if (it.startsWith("BT-DISABLE: request failed")) requestFailed.countDown()
            },
            requestClock = { System.nanoTime() / 1_000_000 },
        )
        @Volatile var sourceLive = true
        @Volatile var sourceIdentity = Any()
        @Volatile var workCurrent = true
        @Volatile var controlReady = true
        var handoffRegistrations = 0
        fun arm(record: Boolean = true, ready: Boolean = true) {
            client.disableBluetoothAfterActivation(
                record,
                source = {
                    val identity = sourceIdentity
                    if (!sourceLive) null else ({ sourceLive && sourceIdentity === identity })
                },
                workCurrent = { workCurrent },
                beforeSend = { handoffRegistrations++ },
                iap2Ready = { ready && controlReady },
            )
        }
        fun awaitWorker(session: AirPlayClient.BluetoothDisableSession) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (session.queued.get() && System.nanoTime() < end) Thread.sleep(5)
            assertFalse("handoff worker must finish", session.queued.get())
        }
        var peer = connect()
        fun connect(): Socket {
            val method = AirPlayClient::class.java.getDeclaredMethod("ensureConnected", Int::class.javaPrimitiveType)
            method.isAccessible = true
            assertEquals(true, method.invoke(client, 2000))
            return server.accept().apply { soTimeout = 2000 }
        }
        fun field(name: String): Any? = AirPlayClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)
        fun session(id: String = "AA:BB:CC:11:22:33", advertised: Boolean = true): AirPlayClient.BluetoothDisableSession {
            val session = AirPlayClient.BluetoothDisableSession(
                id, advertised, field("lifecycleGeneration") as Long, field("resources") as ConnectionResources,
            )
            AirPlayClient::class.java.getDeclaredField("bluetoothDisableSession").apply { isAccessible = true }.set(client, session)
            return session
        }
        fun request(): Pair<String, Map<*, *>> {
            val input = peer.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0) { "unexpected EOF" }
                head.append(byte.toChar())
            }
            val length = Regex("Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
            return head.toString() to (if (length == 0) emptyMap<Any, Any>() else BinaryPlist.parse(input.readNBytes(length)) as Map<*, *>)
        }
        fun respondRaw(head: String, status: String = "200 OK") {
            val cseq = Regex("CSeq: (\\d+)").find(head)!!.groupValues[1]
            peer.getOutputStream().write("RTSP/1.0 $status\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        fun respond(head: String, status: String = "200 OK") {
            respondRaw(head, status)
            assertTrue(responseLogged.await(3, TimeUnit.SECONDS))
        }
        fun assertNoRequest() {
            peer.soTimeout = 150
            assertThrows(SocketTimeoutException::class.java) { peer.getInputStream().read() }
            peer.soTimeout = 2000
        }
        override fun close() { client.dispose(); peer.close(); server.close() }
    }

    @Test fun sendsLocalSetupIdentityOnControlChannelOnlyOnce() = Fixture().use { f ->
        val session = f.session()
        f.arm(true, true)
        val (head, command) = f.request()
        assertFalse(f.client.bluetoothDisableAccepted)
        assertTrue(head.startsWith("POST /command RTSP/1.0\r\n"))
        assertTrue(head.contains("Content-Type: application/x-apple-binary-plist"))
        assertEquals(setOf("type", "params"), command.keys)
        assertEquals("disableBluetooth", command["type"])
        // DiPlay handleCommand reads only body["params"]; TROOPERS24 slide 32
        // independently shows the phone sending deviceID inside that dictionary.
        val params = command["params"] as? Map<*, *>
        assertNotNull("receiver must be able to read command parameters", params)
        assertEquals(mapOf("deviceID" to session.deviceId), params)
        f.respond(head)
        assertTrue(f.client.bluetoothDisableAccepted)
        repeat(3) { f.arm(true, true) }
        f.assertNoRequest()
        assertTrue(f.logs.any { it.contains("accepted=true") && it.contains("disconnection unconfirmed") })
    }

    @Test fun rejectedResponseIsNotSuccessAndDoesNotRetry() = Fixture().use { f ->
        f.session()
        f.arm(true, true)
        val (head, _) = f.request()
        f.respond(head, "422 Unprocessable Entity")
        assertFalse(f.client.bluetoothDisableAccepted)
        f.arm(true, true)
        f.assertNoRequest()
        assertTrue(f.logs.any { it.contains("accepted=false") })
        assertTrue(f.logs.any { it.contains("422 Unprocessable Entity") && it.contains("disconnection unconfirmed") })
    }

    @Test fun readinessRequiresBothActivationAndWifiIap2() = Fixture().use { f ->
        val session = f.session()
        f.arm(false, true)
        f.arm(true, false)
        assertFalse(f.client.bluetoothDisableAccepted)
        assertFalse(session.attempted.get())
        f.assertNoRequest()
        f.arm(true, true)
        f.respond(f.request().first)
    }

    @Test fun carPlayFirstKeepsBluetoothUntilLateSourceOnExistingFeedbackLoop() = Fixture().use { f ->
        val session = f.session()
        f.sourceLive = false
        f.arm()
        f.assertNoRequest()
        assertFalse(session.attempted.get())
        assertEquals(0, f.handoffRegistrations)
        // Real heartbeat drives the late-source check; no second activation call.
        f.client.startFeedbackLoop()
        val initial = f.request()
        assertTrue(initial.first.startsWith("POST /feedback "))
        f.respondRaw(initial.first)
        f.sourceLive = true
        val paths = mutableListOf<String>()
        repeat(2) {
            val (head, _) = f.request()
            paths += head.lineSequence().first()
            f.respondRaw(head)
        }
        assertTrue(paths.any { it.startsWith("POST /command ") })
        assertTrue(paths.any { it.startsWith("POST /feedback ") })
        assertTrue(f.responseLogged.await(3, TimeUnit.SECONDS))
        assertTrue(f.client.bluetoothDisableAccepted)
        assertEquals(1, f.handoffRegistrations)
    }

    @Test fun carLifeFirstWaitsForLateControlWithoutRearming() = Fixture().use { f ->
        val session = f.session()
        f.controlReady = false
        f.arm()
        f.assertNoRequest()
        assertFalse(session.attempted.get())
        f.controlReady = true
        f.client.evaluateBluetoothDisable()
        f.respond(f.request().first)
        repeat(10) { f.client.evaluateBluetoothDisable() }
        f.assertNoRequest()
        assertEquals(1, f.handoffRegistrations)
    }

    @Test fun queuedSourceReplacementCannotUseOldLeaseOrSpendAttempt() = Fixture().use { f ->
        val session = f.session()
        synchronized(f.field("rtspWriteLock")!!) {
            f.arm()
            assertTrue(f.requestQueued.await(3, TimeUnit.SECONDS))
            f.sourceIdentity = Any()
            f.sourceLive = false
        }
        assertTrue(f.gateChanged.await(3, TimeUnit.SECONDS))
        f.awaitWorker(session)
        f.assertNoRequest()
        assertFalse(session.attempted.get())
        assertEquals(0, f.handoffRegistrations)
        f.sourceLive = true
        f.client.evaluateBluetoothDisable()
        f.respond(f.request().first)
        assertEquals(1, f.handoffRegistrations)
    }

    @Test fun cancellationWhileQueuedCannotSendOrRegisterHandoff() = Fixture().use { f ->
        val session = f.session()
        synchronized(f.field("rtspWriteLock")!!) {
            f.arm()
            assertTrue(f.requestQueued.await(3, TimeUnit.SECONDS))
            f.workCurrent = false
        }
        f.awaitWorker(session)
        f.assertNoRequest()
        assertFalse(session.attempted.get())
        assertEquals(0, f.handoffRegistrations)
    }

    @Test fun controlLossWhileQueuedDefersUntilReadyAgain() = Fixture().use { f ->
        val session = f.session()
        synchronized(f.field("rtspWriteLock")!!) {
            f.arm()
            assertTrue(f.requestQueued.await(3, TimeUnit.SECONDS))
            f.controlReady = false
        }
        f.awaitWorker(session)
        f.assertNoRequest()
        assertFalse(session.attempted.get())
        assertEquals(0, f.handoffRegistrations)
        f.controlReady = true
        f.client.evaluateBluetoothDisable()
        f.respond(f.request().first)
        assertEquals(1, f.handoffRegistrations)
    }

    @Test fun replacedSetupOnSameSocketRejectsQueuedOldWork() = Fixture().use { f ->
        val old = f.session()
        synchronized(f.field("rtspWriteLock")!!) {
            f.arm()
            assertTrue(f.requestQueued.await(3, TimeUnit.SECONDS))
            f.session()
        }
        f.awaitWorker(old)
        f.assertNoRequest()
        assertFalse(old.attempted.get())
        f.arm()
        f.respond(f.request().first)
        assertEquals(1, f.handoffRegistrations)
    }

    @Test fun missingCapabilityOrPlaceholderAddressCannotSend() = Fixture().use { f ->
        for ((id, advertised) in listOf("AA:BB:CC:11:22:33" to false, DeviceIdentity.PLACEHOLDER_MAC to true, "unknown" to true)) {
            val session = f.session(id, advertised)
            f.arm(true, true)
            assertFalse(session.attempted.get())
        }
        f.assertNoRequest()
    }

    @Test fun retiredSessionCannotSendOnReplacementButNewSessionCan() = Fixture().use { f ->
        val old = f.session()
        f.client.close()
        f.peer.close()
        f.peer = f.connect()
        f.arm(true, true)
        assertFalse(old.attempted.get())
        f.assertNoRequest()
        f.session("AA:BB:CC:44:55:66")
        f.arm(true, true)
        val (head, command) = f.request()
        assertEquals("AA:BB:CC:44:55:66", (command["params"] as? Map<*, *>)?.get("deviceID"))
        f.respond(head)
    }

    @Test fun queuedOldRequestCannotCrossIntoNewControlConnection() = Fixture().use { f ->
        f.session()
        synchronized(f.field("rtspWriteLock")!!) {
            f.arm(true, true)
            assertTrue(f.requestQueued.await(3, TimeUnit.SECONDS))
            f.client.close()
            f.peer.close()
            f.peer = f.connect()
        }
        f.assertNoRequest()
    }

    @Test fun acceptedStateExpiresWhenControlConnectionCloses() = Fixture().use { f ->
        f.session()
        f.arm(true, true)
        f.respond(f.request().first)
        assertTrue(f.client.bluetoothDisableAccepted)
        f.client.close()
        assertFalse(f.client.bluetoothDisableAccepted)
        f.peer.close()
        f.peer = f.connect()
        f.session()
        assertFalse(f.client.bluetoothDisableAccepted)
    }

    @Test fun lateResponseCannotAcceptReplacementSetupOnSameConnection() = Fixture().use { f ->
        f.session()
        f.arm(true, true)
        val (head, _) = f.request()
        f.session()
        f.respond(head)
        assertFalse(f.client.bluetoothDisableAccepted)
    }

    @Test fun controlFailureDoesNotAcceptHandoff() = Fixture().use { f ->
        f.session()
        f.arm(true, true)
        f.request()
        f.peer.close()
        assertTrue(f.requestFailed.await(3, TimeUnit.SECONDS))
        assertFalse(f.client.bluetoothDisableAccepted)
    }

    @Test fun missingResponseTimesOutWithoutAcceptingOrRetrying() = Fixture().use { f ->
        f.session()
        f.arm(true, true)
        f.request()
        assertTrue(f.requestFailed.await(12, TimeUnit.SECONDS))
        assertTrue(f.logs.any { it.startsWith("BT-DISABLE: request failed") && it.contains("RTSP 超时") })
        assertFalse(f.client.bluetoothDisableAccepted)
        f.arm(true, true)
        f.assertNoRequest()
    }

    @Test fun bluetoothCloseBeforeRealResponseStopsRecoveryAfter200Only() = Fixture().use { f ->
        val links = BtLinkOwnership { 1_000L }
        val recovery = BtLinkRecovery({ 1_000L }, 180_000L)
        val owner = links.claim("outbound")!!
        recovery.expectHandoff(owner) { f.client.bluetoothDisableAccepted }
        f.session()
        f.arm(true, true)
        val (head, _) = f.request()
        owner.finish()
        recovery.noteLost("car", owner)
        assertEquals("car", recovery.target(true))
        f.respond(head)
        assertNull(recovery.target(true))
        f.client.close()
        assertEquals("car", recovery.target(true))
    }
}

package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
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
        val client = AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint("test", "", 0, 0, server.localPort, null, null, null, listOf("127.0.0.1")),
            { logs += it
                if (it.startsWith("BT-DISABLE: response")) responseLogged.countDown()
                if (it.startsWith("BT-DISABLE: request generation")) requestLogged.countDown()
            },
            requestClock = { System.nanoTime() / 1_000_000 },
        )
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
            return head.toString() to (BinaryPlist.parse(input.readNBytes(length)) as Map<*, *>)
        }
        fun respond(head: String, status: String = "200 OK") {
            val cseq = Regex("CSeq: (\\d+)").find(head)!!.groupValues[1]
            peer.getOutputStream().write("RTSP/1.0 $status\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray())
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
        f.client.disableBluetoothAfterActivation(true, true)
        val (head, command) = f.request()
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
        repeat(3) { f.client.disableBluetoothAfterActivation(true, true) }
        f.assertNoRequest()
        assertTrue(f.logs.any { it.contains("accepted=true") && it.contains("disconnection unconfirmed") })
    }

    @Test fun rejectedResponseIsNotSuccessAndDoesNotRetry() = Fixture().use { f ->
        f.session()
        f.client.disableBluetoothAfterActivation(true, true)
        val (head, _) = f.request()
        f.respond(head, "422 Unprocessable Entity")
        f.client.disableBluetoothAfterActivation(true, true)
        f.assertNoRequest()
        assertTrue(f.logs.any { it.contains("accepted=false") })
        assertTrue(f.logs.any { it.contains("422 Unprocessable Entity") && it.contains("disconnection unconfirmed") })
    }

    @Test fun readinessRequiresBothActivationAndWifiIap2() = Fixture().use { f ->
        val session = f.session()
        f.client.disableBluetoothAfterActivation(false, true)
        f.client.disableBluetoothAfterActivation(true, false)
        assertFalse(session.attempted.get())
        f.assertNoRequest()
        f.client.disableBluetoothAfterActivation(true, true)
        f.respond(f.request().first)
    }

    @Test fun missingCapabilityOrPlaceholderAddressCannotSend() = Fixture().use { f ->
        for ((id, advertised) in listOf("AA:BB:CC:11:22:33" to false, DeviceIdentity.PLACEHOLDER_MAC to true, "unknown" to true)) {
            val session = f.session(id, advertised)
            f.client.disableBluetoothAfterActivation(true, true)
            assertFalse(session.attempted.get())
        }
        f.assertNoRequest()
    }

    @Test fun retiredSessionCannotSendOnReplacementButNewSessionCan() = Fixture().use { f ->
        val old = f.session()
        f.client.close()
        f.peer.close()
        f.peer = f.connect()
        f.client.disableBluetoothAfterActivation(true, true)
        assertFalse(old.attempted.get())
        f.assertNoRequest()
        f.session("AA:BB:CC:44:55:66")
        f.client.disableBluetoothAfterActivation(true, true)
        val (head, command) = f.request()
        assertEquals("AA:BB:CC:44:55:66", (command["params"] as? Map<*, *>)?.get("deviceID"))
        f.respond(head)
    }

    @Test fun queuedOldRequestCannotCrossIntoNewControlConnection() = Fixture().use { f ->
        f.session()
        synchronized(f.field("rtspWriteLock")!!) {
            f.client.disableBluetoothAfterActivation(true, true)
            assertTrue(f.requestLogged.await(3, TimeUnit.SECONDS))
            f.client.close()
            f.peer.close()
            f.peer = f.connect()
        }
        f.assertNoRequest()
    }
}

package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession.CarPlayEndpoint
import io.github.shaonialife.carptocar.net.AirPlayPortSource
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket

class AirPlayConnectionDiagnosticsTest {
    private fun connect(client: AirPlayClient): Boolean = AirPlayClient::class.java
        .getDeclaredMethod("ensureConnected", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(client, 1000) as Boolean

    @Test fun successfulSocketReportsActualPortAndItsSource() {
        ServerSocket(0).use { server ->
            val logs = mutableListOf<String>()
            val endpoint = CarPlayEndpoint("test", "", 0, 0, server.localPort, null, null, null,
                listOf("127.0.0.1"), airPlayPortSource = AirPlayPortSource.MDNS)
            val client = AirPlayClient(endpoint, logs::add)
            try {
                assertTrue(connect(client))
                server.accept().use {
                    assertTrue(logs.any { it.contains(":${server.localPort}") && it.contains("portSource=MDNS") })
                }
            } finally { client.dispose() }
        }
    }

    @Test fun refusedSocketReportsCandidateWithoutClaimingWholeServiceIsAbsent() {
        val port = ServerSocket(0).use { it.localPort }
        val logs = mutableListOf<String>()
        val endpoint = CarPlayEndpoint("test", "", 0, 0, port, null, null, null,
            listOf("127.0.0.1"), airPlayPortSource = AirPlayPortSource.FALLBACK)
        val client = AirPlayClient(endpoint, logs::add)
        try {
            assertFalse(connect(client))
            assertTrue(logs.any { it.contains("tcp-failed") && it.contains("port=$port") &&
                it.contains("portSource=FALLBACK") && it.contains("type=ConnectException") })
        } finally { client.dispose() }
    }
}

package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession.CarPlayEndpoint
import org.junit.Assert.*
import org.junit.Test

class AirPlayEndpointPortsTest {
    private fun endpoint(port: Int = 0) = CarPlayEndpoint("q4", "fixed", 149, 2, port, null, null, null, emptyList())

    @Test fun rememberedWifiDoesNotAssertAServicePort() {
        val unknown = endpoint()
        assertEquals(AirPlayPortSource.UNKNOWN, unknown.airPlayPortSource)
        val found = AirPlayEndpointPorts.fromMdns(unknown, 17000)
        assertEquals(17000, found.airPlayPort)
        assertEquals(AirPlayPortSource.MDNS, found.airPlayPortSource)
    }

    @Test fun lateDiscoveryReplacesFallbackAndCanUpdateItsOwnPort() {
        val guessed = AirPlayEndpointPorts.fallback(endpoint())
        assertEquals(7000, guessed.airPlayPort)
        assertEquals(AirPlayPortSource.FALLBACK, guessed.airPlayPortSource)
        val found = AirPlayEndpointPorts.fromMdns(guessed, 17000)
        assertEquals(17000, found.airPlayPort)
        assertEquals(AirPlayPortSource.MDNS, found.airPlayPortSource)
        assertEquals(7100, AirPlayEndpointPorts.fromMdns(found, 7100).airPlayPort)
    }

    @Test fun explicitIap2PortWinsOverConflictingDiscovery() {
        val announced = endpoint(17000)
        assertEquals(announced, AirPlayEndpointPorts.fromMdns(announced, 7000))
        assertEquals(announced, AirPlayEndpointPorts.fallback(announced))
    }

    @Test fun sameNumericPortStillBecomesConfirmedByMdns() {
        val found = AirPlayEndpointPorts.fromMdns(AirPlayEndpointPorts.fallback(endpoint()), 7000)
        assertEquals(7000, found.airPlayPort)
        assertEquals(AirPlayPortSource.MDNS, found.airPlayPortSource)
    }

    @Test fun incompleteLateAnnouncementRetainsSourceWithoutPromotingFallback() {
        val guessed = AirPlayEndpointPorts.fallback(endpoint())
        val merged = AirPlayEndpointPorts.preferAnnouncement(endpoint(), guessed)
        assertEquals(AirPlayPortSource.FALLBACK, merged.airPlayPortSource)
        assertEquals(17000, AirPlayEndpointPorts.fromMdns(merged, 17000).airPlayPort)
        val late = AirPlayEndpointPorts.preferAnnouncement(endpoint(7100), merged)
        assertEquals(7100, late.airPlayPort)
        assertEquals(AirPlayPortSource.IAP2, late.airPlayPortSource)
    }

    @Test fun invalidDiscoveryCannotEraseValidPortOrInventOne() {
        for (invalid in listOf(-1, 0, 65536)) {
            assertEquals(endpoint(), AirPlayEndpointPorts.fromMdns(endpoint(), invalid))
            val valid = endpoint(7000)
            assertEquals(valid, AirPlayEndpointPorts.fromMdns(valid, invalid))
        }
        assertEquals(1, AirPlayEndpointPorts.fromMdns(endpoint(), 1).airPlayPort)
        assertEquals(65535, AirPlayEndpointPorts.fromMdns(endpoint(), 65535).airPlayPort)
    }
    @Test fun clientRetainsSourceOnlyUpdateWithoutClosingOrChangingEndpointGeneration() {
        val guessed = AirPlayEndpointPorts.fallback(endpoint())
        val client = io.github.shaonialife.carptocar.airplay.AirPlayClient(guessed, {})
        val generation = client.javaClass.getDeclaredField("endpointGeneration").apply { isAccessible = true }
        val before = generation.getInt(client)
        val confirmed = AirPlayEndpointPorts.fromMdns(guessed, 7000)
        assertFalse(client.updateEndpoint(confirmed, "same port confirmed"))
        assertEquals(AirPlayPortSource.MDNS, client.currentEndpoint.airPlayPortSource)
        assertEquals(before, generation.getInt(client))
        val announced = endpoint(7000)
        assertFalse(client.updateEndpoint(announced, "same port from iAP2"))
        assertEquals(AirPlayPortSource.IAP2, client.currentEndpoint.airPlayPortSource)
        assertEquals(7000, AirPlayEndpointPorts.fromMdns(client.currentEndpoint, 17000).airPlayPort)
    }

    @Test fun staleMdnsRefreshCannotOverwriteLateIap2Announcement() {
        val old = AirPlayEndpointPorts.fallback(endpoint())
        val staleRefresh = AirPlayEndpointPorts.fromMdns(old, 17000)
        val client = io.github.shaonialife.carptocar.airplay.AirPlayClient(old, {})
        try {
            val late = endpoint(7100)
            assertTrue(client.updateEndpoint(late, "late iAP2"))
            val method = client.javaClass.getDeclaredMethod("updateRefreshedEndpoint",
                old.javaClass, old.javaClass).apply { isAccessible = true }
            assertEquals(false, method.invoke(client, old, staleRefresh))
            assertEquals(7100, client.currentEndpoint.airPlayPort)
            assertEquals(AirPlayPortSource.IAP2, client.currentEndpoint.airPlayPortSource)
        } finally { client.dispose() }
    }

}

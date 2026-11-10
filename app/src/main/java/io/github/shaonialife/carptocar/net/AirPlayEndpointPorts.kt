package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession.CarPlayEndpoint

/** Runtime provenance only; service ports are not stored with remembered Wi-Fi credentials. */
enum class AirPlayPortSource { UNKNOWN, IAP2, MDNS, FALLBACK }

internal object AirPlayEndpointPorts {
    fun valid(port: Int): Boolean = port in 1..65535

    fun fromMdns(endpoint: CarPlayEndpoint, port: Int): CarPlayEndpoint {
        if (!valid(port)) return endpoint
        if (valid(endpoint.airPlayPort) && endpoint.airPlayPortSource == AirPlayPortSource.IAP2) return endpoint
        return endpoint.copy(airPlayPort = port, airPlayPortSource = AirPlayPortSource.MDNS)
    }

    fun fallback(endpoint: CarPlayEndpoint): CarPlayEndpoint =
        if (valid(endpoint.airPlayPort)) endpoint
        else endpoint.copy(airPlayPort = 7000, airPlayPortSource = AirPlayPortSource.FALLBACK)

    /** A late announcement without a valid port must retain both the fallback value and its source. */
    fun preferAnnouncement(late: CarPlayEndpoint, fallback: CarPlayEndpoint): CarPlayEndpoint =
        if (valid(late.airPlayPort)) late
        else late.copy(airPlayPort = fallback.airPlayPort, airPlayPortSource = fallback.airPlayPortSource)
}

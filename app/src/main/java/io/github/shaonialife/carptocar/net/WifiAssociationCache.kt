package io.github.shaonialife.carptocar.net

/** Only live, unredacted observations authorize reuse. A password or historical IP never does. */
internal class WifiAssociationCache<N> {
    private val identities = mutableMapOf<N, String>()
    @Synchronized fun update(network: N, ssid: String?) {
        if (ssid == null) identities.remove(network) else identities[network] = ssid
    }
    @Synchronized fun lost(network: N) { identities.remove(network) }
    @Synchronized fun clear() { identities.clear() }
    @Synchronized fun matches(ssid: String, live: Collection<N>): Boolean =
        identities.any { (network, name) -> network in live && name == ssid }
}

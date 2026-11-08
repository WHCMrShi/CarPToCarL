package io.github.shaonialife.carptocar.net

/** A fallback request owns one network until loss, never a later unrelated WiFi. */
internal class WifiNetworkLease<T> {
    private var selected: T? = null
    private var ended = false

    @Synchronized
    fun accept(network: T): Boolean {
        if (ended) return false
        if (selected == null) selected = network
        return selected == network
    }

    @Synchronized
    fun lost(network: T): Boolean {
        if (selected != network || ended) return false
        ended = true
        return true
    }
}

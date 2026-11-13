package io.github.shaonialife.carptocar.net

/** Losing a Network clears that result; only request unavailability is terminal. */
internal class WifiRequestState<T> {
    private var network: T? = null
    private var ended = false
    @Synchronized fun get(): T? = network
    @Synchronized fun available(next: T, bind: () -> Boolean): Boolean {
        if (ended || !bind()) return false
        network = next
        return true
    }
    @Synchronized fun unavailable(): T? {
        if (ended) return null
        ended = true
        return network.also { network = null }
    }
    @Synchronized fun lost(expected: T): Boolean {
        if (ended || network != expected) return false
        network = null
        return true
    }
}

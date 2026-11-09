package io.github.shaonialife.carptocar.net

/** Specifier result lifecycle: unavailable/lost is terminal even after onAvailable. */
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
        unavailable()
        return true
    }
}

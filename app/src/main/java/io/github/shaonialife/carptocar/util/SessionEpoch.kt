package io.github.shaonialife.carptocar.util

/** 网络协商不持有此锁；异步资源只可在原会话仍开放时发布。 */
internal class SessionEpoch {
    data class Lease(val owner: Any, val version: Long)
    private var owner: Any? = null
    private var version = 0L
    @Synchronized fun begin(next: Any?) { version++; owner = next }
    @Synchronized fun capture(expected: Any): Lease? = if (owner === expected) Lease(expected, version) else null
    @Synchronized fun isCurrent(lease: Lease): Boolean = owner === lease.owner && version == lease.version
    @Synchronized fun commit(lease: Lease, publish: () -> Unit): Boolean {
        if (!isCurrent(lease)) return false
        publish()
        return true
    }
    @Synchronized fun end(expected: Any, retire: () -> Unit): Boolean {
        if (owner !== expected) return false
        clear(retire)
        return true
    }
    @Synchronized fun clear(retire: () -> Unit) { version++; owner = null; retire() }
}

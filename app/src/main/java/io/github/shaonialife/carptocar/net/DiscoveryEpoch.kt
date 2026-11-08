package io.github.shaonialife.carptocar.net

/** Late resolve results cannot resurrect a service that was lost or reset. */
internal class DiscoveryEpoch {
    data class Lease(val name: String, val version: Long)
    private val versions = HashMap<String, Long>()
    private var next = 0L
    @Synchronized fun found(name: String): Lease = Lease(name, ++next).also { versions[name] = it.version }
    @Synchronized fun lost(name: String) { versions.remove(name) }
    @Synchronized fun reset() { versions.clear() }
    @Synchronized fun publish(lease: Lease, action: () -> Unit): Boolean {
        if (versions[lease.name] != lease.version) return false
        action()
        return true
    }
}

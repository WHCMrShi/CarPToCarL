package io.github.shaonialife.carptocar.net

/** Credentials identify a destination, not its current presence. Admission is not a lease on BT. */
internal object VehicleNetworkPolicy {
    fun present(target: String, acl: Boolean, readyPeer: String?, associated: Boolean): Boolean =
        acl || associated || target.equals(readyPeer, ignoreCase = true)

    fun awaitPresence(
        valid: () -> Boolean,
        present: () -> Boolean,
        now: () -> Long,
        sleep: (Long) -> Unit,
        budgetMs: Long = 180_000,
    ): Boolean {
        val deadline = now() + budgetMs
        while (valid() && now() < deadline) {
            val found = present()
            if (!valid() || now() >= deadline) return false
            if (found) return true
            try {
                sleep(minOf(1_000, (deadline - now()).coerceAtLeast(1)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}

/** A failed migration is retryable; only a verified successful removal completes it. */
internal class OwnedSuggestionMigration {
    private var complete = false

    @Synchronized fun ensure(removeAndVerify: () -> Boolean): Boolean {
        if (complete) return true
        complete = removeAndVerify()
        return complete
    }
}

/** No IP-based inference: an observation is usable only for that live Network and observer epoch. */
internal class LiveWifiIdentity<N> {
    private val ssids = mutableMapOf<N, String>()
    private var epoch = 0L

    @Synchronized fun restart(): Long { ssids.clear(); return ++epoch }
    @Synchronized fun update(owner: Long, network: N, ssid: String?) {
        if (owner != epoch) return
        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") ssids.remove(network) else ssids[network] = ssid
    }
    @Synchronized fun lost(owner: Long, network: N) { if (owner == epoch) ssids.remove(network) }
    @Synchronized fun matches(ssid: String, live: Collection<N>): Boolean = live.any { ssids[it] == ssid }
}

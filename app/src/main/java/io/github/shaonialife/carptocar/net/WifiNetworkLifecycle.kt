package io.github.shaonialife.carptocar.net

/**
 * A request keeps an association alive; an observer only reports networks; a binding selects
 * the process network. Reuse must not release the request (Q4 2026-11-13 config removal).
 * All ownership checks and platform side effects share this lock, including terminal cleanup.
 */
internal class WifiNetworkLifecycle<R : Any, N : Any>(
    private val unregister: (R) -> Unit,
    private val bindProcess: (N?) -> Boolean,
) {
    private data class Binding<N>(val owner: Any, val network: N, val valid: () -> Boolean)
    private var request: R? = null
    private var observer: R? = null
    private var probe: Any? = null
    private var binding: Binding<N>? = null
    private var bindingVersion = 0L

    @Synchronized fun requestOwner(): R? = request
    @Synchronized fun observerOwner(): R? = observer
    @Synchronized fun probeOwner(): Any? = probe
    @Synchronized fun network(): N? = binding?.network

    @Synchronized fun usable(check: (N) -> Boolean): N? =
        binding?.takeIf { it.valid() && check(it.network) }?.network

    /** A completed old waiter must not return a successor's binding between two lock reads. */
    @Synchronized fun requestResult(owner: R, valid: () -> Boolean, check: (N) -> Boolean): N? =
        if (request === owner && valid()) usable(check) else null

    @Synchronized fun probeResult(owner: Any, valid: () -> Boolean): N? =
        if (probe === owner && binding?.owner === owner && valid()) binding?.network else null

    @Synchronized fun withRequest(owner: R, action: () -> Unit): Boolean {
        if (request !== owner) return false
        action()
        return true
    }

    @Synchronized fun withObserver(owner: R, action: () -> Unit): Boolean {
        if (observer !== owner) return false
        action()
        return true
    }

    /** Only an explicit new active request supersedes the previous association request. */
    @Synchronized fun startRequest(owner: R, valid: () -> Boolean, register: () -> Unit): Boolean {
        if (!valid()) return false
        probe = null
        clearBinding()
        request?.let { request = null; unregister(it) }
        request = owner
        try {
            register()
        } catch (error: Exception) {
            releaseRequest(owner)
            throw error
        }
        return true
    }

    /** One passive observer lives until connection teardown, independently of reuse waiters. */
    @Synchronized fun ensureObserver(owner: R, valid: () -> Boolean, register: () -> Unit): Boolean {
        if (!valid()) return false
        if (observer != null) return true
        observer = owner
        try {
            register()
        } catch (error: Exception) {
            observer = null
            unregister(owner)
            throw error
        }
        return true
    }

    @Synchronized fun startProbe(owner: Any, valid: () -> Boolean): Boolean {
        if (!valid()) return false
        probe = owner
        return true
    }

    @Synchronized fun endProbe(owner: Any) {
        if (probe === owner) probe = null
    }

    @Synchronized fun bindRequest(owner: R, network: N, valid: () -> Boolean): Boolean {
        if (request !== owner || !valid()) return false
        val previous = binding
        // A retained request may report another best network after a verified reuse binding.
        // It retains its association resource, but cannot take that binding back.
        if (previous != null && previous.owner !== owner && previous.valid()) {
            return previous.network == network && bindProcess(network)
        }
        return bind(owner, network, valid)
    }

    @Synchronized fun bindProbe(owner: Any, network: N, valid: () -> Boolean): Boolean {
        if (probe !== owner || !valid()) return false
        return bind(owner, network, valid)
    }

    private fun bind(owner: Any, network: N, valid: () -> Boolean): Boolean {
        if (!bindProcess(network)) return false
        binding = Binding(owner, network, valid)
        bindingVersion++
        return true
    }

    /** The returned predicate rejects delayed recovery after rebind, cancellation or teardown. */
    @Synchronized fun requestLost(owner: R, network: N): (() -> Boolean)? {
        if (request !== owner || binding?.owner !== owner || binding?.network != network) return null
        return loseBinding()
    }

    @Synchronized fun observerLost(owner: R, network: N): (() -> Boolean)? {
        if (observer !== owner || binding?.network != network) return null
        return loseBinding()
    }

    @Synchronized fun releaseRequest(owner: R): (() -> Boolean)? {
        if (request !== owner) return null
        request = null
        val lost = if (binding?.owner === owner) loseBinding() else null
        unregister(owner)
        return lost
    }

    private fun loseBinding(): (() -> Boolean)? {
        val previous = binding ?: return null
        clearBinding()
        val version = bindingVersion
        return { synchronized(this) { bindingVersion == version && previous.valid() } }
    }

    private fun clearBinding() {
        val previous = binding
        binding = null
        bindingVersion++
        if (previous != null) bindProcess(null)
    }

    @Synchronized fun release() {
        val oldRequest = request
        val oldObserver = observer
        request = null
        observer = null
        probe = null
        clearBinding()
        oldRequest?.let(unregister)
        oldObserver?.let(unregister)
    }
}

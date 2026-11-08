package io.github.shaonialife.carptocar.video

/** Independent observer: it never needs the writer's lock to expire a blocked write. */
internal class WriteDeadline<T>(private val timeoutMs: Long = 4_000) {
    data class Lease<T>(val owner: T, val kind: String, val startedAt: Long)
    private var active: Lease<T>? = null
    private var expired = false

    @Synchronized fun begin(owner: T, kind: String, now: Long): Lease<T> =
        Lease(owner, kind, now).also { active = it; expired = false }

    @Synchronized fun finish(lease: Lease<T>) {
        if (active === lease) active = null
    }

    @Synchronized fun overdue(now: Long): Lease<T>? {
        val lease = active ?: return null
        if (expired || now - lease.startedAt < timeoutMs) return null
        expired = true
        return lease
    }
}

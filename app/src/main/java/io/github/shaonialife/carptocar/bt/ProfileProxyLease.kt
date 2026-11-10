package io.github.shaonialife.carptocar.bt

/** Retiring an acquisition releases both an early result and any late callback result. */
internal class ProfileProxyLease<T : Any>(private val dispose: (T) -> Unit) {
    private var retired = false
    private var resource: T? = null

    @Synchronized fun deliver(value: T) {
        if (retired) dispose(value)
        else if (resource !== value) {
            resource?.let(dispose)
            resource = value
        }
    }
    @Synchronized fun get(): T? = if (retired) null else resource
    @Synchronized fun retire() {
        retired = true
        resource?.let(dispose)
        resource = null
    }
}

internal fun awaitProfileReady(await: () -> Boolean): Boolean = try {
    if (Thread.currentThread().isInterrupted) false else await()
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}

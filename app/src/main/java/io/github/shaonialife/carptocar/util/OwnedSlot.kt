package io.github.shaonialife.carptocar.util

/** Ownership check and side effect are serialized with replacement and disposal. */
internal class OwnedSlot<T : Any> {
    @Volatile private var owner: T? = null
    fun get(): T? = owner

    @Synchronized fun replace(next: T, allowed: () -> Boolean, dispose: (T) -> Unit): Boolean {
        if (!allowed()) return false
        owner?.let { owner = null; dispose(it) }
        owner = next
        return true
    }
    @Synchronized fun runIfCurrent(expected: T, action: () -> Unit): Boolean {
        if (owner !== expected) return false
        action()
        return true
    }
    @Synchronized fun clear(expected: T? = null, dispose: (T) -> Unit): Boolean {
        val old = owner ?: return false
        if (expected != null && old !== expected) return false
        owner = null
        dispose(old)
        return true
    }
}

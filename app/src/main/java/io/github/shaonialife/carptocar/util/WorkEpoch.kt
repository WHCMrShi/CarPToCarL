package io.github.shaonialife.carptocar.util

/** Immutable tickets never become valid again when a later connection clears cancellation. */
internal class WorkEpoch {
    private val lock = Any()
    @Volatile private var identity: Any? = Any()
    fun capture(): Ticket = Ticket(identity)
    fun begin() = synchronized(lock) { if (identity == null) identity = Any() }
    fun replace(): Ticket = synchronized(lock) { identity = Any(); capture() }
    fun end() = synchronized(lock) { identity = null }

    inner class Ticket internal constructor(private val value: Any?) {
        val isCurrent: Boolean get() = value != null && identity === value
        fun runIfCurrent(action: () -> Unit): Boolean = synchronized(lock) {
            if (!isCurrent) false else { action(); true }
        }
    }
}

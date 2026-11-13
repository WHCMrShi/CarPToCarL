package io.github.shaonialife.carptocar.bt

/**
 * One local release/dial action at a time. A lease spans both dials, the intervening release,
 * and the final owner claim. Inbound accept never waits for this lease.
 * The monitor protects bookkeeping only; platform calls, cancellation and logging run outside it.
 */
internal class BtActionCoordinator {
    private val lock = Object()
    private var nextId = 0L
    private var active: Lease? = null

    inner class Lease internal constructor(
        val id: Long,
        internal val operation: BtProfileOperation,
        private val source: String,
        private val log: (String) -> Unit,
    ) : AutoCloseable {
        private var cancellation: (() -> Unit)? = null

        fun phase(name: String) {
            log("BT-ACTION: id=$id source=$source phase=$name")
        }

        /** Always captures one specific socket, never a mutable currentSocket reference. */
        fun cancelWith(action: () -> Unit): AutoCloseable {
            synchronized(lock) { check(active === this); cancellation = action }
            cancelIfObsolete()
            return AutoCloseable {
                synchronized(lock) { if (cancellation === action) cancellation = null }
            }
        }

        internal fun cancelIfObsolete() {
            if (!operation.isSuperseded()) return
            val action = synchronized(lock) {
                if (active === this) cancellation.also { cancellation = null } else null
            }
            action?.invoke()
        }

        override fun close() {
            val released = synchronized(lock) {
                if (active !== this) false else {
                    cancellation = null
                    active = null
                    lock.notifyAll()
                    true
                }
            }
            if (released) phase("END")
        }
    }

    fun acquire(operation: BtProfileOperation, source: String, log: (String) -> Unit = {}): Lease? {
        var waitingLogged = false
        while (operation.isCurrent()) {
            val lease = try {
                synchronized(lock) {
                    if (active == null) Lease(++nextId, operation, source, log).also { active = it }
                    else { lock.wait(100); null }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (lease != null) {
                if (!operation.isCurrent()) { lease.close(); return null }
                lease.phase("BEGIN")
                return lease
            }
            if (!waitingLogged) {
                log("BT-ACTION: source=$source waiting for local release/dial action")
                waitingLogged = true
            }
        }
        return null
    }

    /** Called after an inbound claim or work invalidation, without holding lifecycle locks. */
    fun cancelObsoleteAction() {
        synchronized(lock) { active }?.cancelIfObsolete()
    }
}

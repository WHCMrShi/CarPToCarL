package io.github.shaonialife.carptocar.net

import java.net.ServerSocket

/** DNS announcement refreshes reuse the listener; explicit stop owns its retirement. */
internal class CtrlListener : AutoCloseable {
    data class Lease(val socket: ServerSocket, val fresh: Boolean)
    @Volatile var current: ServerSocket? = null
        private set
    @Synchronized fun acquire(): Lease {
        current?.takeUnless { it.isClosed }?.let { return Lease(it, false) }
        return Lease(ServerSocket(0), true).also { current = it.socket }
    }
    @Synchronized override fun close() {
        val old = current
        current = null
        old?.close()
    }
}

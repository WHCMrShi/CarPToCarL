package io.github.shaonialife.carptocar.bt

/** One RFCOMM session owner across both directions. A retired completion cannot clear its successor. */
class BtLinkOwnership(private val clock: () -> Long) {
    private var sequence = 0L
    private var latest: Token? = null
    private var active: Token? = null
    inner class Token internal constructor(val id: Long, val direction: String, val startedAt: Long) {
        var ready: Boolean = false
            private set
        private var retired = false
        val isCurrent: Boolean get() = synchronized(this@BtLinkOwnership) { latest === this && !retired }
        fun runIfCurrent(action: () -> Unit): Boolean = synchronized(this@BtLinkOwnership) {
            if (active !== this || retired) false else { action(); true }
        }
        fun markReady() = runIfCurrent { ready = true }
        fun finish(): Boolean = synchronized(this@BtLinkOwnership) {
            if (active !== this) false else { active = null; true }
        }
        fun retire() = synchronized(this@BtLinkOwnership) {
            retired = true
            if (active === this) active = null
        }
    }
    @Synchronized fun claim(direction: String): Token? {
        if (active != null) return null
        return Token(++sequence, direction, clock()).also { latest = it; active = it }
    }
    @Synchronized fun inbound(): Boolean = active?.direction == "inbound"
    @Synchronized fun ready(): Boolean = active?.ready == true
}

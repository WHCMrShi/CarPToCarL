package io.github.shaonialife.carptocar.audio

/** Per-control-session AAC budget. A new sender does not replenish automatic recovery attempts. */
internal class AacRecoveryBudget {
    private var owner: Any? = null
    private var active: Ticket? = null
    private var automaticAttempts = 0
    private var lastAt: Long? = null
    inner class Ticket internal constructor(val manual: Boolean) {
        val current: Boolean get() = synchronized(this@AacRecoveryBudget) { active === this }
        fun finish() = synchronized(this@AacRecoveryBudget) { if (active === this) active = null }
    }
    @Synchronized fun reserve(session: Any, now: Long, manual: Boolean): Ticket? {
        if (owner !== session) { owner = session; active = null; automaticAttempts = 0; lastAt = null }
        if (active != null || (!manual && automaticAttempts >= 2)) return null
        if (lastAt?.let { now - it < if (manual) 2_000L else 30_000L } == true) return null
        lastAt = now
        if (!manual) automaticAttempts++
        return Ticket(manual).also { active = it }
    }
}

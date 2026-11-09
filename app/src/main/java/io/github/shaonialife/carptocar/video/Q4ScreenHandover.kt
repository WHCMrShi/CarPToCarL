package io.github.shaonialife.carptocar.video

/** Q4 分支的晚到真实源恢复；缓存起播不武装，IO 不持有状态锁。 */
internal class Q4ScreenHandover {
    class Ticket internal constructor(internal val owner: Any, val generation: Long, val reason: String)
    enum class Result { ACTIVATED, DEFERRED, STALE, MODES_FAILED, RECORD_FAILED }
    private var generation = 0L
    private var reason: String? = null
    private var issued = false
    private var started = false

    @Synchronized fun arm(reason: String) {
        generation++; this.reason = reason; issued = false; started = false
    }
    @Synchronized fun retire(): Long {
        generation++; reason = null; issued = false; started = false
        return generation
    }
    @Synchronized fun sourceGeneration(): Long = generation
    @Synchronized fun isSourceCurrent(expected: Long): Boolean = generation == expected
    @Synchronized fun waiting(): Boolean = reason != null && !issued
    @Synchronized fun written(expectedGeneration: Long, idr: Boolean, configured: Boolean): Ticket? {
        val pending = reason ?: return null
        if (generation != expectedGeneration || issued || !idr || !configured) return null
        issued = true
        return Ticket(this, generation, pending)
    }
    @Synchronized private fun current(ticket: Ticket): Boolean =
        ticket.owner === this && generation == ticket.generation && reason != null && issued

    fun recover(ticket: Ticket, alive: () -> Boolean, modes: () -> Boolean, record: () -> Boolean,
        allowed: () -> Boolean = { true }): Result {
        if (!alive() || !current(ticket)) return Result.STALE
        synchronized(this) {
            if (!current(ticket) || started) return Result.STALE
            if (!allowed()) return Result.DEFERRED
            started = true
        }
        if (!alive() || !current(ticket)) return Result.STALE
        fun defer(): Result {
            synchronized(this) { if (current(ticket)) started = false }
            return Result.DEFERRED
        }
        if (!allowed()) return defer()
        if (!modes()) return if (!allowed()) defer() else Result.MODES_FAILED
        if (!alive() || !current(ticket)) return Result.STALE
        if (!allowed()) return defer()
        if (!record()) return Result.RECORD_FAILED
        return if (alive() && current(ticket)) Result.ACTIVATED else Result.STALE
    }
}

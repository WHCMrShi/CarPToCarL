package io.github.shaonialife.carptocar.bt

/** A single two-second settling window after a long observer gap, without moving the idle deadline. */
internal class IdleResumeWindow {
    private var lastAt: Long? = null
    private var until = 0L
    fun reset() { lastAt = null; until = 0 }
    fun defer(now: Long, wanted: Boolean): Boolean {
        val previous = lastAt
        lastAt = now
        if (!wanted) { until = 0; return false }
        if (previous != null && now - previous >= 10_000) until = now + 2_000
        return now < until
    }
}

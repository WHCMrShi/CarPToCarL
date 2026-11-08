package io.github.shaonialife.carptocar.bt

/** Deadlines belong to phases: activity ends startup waiting, loss starts a fresh recovery window. */
internal class IdleBudget(initialDeadline: Long = 0L) {
    var deadline = initialDeadline
        private set
    private var wasPresent = false

    fun start(now: Long) { wasPresent = false; deadline = now + STARTUP_MS }
    fun clear() { wasPresent = false; deadline = 0L }
    fun disconnected(now: Long) {
        val recovery = now + CarIdlePolicy.IDLE_TIMEOUT_MILLIS
        deadline = if (deadline > 0) minOf(deadline, recovery) else recovery
    }
    fun expired(now: Long, present: Boolean): Boolean {
        if (present) { deadline = 0L; wasPresent = true; return false }
        if (wasPresent) { wasPresent = false; disconnected(now) }
        if (deadline == 0L) start(now)
        return now >= deadline
    }

    companion object { const val STARTUP_MS = 180_000L }
}

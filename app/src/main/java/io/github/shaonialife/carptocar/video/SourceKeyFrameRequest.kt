package io.github.shaonialife.carptocar.video

/** One source request at a time; tickets cannot migrate across a source restart. */
internal class SourceKeyFrameRequest(private val intervalMs: Long = 6_000) {
    class Ticket internal constructor(val generation: Long, val at: Long) { internal var arrived = false }
    private var pending: Ticket? = null
    private var inFlight = false
    private var lastAt: Long? = null
    @Synchronized fun begin(generation: Long, now: Long): Ticket? {
        if (inFlight || lastAt?.let { now - it < intervalMs } == true) return null
        return Ticket(generation, now).also { pending = it; inFlight = true; lastAt = now }
    }
    @Synchronized fun current(ticket: Ticket, generation: Long): Boolean = pending === ticket && ticket.generation == generation
    @Synchronized fun needsForward(ticket: Ticket, generation: Long): Boolean = current(ticket, generation) && !ticket.arrived
    @Synchronized fun forwarded(ticket: Ticket, ok: Boolean): Boolean {
        if (pending !== ticket) return false
        inFlight = false
        if (!ok && !ticket.arrived) pending = null
        return true
    }
    @Synchronized fun arrived(generation: Long): Ticket? = pending?.takeIf { it.generation == generation && !it.arrived }?.also { it.arrived = true }
    @Synchronized fun written(generation: Long): Ticket? = pending?.takeIf { it.generation == generation && it.arrived }?.also { pending = null; inFlight = false }
    @Synchronized fun reset() { pending = null; inFlight = false; lastAt = null }
}

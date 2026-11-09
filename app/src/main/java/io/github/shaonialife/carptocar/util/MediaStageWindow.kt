package io.github.shaonialife.carptocar.util

/** One update per input block/frame, no payload retention or per-event formatting. */
internal class MediaStageWindow(private val name: String, private val slowNs: Long) {
    private var since = 0L
    private var lastReport = 0L
    private var count = 0L
    private var slow = 0L
    private var gaps = 0L
    private var waitMax = 0L
    private var workMax = 0L
    private var sealMax = 0L
    private var sendMax = 0L

    @Synchronized fun record(now: Long, wait: Long, work: Long, seal: Long = 0, send: Long = 0, gap: Boolean = false): String? {
        if (since == 0L) { since = now; lastReport = now }
        count++
        if (wait + work >= slowNs) slow++
        if (gap) gaps++
        waitMax = maxOf(waitMax, wait); workMax = maxOf(workMax, work)
        sealMax = maxOf(sealMax, seal); sendMax = maxOf(sendMax, send)
        val elapsed = now - lastReport
        if (elapsed < 30_000_000_000L && (elapsed < 5_000_000_000L || slow == 0L && gaps == 0L)) return null
        return report(now, false)
    }

    @Synchronized fun finish(now: Long): String? = if (count == 0L) null else report(now, true)

    private fun report(now: Long, final: Boolean): String {
        val result = "MEDIA-STAGE: $name windowMs=${(now - since) / 1_000_000} count=$count slow=$slow discontinuities=$gaps " +
            "lockWaitMaxUs=${waitMax / 1000} workInclusiveMaxUs=${workMax / 1000} sealMaxUs=${sealMax / 1000} sendMaxUs=${sendMax / 1000} final=$final"
        since = now; lastReport = now; count = 0; slow = 0; gaps = 0
        waitMax = 0; workMax = 0; sealMax = 0; sendMax = 0
        return result
    }
}

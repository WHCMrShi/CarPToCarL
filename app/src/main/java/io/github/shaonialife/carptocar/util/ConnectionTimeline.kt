package io.github.shaonialife.carptocar.util

/** Observation only: parallel stage times must never be added to infer connection latency. */
internal class ConnectionTimeline(private val clock: () -> Long, private val log: (String) -> Unit) {
    enum class Stage { WIFI_SHARED_WAIT, WIFI_REUSE, WIFI_SPECIFIER, WIFI_SUGGESTION, PAIRING, INITIAL_SETUP, SCREEN_SETUP, AUDIO_SETUP, RECORD }
    private var round = 0L
    private var active = false
    private var origin = clock()
    private val attempts = IntArray(Stage.entries.size)

    @Synchronized fun begin() {
        if (active) return
        active = true
        round++
        origin = clock()
        attempts.fill(0)
        emit("round=$round event=BEGIN")
    }

    @Synchronized fun end() {
        emit("round=$round event=END elapsedMs=${clock() - origin}")
        active = false
        round++ // A retired stage cannot complete into the next connection's timeline.
        attempts.fill(0)
        origin = clock()
    }

    fun <T> measure(stage: Stage, block: () -> T): T {
        val ticket = synchronized(this) {
            val at = clock()
            val attempt = ++attempts[stage.ordinal]
            emit("round=$round active=$active stage=$stage attempt=$attempt event=BEGIN elapsedMs=${at - origin}")
            Triple(round, at, attempt)
        }
        var outcome = "THREW"
        try {
            val result = block()
            outcome = when (result) { null -> "NULL"; is Boolean -> result.toString(); else -> "RETURNED" }
            return result
        } finally {
            synchronized(this) {
                if (round == ticket.first) {
                    val now = clock()
                    emit("round=$round active=$active stage=$stage attempt=${ticket.third} event=END durationMs=${now - ticket.second} elapsedMs=${now - origin} outcome=$outcome")
                }
            }
        }
    }

    private fun emit(detail: String) { runCatching { log("PATH-DIAG: $detail") } }
}

package io.github.shaonialife.carptocar.audio

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serializes the experiment's control commands off the RTP worker. */
internal class NavigationPromptDucking(
    private val latencyMs: Long,
    private val send: (duck: Boolean) -> Boolean?,
) : AutoCloseable {
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "q4-nav-ducking").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var release: ScheduledFuture<*>? = null
    private var generation = 0L
    private var promptActive = false
    private var closed = false

    // Accessed only on the command worker. A lost acknowledgement still calls for restoration.
    private var restoreNeeded = false
    private var failed = false

    @Synchronized
    fun begin() {
        if (closed) return
        generation++
        release?.cancel(false)
        if (promptActive) return
        promptActive = true
        worker.execute {
            if (!failed) {
                val result = send(true)
                restoreNeeded = result != false
                failed = result != true
            }
        }
    }

    @Synchronized
    fun end(lastPacketEndNs: Long?) {
        if (closed || !promptActive) return
        val ticket = ++generation
        release?.cancel(false)
        // Account for the SETUP jitter-buffer request after the last packet's audio ends.
        val remaining = lastPacketEndNs?.let {
            (TimeUnit.MILLISECONDS.toNanos(latencyMs) - (System.nanoTime() - it)).coerceAtLeast(0)
        } ?: 0L
        release = worker.schedule({
            val current = synchronized(this) {
                (ticket == generation && !closed).also { if (it) promptActive = false }
            }
            if (current) restore()
        }, remaining, TimeUnit.NANOSECONDS)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        release?.cancel(false)
        worker.execute(::restore)
        worker.shutdown()
    }

    private fun restore() {
        if (!restoreNeeded) return
        restoreNeeded = false
        if (send(false) != true) failed = true
    }
}

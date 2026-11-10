package io.github.shaonialife.carptocar.audio

/** Only controls silence eligibility; never controls real PCM admission or connection recovery. */
internal class SilenceFillPolicy(private val idleMs: Long = 300L, private val stoppedMs: Long = 50L) {
    @Volatile private var stopped = false
    fun engineStopped() { stopped = true }
    fun resumed() { stopped = false }
    // STOP is advisory and shared by media/TTS. Do not insert silence into fresh real input.
    fun thresholdMs(): Long = if (stopped) stoppedMs else idleMs
}

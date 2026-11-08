package io.github.shaonialife.carptocar.audio

/** 只统计时序，不读取 PCM 内容或改变发送节拍；每 30 秒/退役时生成一行。 */
internal class AudioContinuityWindow {
    private var started: Long? = null
    private var lastInput: Long? = null
    private var lastOutput: Long? = null
    private var feeds = 0L
    private var packets = 0L
    private var silence = 0L
    private var inputGap = 0L
    private var outputGap = 0L
    private var sendTime = 0L

    @Synchronized fun input(now: Long) {
        if (started == null) started = now
        lastInput?.let { inputGap = maxOf(inputGap, now - it) }
        lastInput = now; feeds++
    }

    @Synchronized fun silence() { silence++ }

    @Synchronized fun output(now: Long, duration: Long): String? {
        if (started == null) started = now
        lastOutput?.let { outputGap = maxOf(outputGap, now - it) }
        lastOutput = now; packets++; sendTime = maxOf(sendTime, duration)
        return if (now - started!! >= 30_000_000_000L) summary(now) else null
    }

    @Synchronized fun summary(now: Long): String? {
        if (feeds == 0L && packets == 0L && silence == 0L) return null
        val text = "AUDIO-DIAG: windowMs=${(now - (started ?: now)) / 1_000_000} realFeeds=$feeds udpPackets=$packets " +
            "inputGapMaxMs=${inputGap / 1_000_000} outputGapMaxMs=${outputGap / 1_000_000} sendMaxUs=${sendTime / 1_000} silenceSubmissions=$silence"
        feeds = 0; packets = 0; silence = 0; inputGap = 0; outputGap = 0; sendTime = 0
        started = now
        return text
    }
}

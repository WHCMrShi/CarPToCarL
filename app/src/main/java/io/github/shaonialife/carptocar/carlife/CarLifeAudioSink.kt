package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.audio.AudioSender

/**
 * CarLife 音频桥接（手机 → 车机方向的**直通落地点**）。
 *
 * 早期实现是「本机播放 + 播放捕获」：把 CarLife 引擎的 PCM 用 `USAGE_MEDIA` 的
 * `AudioTrack` 在本机播出来，再让 [AudioSender] 的 `AudioPlaybackCapture` 采回去
 * 推给车机。代价是依赖 MediaProjection 录屏授权 + 一次系统内混音/重采样往返。
 *
 * 现在改为**直通**：直接把引擎的裸 PCM 喂给 [AudioSender.feedPcm]，由其内部完成
 * 采样率/声道对齐 + RTP/ChaCha20 封包发送。不依赖录屏授权、少一次中转。
 *
 * 调用方先为当前 PCM 包选定已协商的发送端，再交给本类处理格式与统计。
 */
internal class CarLifeAudioSink(
    private val log: (String) -> Unit,
    /** Port origin only, for queue budgets; merged 9240 PCM is never music-only. */
    private val speech: Boolean = false,
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
    /** CarLife 引擎宣告的源采样率（CarlifeMusicInit；<=0 表示尚未宣告，按 48k 处理）。 */
    @Volatile
    private var srcRate = 0

    /** CarLife 引擎宣告的源声道数（1 或 2）。 */
    @Volatile
    private var srcChannels = 2

    private var packets = 0L
    private var bytes = 0L
    private var lastReportAt = 0L

    /**
     * 电平统计（跟着 5 秒那条「音频直通」日志一起报）。
     *
     * 为什么需要（2026-10-26）：接过微信电话后，RTP 照发、车机 `/feedback` 的 sampleTime
     * 按 1.00 倍实时推进（= 它在正常消费），但喇叭全静音。这时只有两种可能：
     * ① 引擎推的就是静音（通话把媒体音频路由带走了）；② 引擎推的是真声音、车机静音位。
     * 看 ppm 峰值/非零占比就能一眼区分，不必再猜。
     */
    private var peakSinceReport = 0

    private var nonZeroSinceReport = 0L

    private var sampledSinceReport = 0L

    /** 手机宣告的音频格式（CarlifeMusicInit）。重复调用无副作用。 */
    @Synchronized
    fun configure(
        sampleRate: Int,
        channels: Int,
    ) {
        srcRate = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        srcChannels = if (channels == 1) 1 else 2
        log("CARLIFE: 音频格式 ${srcRate}Hz ${if (srcChannels == 2) "立体声" else "单声道"} 16bit（直通）")
    }

    /** 收到一包裸 PCM（16bit 交织），直通给正在运行的 AirPlay 音频发送端。 */
    @Synchronized
    fun write(
        sampleRate: Int,
        channels: Int,
        pcm: ByteArray,
        size: Int,
        forward: (ByteArray, Int, Int, Int, Boolean) -> Boolean,
    ) {
        if (size <= 0) return
        if (srcRate <= 0) {
            configure(
                if (sampleRate > 0) sampleRate else DEFAULT_RATE,
                if (channels > 0) channels else 2,
            )
        }
        if (!forward(pcm, size, srcRate, srcChannels, speech)) return
        sampleLevel(pcm, size)
        packets++
        bytes += size
        reportIfDue()
    }

    /** 抽样统计电平（每 16 个采样点取 1 个，开销可忽略）。 */
    private fun sampleLevel(
        pcm: ByteArray,
        size: Int,
    ) {
        var index = 0
        while (index + 1 < size) {
            val sample = (((pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)).toShort()).toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peakSinceReport) peakSinceReport = magnitude
            if (magnitude >= SILENCE_FLOOR) nonZeroSinceReport++
            sampledSinceReport++
            index += 32
        }
    }

    @Synchronized
    fun stop() {
        srcRate = 0
        srcChannels = 2
        packets = 0
        bytes = 0
        lastReportAt = 0
        peakSinceReport = 0
        nonZeroSinceReport = 0
        sampledSinceReport = 0
    }

    private fun reportIfDue() {
        val now = clock()
        if (lastReportAt == 0L) {
            lastReportAt = now
            return
        }
        if (now - lastReportAt < REPORT_INTERVAL_MS) return
        val seconds = (now - lastReportAt) / 1000.0
        val kbps = if (seconds > 0) (bytes * 8 / 1000.0 / seconds).toInt() else 0
        val peakPercent = peakSinceReport * 100 / 32768
        val nonZeroPercent = if (sampledSinceReport > 0) (nonZeroSinceReport * 100 / sampledSinceReport).toInt() else 0
        log(
            "CARLIFE: 音频直通 ${packets} 包 / ${bytes / 1024}KB / ${kbps}kbps" +
                " · 电平 ${peakPercent}%（非零 ${nonZeroPercent}%）",
        )
        lastReportAt = now
        packets = 0
        bytes = 0
        peakSinceReport = 0
        nonZeroSinceReport = 0
        sampledSinceReport = 0
    }

    private companion object {
        const val DEFAULT_RATE = 48_000
        const val MIN_RATE = 8_000
        const val MAX_RATE = 48_000
        const val REPORT_INTERVAL_MS = 5_000L

        /** 电平统计的「非零」门限（约 -66dBFS，低于它的样本算静音底噪）。 */
        const val SILENCE_FLOOR = 16
    }
}

/** 音频包类型 → 可读名（日志用）。 */
internal fun carLifeAudioTypeName(type: Int): String =
    when (type) {
        CarLifeProtocol.MUSIC_PKG_INIT -> "INIT"
        CarLifeProtocol.MUSIC_PKG_START -> "开始"
        CarLifeProtocol.MUSIC_PKG_STOP -> "停止"
        CarLifeProtocol.MUSIC_PKG_NORMAL -> "PCM"
        CarLifeProtocol.TTS_PKG_INIT -> "TTS-INIT"
        0x40002 -> "TTS-开始"
        0x40003 -> "TTS-PCM"
        else -> "0x%X".format(type)
    }

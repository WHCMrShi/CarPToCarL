package io.github.shaonialife.carptocar.audio

internal interface AacEncoderPort {
    val isRunning: Boolean
    fun start(): Boolean
    fun encode(pcm: ByteArray, size: Int, offset: Int): List<ByteArray>
    fun stop()
}

/** AAC state belongs to this stream only. PCM never constructs this backend. Caller serializes feed. */
internal class AacOutputPipeline(
    val settings: AacOutputSettings,
    private val log: (String) -> Unit,
    private val send: (ByteArray) -> Boolean,
    private val longGap: (String) -> Unit,
    private val now: () -> Long = System::nanoTime,
    private val cpuNow: () -> Long = { android.os.Debug.threadCpuTimeNanos() },
    private val probeWork: (() -> Unit) -> Unit = { work -> Thread(work, "aac-stream-probe").apply { isDaemon = true; start() } },
    private val decodeWindow: (List<ByteArray>, Int) -> Pair<Int, Int>? = { window, rate -> AacLcEncoder.decodePeak(window, rate, 2) },
    private val createEncoder: (AacOutputSettings) -> AacEncoderPort = { config ->
        object : AacEncoderPort {
            private val implementation = AacLcEncoder(config.sampleRate, 2, config.bitRate, config.wrapAdts, log)
            override val isRunning get() = implementation.started
            override fun start() = implementation.start()
            override fun encode(pcm: ByteArray, size: Int, offset: Int) = implementation.encode(pcm, size, offset)
            override fun stop() = implementation.stop()
        }
    },
) {
    val frameBytes = AacLcEncoder.pcmBytes(2)
    private val diagnosticId = Integer.toHexString(System.identityHashCode(this))
    private var encoder: AacEncoderPort? = null
    private val frames = PcmFrameBuffer(frameBytes)
    private val resampler = StreamingPcmResampler(settings.sampleRate)
    private val probe = AacPassthroughProbe()
    private var lastInputAt = 0L
    private var failureReported = false
    private var timingStart = 0L
    private var timingCalls = 0
    private var timingFrames = 0
    private var wallMax = 0L
    private var cpuMax = 0L
    private val emit = PcmFrameConsumer { source, offset -> dispatch(source, offset, frameBytes) }

    @Synchronized fun start(): Boolean {
        if (encoder != null) return true
        val created = createEncoder(settings)
        if (!created.start()) { created.stop(); return false }
        encoder = created
        failureReported = false
        timingStart = now()
        log("AUDIO-DIAG: AAC backend=$diagnosticId config=$settings")
        return true
    }

    @Synchronized fun feed(pcm: ByteArray, size: Int, rate: Int, channels: Int, gap: Boolean) {
        if (encoder == null) return
        val at = now()
        if (gap) {
            frames.clear(); resampler.reset()
            if (lastInputAt > 0 && at - lastInputAt >= settings.longGapNs && (0 until size).any { pcm[it] != 0.toByte() })
                longGap("backend=$diagnosticId real-input discontinuity intervalMs=${(at - lastInputAt) / 1_000_000}")
        }
        lastInputAt = at
        if ((rate <= 0 || rate == settings.sampleRate) && channels != 1) {
            resampler.reset(); frames.feed(pcm, size, emit)
        } else {
            val converted = resampler.convert(pcm, size, rate.takeIf { it > 0 } ?: settings.sampleRate, if (channels == 1) 1 else 2)
            frames.feed(converted, converted.size, emit)
        }
    }

    @Synchronized fun encode(pcm: ByteArray, offset: Int, size: Int): List<ByteArray> {
        val active = encoder ?: return emptyList()
        val wallStart = now()
        val cpuStart = cpuNow()
        val payloads = active.encode(pcm, size, offset)
        val finished = now()
        val wall = (finished - wallStart).coerceAtLeast(0)
        timingCalls++; timingFrames += payloads.size
        wallMax = maxOf(wallMax, wall)
        cpuMax = maxOf(cpuMax, (cpuNow() - cpuStart).coerceAtLeast(0))
        if (finished - timingStart >= 30_000_000_000L || wall >= 500_000_000L && finished - timingStart >= 5_000_000_000L) {
            log("AUDIO-DIAG: AAC-ENCODE backend=$diagnosticId windowMs=${(finished - timingStart) / 1_000_000} calls=$timingCalls outputFrames=$timingFrames wallMaxUs=${wallMax / 1000} cpuMaxUs=${cpuMax / 1000}; encode only, UDP measured separately")
            timingStart = finished; timingCalls = 0; timingFrames = 0; wallMax = 0; cpuMax = 0
        }
        if (!active.isRunning && !failureReported) {
            failureReported = true
            longGap("backend=$diagnosticId encoder-stopped unexpectedly")
        }
        probe.observe(pcm, offset, size, payloads)?.let { window ->
            if (window.isEmpty()) log("AAC-PROBE: skipped oversized output window")
            else probeWork {
                val decoded = decodeWindow(window, settings.sampleRate)
                log("AAC-PROBE: backend=$diagnosticId rate=${settings.sampleRate} afterNonzeroInput=true frames=${window.size} bytes=${window.sumOf { it.size }} " +
                    "decodedPeak=${decoded?.first} decodedSamples=${decoded?.second}; local codec check only, receiver output unverified")
            }
        }
        return payloads
    }

    @Synchronized fun dispatch(pcm: ByteArray, offset: Int, size: Int): Boolean {
        if (encoder == null) return false
        return encode(pcm, offset, size).all(send)
    }

    @Synchronized fun stop() {
        encoder?.stop(); encoder = null
        frames.clear(); resampler.reset(); lastInputAt = 0L
    }
}

package io.github.shaonialife.carptocar.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owned by the 9241 packetization worker. One native call per input block. */
internal class OboeSpeechResampler : AutoCloseable {
    private var handle = 0L
    private val output by lazy(LazyThreadSafetyMode.NONE) {
        // Input blocks are capped at 200 ms, including for non-integer ratios.
        ByteBuffer.allocateDirect(OUTPUT_RATE * 2 / 5).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun process(pcm: ByteArray, inputRate: Int, channels: Int): ByteBuffer {
        if (handle == 0L) {
            handle = try {
                OboeResamplerNative.create(inputRate, channels)
            } catch (error: LinkageError) {
                throw IllegalStateException("Oboe resampler unavailable", error)
            }
            check(handle != 0L) { "Oboe resampler allocation failed" }
        }
        val samples = OboeResamplerNative.process(handle, pcm, output)
        check(samples >= 0) {
            "Oboe resampler produced $samples samples from ${pcm.size} bytes"
        }
        output.clear()
        output.limit(samples * 2)
        return output
    }

    // The upstream module has no reset API. Drop its history at the existing prompt boundaries.
    fun reset() {
        if (handle == 0L) return
        OboeResamplerNative.destroy(handle)
        handle = 0L
    }

    override fun close() = reset()

    companion object {
        const val OUTPUT_RATE = 48_000
    }
}

private object OboeResamplerNative {
    init {
        System.loadLibrary("carptocar_oboe_resampler")
    }

    external fun create(inputRate: Int, channels: Int): Long
    external fun process(handle: Long, pcm: ByteArray, output: ByteBuffer): Int
    external fun destroy(handle: Long)
}

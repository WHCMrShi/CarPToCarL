package io.github.shaonialife.carptocar.audio

/** Independent immutable codec policies. No AAC optimisation can mutate the PCM policy. */
internal object PcmOutputSettings {
    const val INGRESS_NS = 200_000_000L
    const val INGRESS_BYTES = 64 * 1024
    const val SPEECH_NS = 10_000_000_000L
    const val SPEECH_BYTES = 512 * 1024
    val protocol = AudioTuning.PCM_NATIVE
}

internal data class AacOutputSettings(
    val sampleRate: Int,
    val bitRate: Int = 192_000,
    val wrapAdts: Boolean = false,
    val ingressNs: Long = 200_000_000L,
    val ingressBytes: Int = 64 * 1024,
    val longGapNs: Long = 750_000_000L,
    val idleFillMs: Long = 300L,
    val stoppedFillMs: Long = 50L,
    val fillTickMs: Long = 100L,
    val maxFillFrames: Int = 16,
    val speechNs: Long = 10_000_000_000L,
    val speechBytes: Int = 512 * 1024,
) {
    val protocol = AudioTuning.RAW_SPF
}

internal fun protocolFor(codec: AudioCodec): AudioTuning =
    if (codec == AudioCodec.PCM) PcmOutputSettings.protocol else AacOutputSettings(48_000).protocol

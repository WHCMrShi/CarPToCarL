package io.github.shaonialife.carptocar.audio

/** Little-endian s16 -> stereo s16. Integer phase and last samples survive block boundaries. */
internal class StreamingPcmResampler(private val targetRate: Int) {
    private var rate = 0
    private var channels = 0
    private var phase = 0L
    private var previousLeft = 0
    private var previousRight = 0
    private var primed = false
    fun reset() { rate = 0; channels = 0; phase = 0; primed = false }
    fun convert(input: ByteArray, size: Int, sourceRate: Int, sourceChannels: Int): ByteArray {
        require(sourceRate > 0 && targetRate > 0 && sourceChannels in 1..2 && size in 0..input.size)
        require(size % (sourceChannels * 2) == 0)
        if (rate != sourceRate || channels != sourceChannels) { reset(); rate = sourceRate; channels = sourceChannels }
        val frames = size / (sourceChannels * 2)
        val output = ByteArray(((frames.toLong() * targetRate + phase) / sourceRate * 4).toInt())
        fun read(at: Int) = ((input[at].toInt() and 255) or (input[at + 1].toInt() shl 8)).toShort().toInt()
        var writeAt = 0
        fun write(value: Int) { output[writeAt++] = value.toByte(); output[writeAt++] = (value shr 8).toByte() }
        for (index in 0 until frames) {
            val at = index * sourceChannels * 2
            val left = read(at)
            val right = if (sourceChannels == 1) left else read(at + 2)
            if (!primed) { previousLeft = left; previousRight = right; primed = true }
            phase += targetRate
            while (phase >= sourceRate) {
                phase -= sourceRate
                val fraction = targetRate - phase
                write((previousLeft + (left - previousLeft).toLong() * fraction / targetRate).toInt())
                write((previousRight + (right - previousRight).toLong() * fraction / targetRate).toInt())
            }
            previousLeft = left; previousRight = right
        }
        check(writeAt == output.size)
        return output
    }
}

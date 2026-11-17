package io.github.shaonialife.carptocar.audio

/** Streaming 44.1k -> 48k, stereo s16le. Caller serializes access.
 * Output position advances by exactly 147/160 input frames. Linear interpolation
 * uses the two surrounding real samples; the last fractional interval waits for
 * the next input frame, never repeats or fabricates a boundary sample.
 */
internal class Pcm441To480 {
    private var hasPrevious = false
    private var left = 0
    private var right = 0
    // Next output position relative to the previous input frame, in 1/160 units.
    private var phase = 147

    fun reset() {
        hasPrevious = false
        phase = 147
    }

    fun convert(src: ByteArray): ByteArray {
        val frames = src.size / 4
        val out = ByteArray(((frames * 160 + 146) / 147 + 2) * 4)
        var written = 0
        fun sample(offset: Int): Int =
            ((src[offset].toInt() and 255) or (src[offset + 1].toInt() shl 8)).toShort().toInt()
        fun emit(l: Int, r: Int) {
            out[written++] = l.toByte()
            out[written++] = (l shr 8).toByte()
            out[written++] = r.toByte()
            out[written++] = (r shr 8).toByte()
        }
        for (frame in 0 until frames) {
            val nextLeft = sample(frame * 4)
            val nextRight = sample(frame * 4 + 2)
            if (!hasPrevious) {
                emit(nextLeft, nextRight)
                hasPrevious = true
            } else {
                while (phase <= 160) {
                    // Convex weights cannot overshoot s16; round to the nearest integer.
                    emit(Math.floorDiv(left * (160 - phase) + nextLeft * phase + 80, 160),
                        Math.floorDiv(right * (160 - phase) + nextRight * phase + 80, 160))
                    phase += 147
                }
                phase -= 160
            }
            left = nextLeft
            right = nextRight
        }
        return out.copyOf(written)
    }
}

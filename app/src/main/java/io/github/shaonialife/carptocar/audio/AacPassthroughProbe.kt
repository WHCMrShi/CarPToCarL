package io.github.shaonialife.carptocar.audio

/** One bounded window after non-zero input; accommodates preceding codec priming/silence. */
internal class AacPassthroughProbe {
    private var armed = false
    private var completed = false
    private val frames = ArrayList<ByteArray>(16)
    fun observe(pcm: ByteArray, offset: Int, size: Int, encoded: List<ByteArray>): List<ByteArray>? {
        if (completed) return null
        if (!armed) armed = (offset until offset + size).any { pcm[it] != 0.toByte() }
        if (!armed) return null
        for (frame in encoded) {
            if (frame.size > 8_192) { completed = true; frames.clear(); return emptyList() }
            frames.add(frame.copyOf())
            if (frames.size == 16) {
                completed = true
                return frames.toList().also { frames.clear() }
            }
        }
        return null
    }
}

package io.github.shaonialife.carptocar.audio

internal fun interface PcmFrameConsumer {
    fun emit(source: ByteArray, offset: Int): Boolean
}

/** 调用方串行访问。完整帧借用输入，只有不足一帧的尾部被复制；emit 必须同步消费。 */
internal class PcmFrameBuffer(private val frameBytes: Int) {
    init { require(frameBytes > 0) }
    private val tail = ByteArray(frameBytes)
    private var filled = 0

    fun clear() { filled = 0 }

    fun feed(source: ByteArray, size: Int, emit: PcmFrameConsumer) {
        require(size in 0..source.size)
        var offset = 0
        if (filled > 0) {
            val count = minOf(frameBytes - filled, size)
            source.copyInto(tail, filled, 0, count)
            filled += count
            offset += count
            if (filled < frameBytes) return
            filled = 0
            if (!emit.emit(tail, 0)) { clear(); return }
        }
        while (size - offset >= frameBytes) {
            if (!emit.emit(source, offset)) { clear(); return }
            offset += frameBytes
        }
        filled = size - offset
        if (filled > 0) source.copyInto(tail, 0, offset, size)
    }
}

package io.github.shaonialife.carptocar.video

/** 输入在同步发送期间只读；跨帧缓存必须调用 ownedCopy。 */
internal class NalSlice(val data: ByteArray, val offset: Int, val size: Int) {
    init { require(offset >= 0 && size >= 0 && offset <= data.size - size) }
    operator fun get(index: Int): Byte { require(index in 0 until size); return data[offset + index] }
    fun isNotEmpty(): Boolean = size > 0
    fun copyBytes(): ByteArray = data.copyOfRange(offset, offset + size)
    fun ownedCopy(): NalSlice = copyBytes().let { NalSlice(it, 0, it.size) }
    fun matches(bytes: ByteArray): Boolean = size == bytes.size && bytes.indices.all { data[offset + it] == bytes[it] }
}

internal object AnnexBNals {
    fun split(bytes: ByteArray): List<NalSlice> {
        val units = ArrayList<NalSlice>()
        var bodyStart = -1
        var index = 0
        // 与原实现一致：识别 3/4 字节起始码，保留 NAL 内容及尾部字节。
        while (index + 3 < bytes.size) {
            val prefix = when {
                bytes[index] != 0.toByte() || bytes[index + 1] != 0.toByte() -> 0
                bytes[index + 2] == 1.toByte() -> 3
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) {
                if (bodyStart >= 0 && index > bodyStart) units += NalSlice(bytes, bodyStart, index - bodyStart)
                bodyStart = index + prefix
                index += prefix
            } else index++
        }
        if (bodyStart < 0) {
            if (bytes.isNotEmpty()) units += NalSlice(bytes, 0, bytes.size)
        } else if (bodyStart < bytes.size) units += NalSlice(bytes, bodyStart, bytes.size - bodyStart)
        return units
    }

    fun avccSize(units: List<NalSlice>): Int {
        val size = units.sumOf { it.size.toLong() + 4 }
        require(size <= Int.MAX_VALUE - 144) { "Video frame too large" }
        return size.toInt()
    }

    fun pack(units: List<NalSlice>, target: ByteArray, start: Int) {
        var cursor = start
        for (unit in units) {
            for (i in 0..3) target[cursor + i] = (unit.size ushr (24 - i * 8)).toByte()
            cursor += 4
            unit.data.copyInto(target, cursor, unit.offset, unit.offset + unit.size)
            cursor += unit.size
        }
    }
}

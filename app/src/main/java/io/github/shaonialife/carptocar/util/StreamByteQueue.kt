package io.github.shaonialife.carptocar.util

/** 单读线程拥有的滑动缓冲；消费只移动游标，空间不足时才压缩/扩容。 */
internal class StreamByteQueue(
    private val initialCapacity: Int = 8192,
    private val retainedLimit: Int = 262_144,
) {
    var storage = ByteArray(initialCapacity)
        private set
    var start = 0
        private set
    private var end = 0
    val size: Int get() = end - start

    fun append(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return
        val needed = size.toLong() + length
        require(needed <= Int.MAX_VALUE - 8L)
        if (storage.size - end < length) {
            val count = size
            if (needed <= storage.size) {
                storage.copyInto(storage, 0, start, end)
            } else {
                val capacity = maxOf(needed, minOf(Int.MAX_VALUE - 8L, maxOf(1L, storage.size.toLong() * 2))).toInt()
                val next = ByteArray(capacity)
                storage.copyInto(next, 0, start, end)
                storage = next
            }
            start = 0
            end = count
        }
        bytes.copyInto(storage, end, offset, offset + length)
        end += length
    }

    fun discard(count: Int) {
        require(count in 0..size)
        start += count
        if (start == end) {
            start = 0
            end = 0
            if (storage.size > retainedLimit) storage = ByteArray(initialCapacity)
        }
    }

    fun copy(
        offset: Int,
        length: Int,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= size - length)
        return storage.copyOfRange(start + offset, start + offset + length)
    }

    fun indexOf(
        needle: ByteArray,
        from: Int = 0,
    ): Int {
        require(needle.isNotEmpty() && from >= 0)
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (storage[start + i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

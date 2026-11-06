package io.github.shaonialife.carptocar.carlife

import java.io.DataInputStream

/** 每读线程独享；borrow 仅适用于返回前消费/复制全部有效字节的同步回调。 */
internal class PacketPayloadBuffer(
    private val maxRetained: Int = 65_536,
) {
    private var retained = ByteArray(0)
    var lastAllocatedBytes = 0
        private set

    fun read(
        input: DataInputStream,
        length: Int,
        borrow: Boolean,
    ): ByteArray {
        require(length >= 0)
        lastAllocatedBytes = 0
        if (length == 0) return EMPTY
        val bytes =
            if (!borrow || length > maxRetained) {
                lastAllocatedBytes = length
                ByteArray(length)
            } else {
                if (retained.size < length) {
                    val capacity = maxOf(length, minOf(maxRetained, maxOf(1024, retained.size * 2)))
                    retained = ByteArray(capacity)
                    lastAllocatedBytes = capacity
                }
                retained
            }
        input.readFully(bytes, 0, length)
        return bytes
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

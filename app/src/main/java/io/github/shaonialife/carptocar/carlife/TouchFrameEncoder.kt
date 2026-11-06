package io.github.shaonialife.carptocar.carlife

/** 只由触摸 worker 使用；write 返回前不得再次编码。线格式等同 packCommand + touchPoint/action。 */
internal class TouchFrameEncoder {
    val bytes = ByteArray(32)
    private var end = 8

    private fun field(
        tag: Int,
        input: Int,
    ) {
        bytes[end++] = tag.toByte()
        var value = input.toLong()
        do {
            val low = (value and 127).toInt()
            value = value ushr 7
            bytes[end++] = (low or if (value != 0L) 128 else 0).toByte()
        } while (value != 0L)
    }

    fun encode(
        service: Int,
        x: Int,
        y: Int,
        action: Int,
    ): Int {
        end = 8
        if (service == CarLifeProtocol.TOUCH_ACTION) {
            field(8, action)
            field(16, x)
            field(24, y)
        } else {
            field(8, x)
            field(16, y)
        }
        val size = end - 8
        bytes[0] = (size ushr 8).toByte()
        bytes[1] = size.toByte()
        bytes[2] = 0
        bytes[3] = 0
        for (i in 0..3) bytes[4 + i] = (service ushr (24 - 8 * i)).toByte()
        return end
    }
}

package io.github.shaonialife.carptocar.carlife

import java.io.ByteArrayOutputStream

/** HUD 订阅及导航 wire 解码：严格检查边界，畸形消息只影响本条。 */
internal object CarLifeHudProtocol {
    const val NEXT_TURN = 0x10030
    const val ASSISTANT_GUIDE = 0x10047
    const val SUBSCRIBE = 0x18043
    const val SUBSCRIBE_DONE = 0x10044
    const val START = 0x18045
    val modules = listOf(0, 1) // 数据订阅编号；不是模块状态中的 NAVI=2 / EDOG=8。

    fun parse(payload: ByteArray) = CarLifeProtobuf.parse(payload)

    /** START 和 SUBSCRIBE 都带列表，显式编码编号 0，不能按 proto3 默认值省略。 */
    fun subscription(ids: List<Int>): ByteArray = ByteArrayOutputStream().apply {
        require(ids.size <= 2 && ids.all { it in modules })
        write(8); write(ids.size)
        // v2.3.0 指南要求 supportFlag；老 SDK 将其定义为 optional，同样可接收。
        ids.forEach { write(byteArrayOf(0x12, 4, 8, it.toByte(), 16, 1)) }
    }.toByteArray()

    data class SubscriptionReply(val entries: List<Pair<Int?, Int?>>, val error: String?)
    fun reply(payload: ByteArray): SubscriptionReply {
        val outer = parse(payload)
        var error = outer.error
        val entries = outer.items.filter { it.number == 2 && it.wire == 2 }.map {
            val inner = parse(it.bytes!!)
            if (inner.error != null) error = inner.error
            inner.int(1) to inner.int(2)
        }
        if (outer.int(1) != entries.size) error = error ?: "count-mismatch-or-missing"
        if (entries.any { it.first == null } || entries.map { it.first }.distinct().size != entries.size) {
            error = error ?: "missing-or-duplicate-module"
        }
        if (entries.any { it.second != 0 && it.second != 1 }) error = error ?: "missing-or-invalid-support-flag"
        return SubscriptionReply(entries, error)
    }

    fun turn(payload: ByteArray): io.github.shaonialife.carptocar.media.HudTurn? {
        val fields = parse(payload)
        if (fields.error != null) return null
        return io.github.shaonialife.carptocar.media.HudTurn(
            fields.int(1) ?: return null, fields.int(2) ?: return null,
            fields.bytes(3)?.toString(Charsets.UTF_8)?.take(512) ?: return null,
            fields.int(4)?.takeIf { it >= 0 } ?: return null, fields.bytes(6),
        )
    }
}

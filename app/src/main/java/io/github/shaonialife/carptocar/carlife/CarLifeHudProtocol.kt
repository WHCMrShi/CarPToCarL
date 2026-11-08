package io.github.shaonialife.carptocar.carlife

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** HUD 观测用 wire 解码：严格检查边界，畸形消息只影响本条，不中断控制连接。 */
internal object CarLifeHudProtocol {
    const val NEXT_TURN = 0x10030
    const val ASSISTANT_GUIDE = 0x10047
    const val SUBSCRIBE = 0x18043
    const val SUBSCRIBE_DONE = 0x10044
    const val START = 0x18045
    val modules = listOf(0, 1) // 数据订阅编号；不是模块状态中的 NAVI=2 / EDOG=8。

    data class Field(val number: Int, val wire: Int, val value: Long? = null, val bytes: ByteArray? = null)
    data class Fields(val items: List<Field>, val error: String?) {
        fun int(number: Int): Int? = items.lastOrNull { it.number == number && it.wire == 0 }?.value?.toInt()
        fun bytes(number: Int): ByteArray? = items.lastOrNull { it.number == number && it.wire == 2 }?.bytes
        fun shape(): String = items.joinToString(",") { "${it.number}/${it.wire}" + (it.bytes?.let { b -> ":${b.size}B" } ?: "") }
    }

    fun parse(payload: ByteArray): Fields {
        val fields = mutableListOf<Field>()
        var index = 0
        fun varint(): Long {
            var value = 0L
            for (i in 0..9) {
                require(index < payload.size) { "truncated-varint" }
                val b = payload[index++].toInt() and 255
                require(i != 9 || b <= 1) { "overflow-varint" }
                value = value or ((b and 127).toLong() shl (i * 7))
                if (b and 128 == 0) return value
            }
            error("overflow-varint")
        }
        return try {
            require(payload.size <= 65535) { "oversized-command" }
            while (index < payload.size) {
                require(fields.size < 128) { "too-many-fields" }
                val tag = varint()
                require(tag in 8..0xffffffffL) { "invalid-tag" }
                val number = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (wire == 0) {
                    fields += Field(number, wire, value = varint())
                } else {
                    val length = when (wire) {
                        1 -> 8L
                        2 -> varint()
                        5 -> 4L
                        else -> error("unsupported-wire-$wire")
                    }
                    require(length >= 0 && length <= payload.size - index) { "truncated-field-$number" }
                    val end = index + length.toInt()
                    fields += Field(number, wire, bytes = if (wire == 2) payload.copyOfRange(index, end) else null)
                    index = end
                }
            }
            Fields(fields, null)
        } catch (e: IllegalArgumentException) {
            Fields(fields, e.message)
        } catch (e: IllegalStateException) {
            Fields(fields, e.message)
        }
    }

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

    fun describe(id: Int, payload: ByteArray): String {
        val f = parse(payload)
        val body = if (id == NEXT_TURN) {
            val road = f.bytes(3)?.toString(Charsets.UTF_8)?.take(512)?.let(::quoted)
            val icon = f.bytes(6)
            val iconInfo = icon?.let {
                "${it.size}B sha256=${MessageDigest.getInstance("SHA-256").digest(it).hex()} head=${it.take(12).toByteArray().hex()}"
            } ?: "absent"
            "NEXT_TURN action=${f.int(1)} nextTurn=${f.int(2)} roadName=$road " +
                "totalDistanceM=${f.int(4)} remainDistanceM=${f.int(5)} legacyTime=${f.int(6)} icon=$iconInfo"
        } else {
            "ASSISTANT_GUIDE action=${f.int(1)} assistantType=${f.int(2)} trafficSignType=${f.int(3)} " +
                "totalDistanceM=${f.int(4)} remainDistanceM=${f.int(5)} cameraSpeedRaw=${f.int(6)}"
        }
        return "$body id=0x${id.toString(16)} bytes=${payload.size} fields=[${f.shape()}] parseError=${f.error ?: "none"}"
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun quoted(s: String): String = "\"" + s.flatMap {
        when {
            it == '\\' -> "\\\\".toList()
            it == '"' -> "\\\"".toList()
            it.isISOControl() || it == '\u2028' || it == '\u2029' -> "\\u%04x".format(it.code).toList()
            else -> listOf(it)
        }
    }.joinToString("") + "\""
}

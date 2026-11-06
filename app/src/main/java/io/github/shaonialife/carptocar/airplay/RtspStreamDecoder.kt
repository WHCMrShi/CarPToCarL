package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.util.StreamByteQueue

/** 控制与事件通道共用；只在头完整时解析一次，body 始终返回独立数组。 */
internal class RtspStreamDecoder {
    class Message(
        val firstLine: String,
        val head: String,
        val body: ByteArray,
    )

    private val pending = StreamByteQueue()
    private var scanAt = 0
    private var head: String? = null
    private var firstLine = ""
    private var bodyStart = 0
    private var bodyLength = 0

    fun offer(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<Message> {
        pending.append(bytes, offset, length)
        var result: ArrayList<Message>? = null
        while (true) {
            if (head == null) {
                val end = pending.indexOf(SEPARATOR, scanAt)
                if (end < 0) {
                    scanAt = maxOf(0, pending.size - SEPARATOR.size + 1)
                    break
                }
                val text = String(pending.storage, pending.start, end, Charsets.ISO_8859_1)
                val parsed = parseHead(text)
                head = text
                firstLine = parsed.first
                bodyLength = parsed.second
                bodyStart = end + SEPARATOR.size
            }
            if (pending.size - bodyStart < bodyLength) break
            if (result == null) result = ArrayList()
            result.add(Message(firstLine, head!!, pending.copy(bodyStart, bodyLength)))
            pending.discard(bodyStart + bodyLength)
            head = null
            scanAt = 0
        }
        return result ?: emptyList()
    }

    companion object {
        private val CONTENT_LENGTH_REGEX = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val SEPARATOR = byteArrayOf(13, 10, 13, 10)

        fun parseHead(headText: String): Pair<String, Int> {
            val contentLength =
                CONTENT_LENGTH_REGEX
                    .find(headText)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: 0
            val firstLine = headText.lineSequence().firstOrNull() ?: ""
            return firstLine to contentLength
        }
    }
}

package io.github.shaonialife.carptocar.util

/** 调用方持有 consoleLock；清屏后的旧代次回调不得恢复旧内容。 */
internal class ConsoleLogBuffer {
    private val lines = StringBuilder()
    private var generation = 0L

    fun append(line: String, epoch: Long): Boolean {
        if (epoch < generation) return false
        lines.append(line)
        if (lines.length > 40_000) lines.delete(0, lines.length - 20_000)
        return true
    }

    fun clear(epoch: Long) {
        generation = epoch
        lines.setLength(0)
    }

    fun drain(): String = lines.toString().also { lines.setLength(0) }
}

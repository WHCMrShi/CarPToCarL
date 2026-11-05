package io.github.shaonialife.carptocar.util

import java.io.File
import java.io.FileOutputStream

/** 仅由日志写线程调用；启动边界独立于可淘汰的数据队列。 */
internal class SessionLogFile(
    private val file: File,
    private val marker: String,
    private val maxBytes: Long = 2_000_000,
) {
    private var started = false

    fun write(batch: List<String>, dropped: Int) {
        val rotate = file.length() > maxBytes
        FileOutputStream(file, !rotate).writer(Charsets.UTF_8).buffered(64 * 1024).use { out ->
            if (!started || rotate) out.append(marker).append('\n')
            if (dropped > 0) out.append("LOG: 队列过载，省略 $dropped 条旧记录\n")
            batch.forEach { out.append(it) }
        }
        started = true
    }
}

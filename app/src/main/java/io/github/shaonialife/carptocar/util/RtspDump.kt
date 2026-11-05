package io.github.shaonialife.carptocar.util

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RTSP 报文全量落盘（SETUP / RECORD / 音频 SETUP 等）。
 *
 * 与 [SessionTrace] 的分工：SessionTrace 只在**连接失败**时留快照；本类**每次都写**，
 * 目的是一次实车就能拿到车机拒绝（例如 `RECORD 500`）时双方交换的原始字节，
 * 事后可直接与真 iPhone 的抓包对照，而不是靠猜。
 *
 * 文件：`filesDir/rtsp-YYYYMMDD.log`，单文件上限 8MB，超出转 `.1.log`。
 * 每条包含：时间、方向（TX/RX）、首行（方法+路径 或 状态行）、头部全文、body 十六进制（最多 4KB）。
 * 默认开启；`enabled=false` 可整体关闭。
 */
object RtspDump {
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_BODY_BYTES = 4096
    private const val MAX_HEAD_CHARS = 2048

    @Volatile
    private var dir: File? = null

    @Volatile
    var enabled: Boolean = true

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private const val HEX = "0123456789abcdef"
    private val writer =
        AsyncLogWriter("rtsp-log-writer") { batch, dropped ->
            val base = dir ?: return@AsyncLogWriter
            val file = File(base, "rtsp-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.log")
            if (file.length() > MAX_FILE_BYTES) {
                val backup = File(base, file.nameWithoutExtension + ".1.log")
                backup.delete()
                file.renameTo(backup)
            }
            FileOutputStream(file, true).writer(Charsets.UTF_8).buffered(64 * 1024).use { out ->
                if (dropped > 0) out.append("LOG: 队列过载，省略 $dropped 条 RTSP 记录\n")
                batch.forEach { out.append(it) }
            }
        }

    fun init(filesDir: File) {
        dir = filesDir
    }

    fun tx(
        label: String,
        head: String,
        body: ByteArray,
    ) = write("TX", label, head, body)

    fun rx(
        label: String,
        head: String,
        body: ByteArray,
    ) = write("RX", label, head, body)

    private fun write(
        tag: String,
        label: String,
        head: String,
        body: ByteArray,
    ) {
        if (!enabled) return
        if (dir == null) return
        runCatching {
            synchronized(lock) {
                val text =
                    buildString {
                        append('[').append(stamp.format(Date())).append("] ").append(tag).append(' ').append(label).append('\n')
                        if (head.isNotEmpty()) append(head.take(MAX_HEAD_CHARS)).append('\n')
                        if (body.isNotEmpty()) {
                            val n = minOf(body.size, MAX_BODY_BYTES)
                            append("body=").append(body.size).append("B hex=")
                            for (i in 0 until n) {
                                val value = body[i].toInt() and 0xff
                                append(HEX[value ushr 4]).append(HEX[value and 15])
                            }
                            if (body.size > n) append("…(truncated)")
                            append('\n')
                        }
                        append('\n')
                    }
                writer.append(text)
            }
        }.onFailure { Log.w("RtspDump", "write failed: ${it.message}") }
    }
}

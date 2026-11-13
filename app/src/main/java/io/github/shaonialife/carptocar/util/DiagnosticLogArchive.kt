package io.github.shaonialife.carptocar.util

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** 仅由日志工作线程访问；保留本进程最早诊断和最近两段，不受高频主日志覆盖。 */
internal class DiagnosticLogArchive(private val main: File, private val limit: Long = 512_000) {
    private val start = File(main.path + ".diagnostic-start")
    private val recent = File(main.path + ".diagnostic-recent")
    private val previous = File(main.path + ".diagnostic-previous")
    private var initialized = false
    private var startFull = false

    fun write(lines: List<String>, dropped: Int) {
        if (!initialized) {
            start.writeText(""); recent.writeText(""); previous.delete()
            initialized = true
        }
        val text = buildList {
            if (dropped > 0) add("LOG: 诊断归档前队列已丢弃 $dropped 条记录\n")
            lines.forEach { if (isDiagnostic(it)) add(redact(it)) }
        }
        if (text.isEmpty()) return
        var startBytes = start.length()
        var recentBytes = recent.length()
        var recentOutput: OutputStream? = null
        // The writer already supplies batches; open each sidecar per batch, not per line.
        (if (startFull) null else FileOutputStream(start, true).buffered()).use { startOutput ->
            try {
                for (line in text) {
                    val bytes = line.toByteArray(Charsets.UTF_8)
                    if (!startFull && startBytes + bytes.size <= limit) {
                        startOutput!!.write(bytes)
                        startBytes += bytes.size
                    } else startFull = true
                    if (recentBytes + bytes.size > limit) {
                        recentOutput?.close()
                        recentOutput = null
                        recent.copyTo(previous, overwrite = true)
                        recent.writeText("")
                        recentBytes = 0
                    }
                    val output = recentOutput ?: FileOutputStream(recent, true).buffered().also { recentOutput = it }
                    output.write(bytes)
                    recentBytes += bytes.size
                }
            } finally {
                recentOutput?.close()
            }
        }
    }

    companion object {
        private val tags = Regex("(?:STAGE:|CONNECT-DIAG:|ARTWORK:|BT-C:|BT:|WIFI:|CTRL:|NSD:|PROC:|SESSION |LINK closed:|PAIR|pair-setup:|pair-verify:|AIRPLAY: (?:端点|连接候选|重试|配对|本次 close|✓|初始 SETUP|音频 SETUP)|EVENT: 读取结束)")
        internal fun isDiagnostic(line: String): Boolean {
            if (line.contains("RAW tx:") || line.contains("RAW rx:") || line.contains("原始体=")) return false
            if (listOf("IDLE:", "APPROACH:", "SVC:", "WiFi:", "iap2 并行兜底", "iap2 rx=0x5703").any(line::contains)) return true
            if (listOf("WIFI-ID:", "WIFI-POLICY:", "WIFI-MEMORY:", "BT-PROFILE:").any(line::contains)) return true
            if (listOf("BT-ACTION:", "BT-RELEASE:", "BT-LIFECYCLE:", "BT-GUARD:", "BT-DISABLE:").any(line::contains)) return true
            if (line.contains("NOWPLAYING-DIAG:") || line.contains("NAV-MEDIA:")) return true
            if (listOf("MEDIA-STAGE:", "VIDEO-WRITE:", "VIDEO-QUEUE:", "VIDEO-PATH:", "CARLIFE-DIAG:", "EVENT-DIAG:").any(line::contains)) return true
            if (line.contains("CARLIFE:") && listOf("控制端口", "版本握手", "版本匹配", "首帧", "通道断开", "通道已重连", "等待本机引擎").any(line::contains)) return true
            return tags.containsMatchIn(line) || line.contains("PATH-DIAG:") || line.contains("HANDOVER-DIAG:") || line.contains("RESOURCE-DIAG:") || line.contains("KEYFRAME-DIAG:") || line.contains("HUD-DIAG:") || line.contains("AUDIO-DIAG:") || line.contains("PROFILE:") || line.contains("PROTO!") ||
                line.contains("iap2 0x1d01") ||
                (line.contains("IAPLINK:") && listOf("清单", "链路", "MFi", "订阅").any(line::contains))
        }
        private val secret = Regex("(?i)(writeKey|readKey|sharedSecret|psk)=\\S+")
        internal fun redact(line: String): String = line.replace(secret, "$1=[REDACTED]")

        fun read(main: File): String = buildString {
            for ((suffix, title) in listOf(
                "diagnostic-start" to "本进程早期关键诊断（独立保留，可能与后文重复）",
                "diagnostic-previous" to "本进程关键诊断前段（中间可能已轮转）",
                "diagnostic-recent" to "本进程最近关键诊断（可能与主日志重复）",
            )) {
                val file = File(main.path + ".$suffix")
                if (file.exists() && file.length() > 0) {
                    append("\n===== $title =====\n")
                    append(file.readText(Charsets.UTF_8))
                }
            }
        }
    }
}

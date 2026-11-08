package io.github.shaonialife.carptocar.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试文件日志（files/main_debug.log）。
 *
 * 荣耀机型 logcat 被 HKS 加密，本文件是 adb 侧唯一调试通道（run-as 读取）。
 * MainActivity 与 CarPlayService 只入队，由单工作线程批量落盘；导出前等待此前记录写完。
 */
object DebugFileLog {
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var directory: File? = null
    private var logFile: SessionLogFile? = null // 仅写线程访问
    private val writer = AsyncLogWriter("debug-log-writer") { batch, dropped ->
        val base = directory ?: return@AsyncLogWriter
        val target = logFile ?: SessionLogFile(File(base, "main_debug.log"), SESSION_MARKER).also { logFile = it }
        target.write(batch, dropped)
    }

    /**
     * 「本次进程启动」标记行。日志文件跨进程保留（最多 2MB），用户反馈问题时
     * 历史轮次的日志会把真正有用的部分淹没——导出默认只取最后一条标记之后的内容。
     * 标记本身写进文件而不是只记内存偏移：文件可能被 2MB 截断，偏移会失效。
     */
    const val SESSION_MARKER = "===== CarPToCarL 进程启动（本次日志起点）====="

    /**
     * 改名前的标记（应用曾叫 CarDeck）。**故意保留旧字符串**：设备上可能存着旧版本写的
     * 日志文件，那时标记是旧名。只认新名会让这些文件退化成「无标记 = 返回全文」，
     * 用户导出历史日志时反而多出大量无关内容。
     */
    internal const val LEGACY_SESSION_MARKER = "===== CarDeck 进程启动（本次日志起点）====="

    /**
     * 写入本次启动标记。由 [io.github.shaonialife.carptocar.SenderApp.onCreate] 调用一次；
     * 重复调用会产生多条标记，导出仍取最后一条，行为安全。
     */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /**
     * 读取日志正文。[currentSessionOnly] = true 时只返回最后一条 [SESSION_MARKER]
     * 之后的内容（无标记时返回全文——首次安装/旧版本文件都属这种情形）。
     */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            if (!writer.flush()) return@runCatching null
            val file = File(context.filesDir, "main_debug.log")
            val text = file.readText(Charsets.UTF_8)
            val main = if (currentSessionOnly) currentSessionSlice(text) else text
            val diagnostics = runCatching { DiagnosticLogArchive.read(file) }
                .getOrElse { "\nLOG: 独立诊断读取失败 ${it.javaClass.simpleName}\n" }
            diagnostics + "\n===== 主日志（容量有限，前部可能已轮转）=====\n" + main
        }.getOrNull()

    /**
     * 取最后一条启动标记之后的内容（纯函数，单测覆盖边界）。
     * 无标记 → 全文：旧版本写入的文件里没有标记，此时全量比空更好。
     */
    internal fun currentSessionSlice(text: String): String {
        // 新旧两种标记都认，取最靠后的那个：同一个文件里可能前半段是旧版本写的。
        val best =
            listOf(SESSION_MARKER, LEGACY_SESSION_MARKER)
                .map { marker -> marker to text.lastIndexOf(marker) }
                .filter { it.second >= 0 }
                .maxByOrNull { it.second }
                ?: return text
        return text.substring(best.second + best.first.length)
    }

    fun append(
        context: Context,
        message: String,
    ) {
        directory = context.filesDir
        synchronized(timestamp) {
            writer.append("[${timestamp.format(Date())}] ${message.take(16_000)}\n")
        }
    }
}

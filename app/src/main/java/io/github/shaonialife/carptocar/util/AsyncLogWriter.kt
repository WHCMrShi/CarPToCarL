package io.github.shaonialife.carptocar.util

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 有界内存队列：生产者不做 IO；单工作线程每批最多写 256 条，突发过载保留最近记录。 */
internal class AsyncLogWriter(
    name: String,
    private val maxChars: Int = 1_000_000,
    private val maxLines: Int = 2_048,
    private val sink: (List<String>, Int) -> Unit,
) {
    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var chars = 0
    private var dropped = 0
    private var scheduled = false
    private var writeFailed = false // 仅工作线程访问；记录本进程曾丢失的写入。
    private val worker =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, name).apply { isDaemon = true }
        }

    fun append(text: String) {
        synchronized(lock) {
            val bounded = text.take(minOf(maxChars, 16_384))
            while (queue.isNotEmpty() && (queue.size >= maxLines || chars + bounded.length > maxChars)) {
                chars -= queue.removeFirst().length
                dropped++
            }
            queue.addLast(bounded)
            chars += bounded.length
            if (!scheduled) {
                scheduled = true
                worker.schedule(::drain, 100, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun drain() {
        while (true) {
            val batch = mutableListOf<String>()
            val lost: Int
            synchronized(lock) {
                if (queue.isEmpty()) {
                    scheduled = false
                    return
                }
                var batchChars = 0
                while (queue.isNotEmpty() && batch.size < 256 && batchChars < 65_536) {
                    val line = queue.removeFirst()
                    chars -= line.length
                    batchChars += line.length
                    batch += line
                }
                lost = dropped
                dropped = 0
            }
            // IO 失败也不让后台线程退出或让调用方承担异常；后续批次仍可重试。
            runCatching { sink(batch, lost) }.onFailure { writeFailed = true }
        }
    }

    /** 导出在后台调用，等此前入队记录落盘；失败返回 false，避免无限等待。 */
    fun flush(timeoutMillis: Long = 5_000): Boolean {
        val pending = worker.submit<Boolean> {
            drain()
            !writeFailed
        }
        return try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            pending.cancel(false)
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            pending.cancel(false)
            false
        }
    }
}

package io.github.shaonialife.carptocar.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** 每个读取任务的总预算；资源关闭由仓库的后台清理线程执行，不阻塞媒体回调。 */
internal class ArtworkReadControl(
    timeoutMs: Long = 10_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val dispatchClose: (() -> Unit) -> Unit = { it() },
) {
    private val deadline = clock() + timeoutMs
    private val lock = Any()
    @Volatile var cancelled = false
        private set
    private var closeResource: (() -> Unit)? = null

    fun onCancel(close: () -> Unit) {
        val runNow = synchronized(lock) {
            if (cancelled) true else { closeResource = close; false }
        }
        if (runNow) dispatchClose { runCatching(close) }
    }

    fun cancel() {
        val close = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            closeResource.also { closeResource = null }
        }
        if (close != null) dispatchClose { runCatching(close) }
    }

    fun finish() = synchronized(lock) { closeResource = null }

    fun check() {
        if (clock() >= deadline) cancel()
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("artwork read cancelled or timed out")
    }

    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check()
            val count = input.read(buffer)
            check()
            if (count < 0) return output.toByteArray()
            if (output.size() + count > EncodedArtwork.MAX_BYTES) throw IOException("artwork exceeds 1 MiB")
            output.write(buffer, 0, count)
        }
    }
}

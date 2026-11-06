package io.github.shaonialife.carptocar.media

import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 只读编码图片；不把 Bitmap 放入协议队列，也不逐秒比较图片字节。 */
class EncodedArtwork private constructor(private val bytes: ByteArray, val digest: String) {
    val size: Int get() = bytes.size
    internal fun copyTo(target: ByteArray, targetOffset: Int, offset: Int, length: Int) =
        bytes.copyInto(target, targetOffset, offset, offset + length)

    companion object {
        internal const val MAX_BYTES = 1_048_576
        internal fun create(bytes: ByteArray): EncodedArtwork? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            val png = bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }
            if (!jpeg && !png) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return EncodedArtwork(bytes.copyOf(), digest)
        }
    }
}

class ArtworkSource internal constructor(
    val trackId: String,
    val revision: Long,
    internal val load: () -> ByteArray?,
    private val owner: ArtworkRepository,
) {
    internal var attempts = 0
    internal var requested = false
    internal var completed = false
    internal var failedAt = Long.MIN_VALUE
    internal fun request(): ArtworkResult = owner.request(this)
}

internal data class ArtworkResult(val complete: Boolean, val image: EncodedArtwork?)

/** 只有协商成功且请求封面的链路才调用 request；一个运行任务、一个最新待处理任务。 */
internal class ArtworkRepository(private val log: (String) -> Unit = {}) : AutoCloseable {
    private val lock = Any()
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "media-artwork-encode").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    private var closed = false
    private var current: ArtworkSource? = null
    private var image: EncodedArtwork? = null

    fun source(trackId: String, revision: Long, load: () -> ByteArray?): ArtworkSource = synchronized(lock) {
        current?.takeIf { it.trackId == trackId && it.revision == revision }?.let { return it }
        if (current?.trackId != trackId) image = null
        ArtworkSource(trackId, revision, load, this).also { current = it }
    }

    fun request(source: ArtworkSource): ArtworkResult = synchronized(lock) {
        if (closed || current !== source) return ArtworkResult(true, null)
        val now = System.nanoTime() / 1_000_000
        if (!source.requested && source.attempts < 3 && (!source.completed || (image == null && now - source.failedAt >= 5000))) {
            source.requested = true
            source.attempts++
            executor.execute {
                val useful = synchronized(lock) { !closed && current?.trackId == source.trackId }
                val result = try { if (useful) source.load()?.let(EncodedArtwork::create) else null }
                    catch (error: Exception) { log("ARTWORK: 图片准备失败 - ${error.javaClass.simpleName}"); null }
                synchronized(lock) {
                    source.requested = false
                    source.completed = true
                    source.failedAt = System.nanoTime() / 1_000_000
                    // 同曲后来的任务串行覆盖；切歌后绝不发布旧图。
                    if (!closed && current?.trackId == source.trackId) image = result
                }
            }
        }
        ArtworkResult(source.completed, image)
    }

    fun clear() = synchronized(lock) { current = null; image = null }

    override fun close() {
        synchronized(lock) { closed = true; current = null; image = null }
        executor.shutdownNow()
    }
}

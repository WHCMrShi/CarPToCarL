package io.github.shaonialife.carptocar.media

import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor

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
    internal val load: (ArtworkReadControl) -> ByteArray?,
    private val owner: ArtworkRepository,
    internal val resourceKey: String? = null,
) {
    internal var attempts = 0
    internal var requested = false
    internal var completed = false
    internal var failedAt = Long.MIN_VALUE
    internal fun request(): ArtworkResult = owner.request(this)
}

internal data class ArtworkResult(val complete: Boolean, val image: EncodedArtwork?)

/** 一个运行任务、一个最新待处理任务；切歌和总时限取消旧任务并异步关闭资源。 */
internal class ArtworkRepository(
    private val readTimeoutMs: Long = 10_000,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "media-artwork-encode").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val cleanup = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "media-artwork-cleanup").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private class Job(val control: ArtworkReadControl) {
        var future: Future<*>? = null
        var timeout: ScheduledFuture<*>? = null
    }
    private val jobs = mutableMapOf<ArtworkSource, Job>()
    private var closed = false
    private var current: ArtworkSource? = null
    private var image: EncodedArtwork? = null

    fun source(trackId: String, revision: Long, load: () -> ByteArray?): ArtworkSource =
        cancellableSource(trackId, revision) { load() }

    fun cancellableSource(trackId: String, revision: Long, resourceKey: String? = null, load: (ArtworkReadControl) -> ByteArray?): ArtworkSource = synchronized(lock) {
        current?.takeIf { it.trackId == trackId && it.revision == revision && it.resourceKey == resourceKey }?.let { return it }
        if (current?.trackId != trackId || current?.resourceKey != resourceKey) cancelJobsLocked()
        if (current?.trackId != trackId) image = null
        ArtworkSource(trackId, revision, load, this, resourceKey).also { current = it }
    }

    private fun cancelJob(job: Job) {
        job.control.cancel()
        job.future?.cancel(true)
        job.timeout?.cancel(false)
    }

    private fun cancelJobsLocked() {
        jobs.values.forEach(::cancelJob)
        jobs.clear()
        executor.queue.clear()
    }

    fun request(source: ArtworkSource): ArtworkResult = synchronized(lock) {
        if (closed || current !== source) return ArtworkResult(true, null)
        val now = System.nanoTime() / 1_000_000
        if (!source.requested && source.attempts < 3 && (!source.completed || (image == null && now - source.failedAt >= 5000))) {
            // 丢弃排队的旧版本；已经运行的同曲读取可以完成，避免频繁元数据回调造成饥饿。
            executor.queue.poll()?.let { obsolete ->
                val entry = jobs.entries.firstOrNull { it.value.future === obsolete }
                if (entry != null) { cancelJob(entry.value); jobs.remove(entry.key) }
            }
            source.requested = true
            source.attempts++
            val queuedAt = System.nanoTime() / 1_000_000
            log("ARTWORK: load-queued revision=${source.revision} attempt=${source.attempts}")
            val control = ArtworkReadControl(readTimeoutMs, dispatchClose = { close ->
                try { cleanup.execute(close) }
                catch (_: java.util.concurrent.RejectedExecutionException) { close() }
            })
            val job = Job(control)
            jobs[source] = job
            job.future = executor.submit {
                log("ARTWORK: load-start revision=${source.revision} queueMs=${System.nanoTime() / 1_000_000 - queuedAt}")
                val result = try {
                    control.check()
                    val input = source.load(control)
                    val encoded = input?.let { control.check(); EncodedArtwork.create(it) }
                    log("ARTWORK: prepared-input revision=${source.revision} bytes=${input?.size ?: 0} accepted=${encoded != null} (JPEG/PNG, max 1MiB)")
                    encoded
                } catch (error: Exception) {
                    if (!control.cancelled && error !is InterruptedException) log("ARTWORK: 图片准备失败 - ${error.javaClass.simpleName}")
                    null
                }
                synchronized(lock) {
                    log("ARTWORK: load-result revision=${source.revision} bytes=${result?.size ?: 0} cancelled=${control.cancelled} publish=${!closed && !control.cancelled && current?.trackId == source.trackId} elapsedMs=${System.nanoTime() / 1_000_000 - queuedAt}")
                    source.requested = false
                    source.completed = true
                    source.failedAt = System.nanoTime() / 1_000_000
                    if (!closed && !control.cancelled && current?.trackId == source.trackId) image = result
                    job.timeout?.cancel(false)
                    control.finish()
                    if (jobs[source] === job) jobs.remove(source)
                }
            }
            job.timeout = cleanup.schedule({
                synchronized(lock) {
                    if (jobs[source] === job) {
                        log("ARTWORK: load-timeout revision=${source.revision} budgetMs=$readTimeoutMs")
                        cancelJob(job)
                        jobs.remove(source)
                        source.requested = false; source.completed = true
                        source.failedAt = System.nanoTime() / 1_000_000
                    }
                }
            }, readTimeoutMs, TimeUnit.MILLISECONDS)
        }
        ArtworkResult(source.completed, image)
    }

    fun clear() = synchronized(lock) {
        current = null; image = null
        cancelJobsLocked()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null; image = null
            cancelJobsLocked()
        }
        executor.shutdownNow()
        // 已排入的资源关闭动作仍要执行。
        cleanup.shutdown()
    }
}

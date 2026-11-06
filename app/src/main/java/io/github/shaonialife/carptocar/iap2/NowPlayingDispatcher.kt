package io.github.shaonialife.carptocar.iap2

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 每条蓝牙通道只有一个发送者；最多一个在途任务和一个待处理更新，执行时读取最新状态。 */
internal class NowPlayingDispatcher(
    private val alive: () -> Boolean,
    private val provider: () -> CarPlayNowPlaying?,
    private val send: (CarPlayNowPlaying) -> Boolean,
    private val retryDelaysMs: LongArray = longArrayOf(1_500, 4_000, 8_000, 15_000, 25_000),
    private val afterUpdate: () -> Long? = { null },
    private val cleanupPending: () -> Boolean = { false },
) : AutoCloseable {
    private val lock = Any()
    private val updates = NowPlayingUpdates()
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "iap2-nowplaying").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var closed = false
    private var subscribed = false
    private var generation = 0L
    private var queued = false
    private var retryIndex = 0
    private var retry: ScheduledFuture<*>? = null
    private var artworkTick: ScheduledFuture<*>? = null

    fun subscribe() = synchronized(lock) {
        if (closed) return
        generation++
        subscribed = true
        retryIndex = 0
        updates.subscribe()
        requestLocked()
    }

    fun unsubscribe() = synchronized(lock) {
        generation++
        subscribed = false
        artworkTick?.cancel(false)
        artworkTick = null
        retry?.cancel(false)
        retry = null
        updates.unsubscribe()
        requestLocked()
    }

    fun request() = synchronized(lock) { requestLocked() }

    private fun requestLocked() {
        if (closed || (!subscribed && !cleanupPending()) || !alive()) return
        artworkTick?.cancel(false)
        artworkTick = null
        retry?.cancel(false)
        retry = null
        if (queued) return
        queued = true
        executor.execute {
            var sample = false
            val version = synchronized(lock) {
                queued = false
                if (closed || (!subscribed && !cleanupPending()) || !alive()) return@execute
                sample = subscribed
                generation
            }
            val value = if (sample) provider() else null
            if (value != null) {
                updates.push(value) { current ->
                    val valid = synchronized(lock) { !closed && subscribed && generation == version }
                    valid && alive() && send(current)
                }
            } else if (sample) {
                synchronized(lock) {
                    if (closed || !subscribed || generation != version || queued || retryIndex >= retryDelaysMs.size) return@synchronized
                    val delay = retryDelaysMs[retryIndex++]
                    retry = executor.schedule({
                        synchronized(lock) {
                            if (!closed && (subscribed || cleanupPending()) && generation == version) requestLocked()
                        }
                    }, delay, TimeUnit.MILLISECONDS)
                }
            }
            val valid = synchronized(lock) { !closed && generation == version && (subscribed || cleanupPending()) }
            val delay = if (valid) afterUpdate() else null
            if (delay != null) synchronized(lock) {
                if (!closed && (subscribed || cleanupPending()) && generation == version && !queued) {
                    artworkTick = executor.schedule({
                        synchronized(lock) {
                            if (!closed && (subscribed || cleanupPending()) && generation == version) requestLocked()
                        }
                    }, delay, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            subscribed = false
            artworkTick?.cancel(false)
            artworkTick = null
            retry?.cancel(false)
            retry = null
            updates.unsubscribe()
        }
        executor.shutdownNow()
    }
}

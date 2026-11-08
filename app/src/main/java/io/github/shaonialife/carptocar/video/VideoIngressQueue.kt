package io.github.shaonialife.carptocar.video

/** Owns frame bytes; overload discards dependent P frames until a fresh IDR. */
internal class VideoIngressQueue(
    private val consume: (Frame) -> Unit,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val maxFrames: Int = 32,
    private val maxAgeNs: Long = 250_000_000L,
) : AutoCloseable {
    data class Frame(val data: ByteArray, val idr: Boolean, val timestamp: Long?, val at: Long, val epoch: Long, val owner: Any)
    private val identity = Any()
    private val lock = Object()
    private val frames = ArrayDeque<Frame>()
    private var bytes = 0
    private var epoch = 0L
    private var waitingIdr = true
    private var closed = false
    private var dropped = 0L
    private var lastReport = 0L
    private val worker = Thread({ run() }, "video-source-send").apply { isDaemon = true; start() }

    fun offer(data: ByteArray, idr: Boolean, timestamp: Long?): Boolean = synchronized(lock) {
        if (closed) return false
        val now = clock()
        if (data.isEmpty() || data.size > maxBytes) {
            resetLocked(); dropped++; report(now); return false
        }
        if (frames.isNotEmpty() && (bytes + data.size > maxBytes || frames.size >= maxFrames || now - frames.first().at > maxAgeNs)) {
            resetLocked()
        }
        if (waitingIdr && !idr) { dropped++; report(now); return false }
        if (idr) waitingIdr = false
        frames += Frame(data.copyOf(), idr, timestamp, now, epoch, identity)
        bytes += data.size
        lock.notifyAll()
        true
    }

    fun reset() = synchronized(lock) { resetLocked() }
    private fun resetLocked() {
        dropped += frames.size
        frames.clear(); bytes = 0; waitingIdr = true; epoch++
    }

    fun isCurrent(frame: Frame): Boolean = synchronized(lock) { !closed && frame.owner === identity && frame.epoch == epoch }

    fun describe(now: Long): String = synchronized(lock) {
        "pending=${frames.size},bytes=$bytes,oldestMs=${frames.firstOrNull()?.let { (now - it.at) / 1_000_000 } ?: 0},dropped=$dropped,waitingIdr=$waitingIdr,epoch=$epoch"
    }

    private fun take(): Frame? = synchronized(lock) {
        while (true) {
            while (!closed && frames.isEmpty()) lock.wait()
            if (closed) return null
            val now = clock()
            if (now - frames.first().at > maxAgeNs) {
                resetLocked(); report(now); continue
            }
            val frame = frames.removeFirst()
            bytes -= frame.data.size
            report(now)
            return frame
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun report(now: Long) {
        if (dropped == 0L || now - lastReport < 5_000_000_000L) return
        log("VIDEO-QUEUE: dropped=$dropped pending=${frames.size} bytes=$bytes waitingIdr=$waitingIdr epoch=$epoch")
        lastReport = now
    }

    private fun run() {
        try {
            while (true) {
                val frame = take() ?: return
                if (isCurrent(frame)) consume(frame)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log("VIDEO-QUEUE: worker failed ${error.javaClass.simpleName}: ${error.message}")
        } finally { close() }
    }

    override fun close() {
        synchronized(lock) { closed = true; resetLocked(); lock.notifyAll() }
        worker.interrupt()
    }
}

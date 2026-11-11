package io.github.shaonialife.carptocar.audio

/** 有界实时输入；生产者只复制有效字节，消费端慢发送不会阻塞 CarLife socket reader。 */
internal class AudioIngressQueue(
    private val consume: (ByteArray, Int, Int, Boolean) -> Unit,
    private val log: (String) -> Unit = {},
    private val failed: (Exception) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    private val maxDurationNs: Long = 200_000_000L,
    private val maxBytes: Int = 64 * 1024,
) : AutoCloseable {
    private data class Block(val data: ByteArray, val rate: Int, val channels: Int, val at: Long, val duration: Long, val speech: Boolean)
    private data class Delivery(val block: Block, val gap: Boolean)
    private val lock = Object()
    private val queue = ArrayDeque<Block>()
    private var closed = false
    private var inFlight = false
    private var bytes = 0
    private var duration = 0L
    // Pending-only budgets (media=0, speech=1); the in-flight block retains its original exclusion.
    private val pendingBlocks = IntArray(2)
    private val pendingBytes = IntArray(2)
    private val pendingDuration = LongArray(2)
    private var gap = false
    private var dropped = 0L
    private var maxAge = 0L
    private var maxWork = 0L
    private var lastReport = clock()
    private val worker = Thread({ run() }, "audio-pcm-send").apply { isDaemon = true; start() }

    fun offer(data: ByteArray, size: Int, rate: Int, channels: Int, speech: Boolean = false): Boolean = synchronized(lock) {
        if (closed || size <= 0 || size > data.size || size > 256 * 1024 || rate <= 0 || channels !in 1..2) return false
        val span = size.toLong() * 1_000_000_000L / (rate.toLong() * channels * 2)
        val byteBudget = if (speech) 512 * 1024 else maxBytes
        val timeBudget = if (speech) 10_000_000_000L else maxDurationNs
        val kind = if (speech) 1 else 0
        // 单次合法大包不等于排队积压。完整接纳一块；只在确有旧待处理块时丢弃旧块。
        while (pendingBlocks[kind] > 0 &&
            (pendingBytes[kind] + size > byteBudget || pendingDuration[kind] + span > timeBudget)) {
            drop(queue.indexOfFirst { it.speech == speech })
        }
        if (queue.size >= 128) {
            val obsolete = queue.indexOfFirst { !it.speech }
            if (obsolete >= 0) drop(obsolete)
            else if (speech) drop(0) else return false
        }
        queue += Block(data.copyOf(size), rate, channels, clock(), span, speech)
        bytes += size; duration += span
        pendingBlocks[kind]++; pendingBytes[kind] += size; pendingDuration[kind] += span
        lock.notifyAll()
        true
    }

    private fun drop(index: Int) {
        remove(index)
        dropped++; gap = true
    }
    /** Called only under lock, for both delivery and eviction. */
    private fun remove(index: Int): Block {
        val old = queue.removeAt(index)
        bytes -= old.data.size; duration -= old.duration
        val kind = if (old.speech) 1 else 0
        pendingBlocks[kind]--; pendingBytes[kind] -= old.data.size; pendingDuration[kind] -= old.duration
        return old
    }
    private fun take(): Delivery? {
        synchronized(lock) {
            while (true) {
                while (!closed && queue.isEmpty()) lock.wait()
                if (closed) return null
                val age = clock() - queue.first().at
                if (age > if (queue.first().speech) 10_000_000_000L else maxDurationNs) { drop(0); continue }
                val block = remove(0)
                maxAge = maxOf(maxAge, age)
                inFlight = true
                return Delivery(block, gap).also { gap = false }
            }
        }
    }

    private fun run() {
        try {
            while (true) {
                val delivery = take() ?: return
                val started = clock()
                try {
                    val b = delivery.block
                    consume(b.data, b.rate, b.channels, delivery.gap)
                } finally {
                    val now = clock()
                    val report = synchronized(lock) {
                        inFlight = false
                        maxWork = maxOf(maxWork, now - started)
                        if (now - lastReport >= 30_000_000_000L ||
                            (now - lastReport >= 5_000_000_000L && (dropped > 0 || maxWork >= 50_000_000L))) {
                            lastReport = now
                            "AUDIO-DIAG: ingress queuedBytes=$bytes queuedMs=${duration / 1_000_000} droppedBlocks=$dropped queueAgeMaxMs=${maxAge / 1_000_000} workerMaxUs=${maxWork / 1000}".also {
                                dropped = 0; maxAge = 0; maxWork = 0
                            }
                        } else null
                    }
                    report?.let(log)
                }
            }
        } catch (_: InterruptedException) {
            // 会话退役，不把下一轮输入接到旧发送器。
        } catch (error: Exception) {
            failed(error)
        } finally { close() }
    }

    fun pending(): Boolean = synchronized(lock) { queue.isNotEmpty() || inFlight }

    override fun close() {
        synchronized(lock) {
            closed = true; queue.clear(); bytes = 0; duration = 0
            pendingBlocks.fill(0); pendingBytes.fill(0); pendingDuration.fill(0)
            lock.notifyAll()
        }
        worker.interrupt()
    }
}

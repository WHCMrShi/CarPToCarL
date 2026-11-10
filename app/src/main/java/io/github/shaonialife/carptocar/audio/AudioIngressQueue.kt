package io.github.shaonialife.carptocar.audio

/** 有界实时输入；生产者只复制有效字节，消费端慢发送不会阻塞 CarLife socket reader。 */
internal class AudioIngressQueue(
    private val consume: (ByteArray, Int, Int, Boolean) -> Unit,
    private val log: (String) -> Unit = {},
    private val failed: (Exception) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    private val maxDurationNs: Long = 200_000_000L,
    private val maxBytes: Int = 64 * 1024,
    private val speechDurationNs: Long = 10_000_000_000L,
    private val speechMaxBytes: Int = 512 * 1024,
    workerName: String = "audio-pcm-send",
) : AutoCloseable {
    private data class Block(val data: ByteArray, val rate: Int, val channels: Int, val at: Long, val duration: Long, val speech: Boolean)
    private data class Delivery(val block: Block, val gap: Boolean)
    private val lock = Object()
    private val queue = ArrayDeque<Block>()
    private var closed = false
    private var inFlight = false
    private var bytes = 0
    private var duration = 0L
    private var gap = false
    private var dropped = 0L
    private var maxAge = 0L
    private var maxWork = 0L
    private var lastReport = clock()
    private val worker = Thread({ run() }, workerName).apply { isDaemon = true; start() }

    fun offer(data: ByteArray, size: Int, rate: Int, channels: Int, speech: Boolean = false): Boolean = synchronized(lock) {
        if (closed || size <= 0 || size > data.size || size > 256 * 1024 || rate <= 0 || channels !in 1..2) return false
        val span = size.toLong() * 1_000_000_000L / (rate.toLong() * channels * 2)
        val byteBudget = if (speech) speechMaxBytes else maxBytes
        val timeBudget = if (speech) speechDurationNs else maxDurationNs
        // 单次合法大包不等于排队积压。完整接纳一块；只在确有旧待处理块时丢弃旧块。
        while (true) {
            val matching = queue.filter { it.speech == speech }
            if (matching.isEmpty() ||
                (matching.sumOf { it.data.size } + size <= byteBudget && matching.sumOf { it.duration } + span <= timeBudget)) break
            drop(queue.indexOfFirst { it.speech == speech })
        }
        if (queue.size >= 128) {
            val obsolete = queue.indexOfFirst { !it.speech }
            if (obsolete >= 0) drop(obsolete)
            else if (speech) drop(0) else return false
        }
        queue += Block(data.copyOf(size), rate, channels, clock(), span, speech)
        bytes += size; duration += span
        lock.notifyAll()
        true
    }

    private fun drop(index: Int) {
        val old = queue.removeAt(index)
        bytes -= old.data.size; duration -= old.duration
        dropped++; gap = true
    }
    private fun take(): Delivery? {
        synchronized(lock) {
            while (true) {
                while (!closed && queue.isEmpty()) lock.wait()
                if (closed) return null
                val age = clock() - queue.first().at
                if (age > if (queue.first().speech) speechDurationNs else maxDurationNs) { drop(0); continue }
                val block = queue.removeFirst()
                bytes -= block.data.size; duration -= block.duration
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
            lock.notifyAll()
        }
        worker.interrupt()
    }
}

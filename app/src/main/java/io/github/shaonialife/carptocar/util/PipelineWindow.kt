package io.github.shaonialife.carptocar.util

/** 单线程窗口计数器：只在窗口到期时格式化日志；不采样 payload，不新增线程。 */
internal class PipelineWindow(
    private val name: String,
    private val intervalNs: Long = 30_000_000_000L,
    private val clock: () -> Long = System::nanoTime,
    private val runtime: () -> String = {
        val vm = Runtime.getRuntime()
        val gc = runCatching { android.os.Debug.getRuntimeStat("art.gc.gc-count") }.getOrNull() ?: "?"
        "heapUsed=${vm.totalMemory() - vm.freeMemory()} gcCount=$gc"
    },
) {
    private var started = clock()
    private var packets = 0L
    private var bytes = 0L
    private var allocated = 0L
    private var workTotal = 0L
    private var workMax = 0L
    private var queueMax = 0L
    private val workBuckets = LongArray(LIMITS.size)
    private val queueBuckets = LongArray(LIMITS.size)

    fun record(
        bytes: Int,
        allocatedBytes: Int,
        workNs: Long,
        queueNs: Long = 0L,
        now: Long = clock(),
    ): String? {
        packets++
        this.bytes += bytes.coerceAtLeast(0)
        allocated += allocatedBytes.coerceAtLeast(0)
        val work = workNs.coerceAtLeast(0)
        val queue = queueNs.coerceAtLeast(0)
        workTotal += work
        workMax = maxOf(workMax, work)
        queueMax = maxOf(queueMax, queue)
        workBuckets[bucket(work)]++
        queueBuckets[bucket(queue)]++
        if (now - started < intervalNs) return null
        val line =
            "PERF: $name n=$packets bytes=${this.bytes} ingressArrayBytes=$allocated " +
                "workUs(avg/p95<=/max)=${workTotal / packets / 1000}/${percentile(workBuckets, workMax) / 1000}/${workMax / 1000} " +
                "queueUs(p95<=/max)=${percentile(queueBuckets, queueMax) / 1000}/${queueMax / 1000} ${runtime()}"
        started = now
        packets = 0
        this.bytes = 0
        allocated = 0
        workTotal = 0
        workMax = 0
        queueMax = 0
        workBuckets.fill(0)
        queueBuckets.fill(0)
        return line
    }

    private fun bucket(value: Long): Int {
        var index = 0
        while (value > LIMITS[index]) index++
        return index
    }

    private fun percentile(
        buckets: LongArray,
        max: Long,
    ): Long {
        val target = (packets * 95 + 99) / 100
        var count = 0L
        for (i in buckets.indices) {
            count += buckets[i]
            if (count >= target) return minOf(LIMITS[i], max)
        }
        return max
    }

    companion object {
        private val LIMITS = longArrayOf(100_000, 1_000_000, 5_000_000, 20_000_000, 100_000_000, 500_000_000, Long.MAX_VALUE)
    }
}

package io.github.shaonialife.carptocar.iap2

import java.util.concurrent.atomic.AtomicLong

/** Bounded metadata only. Submission, link ACK and protocol response are different evidence. */
internal class Iap2ConnectionEvidence(
    private val scope: String,
    private val log: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    companion object {
        private val sequence = AtomicLong()
        private val observed = setOf(0x1d00, 0x1d01, 0x1d02, 0xaa00, 0xaa01, 0xaa02,
            0xaa03, 0xaa04, 0xaa05, 0x4e0d, 0x4e0e, 0x5702, 0x5703, 0x4300, 0x4301, 0x6800)
    }
    private val session = sequence.incrementAndGet()
    private val started = clock()
    private val counts = linkedMapOf<String, Int>()
    private var phase = "created"
    private var phaseLogs = 0
    private var decisions = 0

    @Synchronized fun phase(next: String) {
        if (phase == next) return
        phase = next
        if (phaseLogs++ < 10) emit("event=PHASE")
    }

    fun observes(id: Int): Boolean = id in observed

    @Synchronized fun message(direction: String, id: Int, link: String = "") {
        if (id !in observed) return
        val key = "$direction:0x${id.toString(16).padStart(4, '0')}"
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        if (count <= 3) emit("event=$direction id=0x${id.toString(16).padStart(4, '0')} count=$count $link")
    }

    @Synchronized fun decision(detail: String) {
        if (decisions++ < 12) emit("event=DECISION $detail")
    }

    @Synchronized fun summary(outcome: String, link: String) {
        emit("event=SUMMARY outcome=$outcome counts=${counts.entries.joinToString(",") { "${it.key}=${it.value}" }} $link")
    }

    private fun emit(detail: String) {
        runCatching { log("IAP2-FLOW: session=$session $scope elapsedMs=${(clock() - started).coerceAtLeast(0)} phase=$phase $detail") }
    }
}

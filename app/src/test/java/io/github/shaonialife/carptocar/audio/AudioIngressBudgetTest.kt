package io.github.shaonialife.carptocar.audio

import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AudioIngressBudgetTest {
    private data class Input(val id: Int, val size: Int, val rate: Int, val channels: Int, val speech: Boolean, val at: Long) {
        val span get() = size.toLong() * 1_000_000_000L / (rate.toLong() * channels * 2)
        fun bytes() = ByteArray(size).also { ByteBuffer.wrap(it).putInt(id) }
    }

    /** The original scan-based admission rules, independent of production incremental counters. */
    private class Reference(private val maxBytes: Int, private val maxDuration: Long) {
        private val queue = mutableListOf<Input>()
        private var gap = false
        private fun drop(index: Int) { queue.removeAt(index); gap = true }
        fun offer(b: Input): Boolean {
            val byteBudget = if (b.speech) 512 * 1024 else maxBytes
            val timeBudget = if (b.speech) 10_000_000_000L else maxDuration
            while (true) {
                val matching = queue.filter { it.speech == b.speech }
                if (matching.isEmpty() || (matching.sumOf { it.size } + b.size <= byteBudget &&
                    matching.sumOf { it.span } + b.span <= timeBudget)) break
                drop(queue.indexOfFirst { it.speech == b.speech })
            }
            if (queue.size >= 128) {
                val obsolete = queue.indexOfFirst { !it.speech }
                if (obsolete >= 0) drop(obsolete) else if (b.speech) drop(0) else return false
            }
            queue += b
            return true
        }
        fun drain(now: Long): List<Pair<Int, Boolean>> {
            val result = mutableListOf<Pair<Int, Boolean>>()
            while (queue.isNotEmpty()) {
                val b = queue.first()
                if (now - b.at > if (b.speech) 10_000_000_000L else maxDuration) { drop(0); continue }
                queue.removeAt(0)
                result += b.id to gap
                gap = false
            }
            return result
        }
    }

    private fun checkAgainstReference(inputs: List<Input>, now: Long, maxBytes: Int = 64 * 1024, maxDuration: Long = 200_000_000L) {
        val clock = AtomicLong()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        val delivered = mutableListOf<Pair<Int, Boolean>>()
        val reference = Reference(maxBytes, maxDuration)
        val marker = Input(Int.MAX_VALUE, 4, 48000, 2, true, now)
        val queue = AudioIngressQueue(
            consume = { data, _, _, gap ->
                val id = ByteBuffer.wrap(data).int
                if (id == 0) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                else { delivered += id to gap; if (id == marker.id) done.countDown() }
            },
            failed = { failure.set(it); done.countDown() }, clock = clock::get,
            maxBytes = maxBytes, maxDurationNs = maxDuration,
        )
        try {
            assertTrue(queue.offer(ByteArray(4), 4, 48000, 2))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            for (b in inputs + marker) {
                clock.set(b.at)
                assertEquals("admission id=${b.id}", reference.offer(b), queue.offer(b.bytes(), b.size, b.rate, b.channels, b.speech))
            }
            val expected = reference.drain(now)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertEquals(expected, delivered.toList())
        } finally { release.countDown(); queue.close() }
    }

    @Test fun mixedRatesAndSpeechMatchOriginalRulesAcrossRandomEvictionsAndExpiry() {
        val random = Random(171732)
        repeat(40) {
            var at = 0L
            val inputs = (1..100).map { id ->
                at += random.nextInt(30) * 1_000_000L
                Input(id, intArrayOf(4, 320, 4096, 8192, 65536)[random.nextInt(5)],
                    intArrayOf(8000, 16000, 48000)[random.nextInt(3)], 1 + random.nextInt(2), random.nextBoolean(), at)
            }
            checkAgainstReference(inputs, at + random.nextInt(250) * 1_000_000L)
        }
    }

    @Test fun fullSpeechQueueRejectsMediaAndEvictsOldestSpeechWithoutCounterDrift() {
        val inputs = (1..128).map { Input(it, 4, 48000, 2, true, 0) } + listOf(
            Input(129, 4, 48000, 2, false, 0),
            Input(130, 4, 48000, 2, true, 0),
            Input(131, 4, 48000, 2, false, 0),
        )
        checkAgainstReference(inputs, 0)
    }

    @Test fun queueLimitEvictsMediaBeforeSpeechAndUpdatesBothBudgets() {
        val inputs = listOf(Input(1, 4, 48000, 2, false, 0)) +
            (2..128).map { Input(it, 4, 48000, 2, true, 0) } +
            listOf(Input(129, 4, 48000, 2, true, 0), Input(130, 4096, 48000, 2, false, 0))
        checkAgainstReference(inputs, 0)
    }

    @Test fun exactTimeLimitAndSingleOversizeBlockKeepOriginalSemantics() {
        checkAgainstReference(listOf(
            Input(1, 4096, 48000, 2, false, 0), Input(2, 4, 16000, 1, true, 0),
            Input(3, 8192, 48000, 2, false, 0),
        ), 200_000_000L, maxBytes = 4)
        checkAgainstReference(listOf(Input(1, 4, 48000, 2, false, 0)), 200_000_001L)
    }

    @Test fun takingBlocksRestoresBudgetForNextBatchAndCloseRejectsOffers() {
        val queueDone = AtomicReference(CountDownLatch(1))
        val failure = AtomicReference<Exception>()
        val gaps = mutableListOf<Boolean>()
        val queue = AudioIngressQueue(
            consume = { _, _, _, gap -> gaps += gap; queueDone.get().countDown() },
            failed = { failure.set(it); queueDone.get().countDown() }, clock = { 0L }, maxBytes = 4,
        )
        try {
            repeat(20) { index ->
                val done = CountDownLatch(1)
                queueDone.set(done)
                assertTrue(queue.offer(ByteArray(8192), 8192, 48000, 2, speech = index % 2 == 0))
                assertTrue(done.await(3, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
            }
            assertEquals(List(20) { false }, gaps.toList())
        } finally { queue.close() }
        assertFalse(queue.offer(ByteArray(4), 4, 48000, 2))
    }
}

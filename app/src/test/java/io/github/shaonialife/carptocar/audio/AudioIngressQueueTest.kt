package io.github.shaonialife.carptocar.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class AudioIngressQueueTest {
    @Test fun healthyLargeTtsBlocksPreserveEverySampleWithoutDiscontinuity() {
        for (size in listOf(8192, 65536)) {
        val done = CountDownLatch(1)
        val input = ByteArray(size) { (it / 2).toByte() }
        var received: ByteArray? = null
        var discontinuity = false
        AudioIngressQueue(consume = { bytes, rate, channels, gap ->
            assertEquals(16000, rate)
            assertEquals(1, channels)
            received = bytes; discontinuity = gap; done.countDown()
        }, clock = { 0L }).use { queue ->
            assertTrue(queue.offer(input, input.size, 16000, 1, speech = true))
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertFalse(discontinuity)
            assertArrayEquals(input, received)
        }
        }
    }

    @Test fun speechSurvivesMediaAgeLimitWhileConsumerIsBusy() {
        val clock = AtomicLong()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        AudioIngressQueue(consume = { bytes, _, _, gap ->
            if (bytes[0].toInt() == 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            else { assertFalse(gap); done.countDown() }
        }, clock = clock::get).use { queue ->
            try {
                queue.offer(ByteArray(4) { 1 }, 4, 48000, 2)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                queue.offer(ByteArray(8192) { 2 }, 8192, 16000, 1, speech = true)
                clock.set(500_000_000)
                release.countDown()
                assertTrue(done.await(3, TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }

    @Test fun blockedNetworkDoesNotBlockProducerAndBorrowedArraysAreCopied() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val values = mutableListOf<Int>()
        AudioIngressQueue(consume = { bytes, _, _, _ ->
            if (bytes[0].toInt() == 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS); firstFinished.countDown() }
            synchronized(values) { values += bytes[0].toInt() }
            if (bytes[0].toInt() == 2) done.countDown()
        }, clock = { 0L }).use { queue ->
            try {
                queue.offer(byteArrayOf(1, 0, 0, 0), 4, 48000, 2)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val borrowed = byteArrayOf(2, 0, 0, 0)
                assertTrue(queue.offer(borrowed, 4, 48000, 2))
                assertEquals("offer must finish while the network consumer is still blocked", 1L, firstFinished.count)
                borrowed[0] = 99
                release.countDown()
                assertTrue(done.await(3, TimeUnit.SECONDS))
                assertEquals(listOf(1, 2), synchronized(values) { values.toList() })
            } finally { release.countDown() }
        }
    }

    @Test fun overflowKeepsLatestCompleteBlockAndMarksDiscontinuity() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val values = mutableListOf<Pair<Int, Boolean>>()
        AudioIngressQueue(consume = { bytes, _, _, gap ->
            if (bytes[0].toInt() == 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            synchronized(values) { values += bytes[0].toInt() to gap }
            if (bytes[0].toInt() == 3) done.countDown()
        }, clock = { 0L }, maxBytes = 4).use { queue ->
            try {
                queue.offer(byteArrayOf(1, 0, 0, 0), 4, 48000, 2)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                queue.offer(byteArrayOf(2, 0, 0, 0), 4, 48000, 2)
                queue.offer(byteArrayOf(3, 0, 0, 0), 4, 48000, 2)
                release.countDown()
                assertTrue(done.await(3, TimeUnit.SECONDS))
                assertEquals(listOf(1 to false, 3 to true), synchronized(values) { values.toList() })
            } finally { release.countDown() }
        }
    }

    @Test fun shutdownRejectsFurtherInputAndStaleBuffersAreDiscarded() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val clock = AtomicLong()
        val values = mutableListOf<Int>()
        val queue = AudioIngressQueue(consume = { bytes, _, _, _ ->
            if (bytes[0].toInt() == 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            synchronized(values) { values += bytes[0].toInt() }
            if (bytes[0].toInt() == 3) done.countDown()
        }, clock = clock::get)
        try {
            queue.offer(byteArrayOf(1, 0, 0, 0), 4, 48000, 2)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            queue.offer(byteArrayOf(2, 0, 0, 0), 4, 48000, 2)
            clock.set(300_000_000)
            queue.offer(byteArrayOf(3, 0, 0, 0), 4, 48000, 2)
            release.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), synchronized(values) { values.toList() })
        } finally { release.countDown(); queue.close() }
        assertFalse(queue.offer(byteArrayOf(4), 1, 48000, 2))
    }
}

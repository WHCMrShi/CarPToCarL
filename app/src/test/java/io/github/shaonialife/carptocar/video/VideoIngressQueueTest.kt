package io.github.shaonialife.carptocar.video

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class VideoIngressQueueTest {
    @Test fun readerCanContinueWhileConsumerIsBlockedAndOwnsBytes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val seen = CopyOnWriteArrayList<Int>()
        val queue = VideoIngressQueue(consume = {
            if (it.idr) { entered.countDown(); release.await() }
            seen += it.data[0].toInt(); delivered.countDown()
        })
        try {
            assertTrue(queue.offer(byteArrayOf(1), true, 1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val borrowed = byteArrayOf(2)
            assertTrue(queue.offer(borrowed, false, 2))
            borrowed[0] = 99
            release.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2), seen.toList())
        } finally { release.countDown(); queue.close() }
    }

    @Test fun overloadDropsDependentFramesUntilFreshIdr() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val seen = CopyOnWriteArrayList<Int>()
        val queue = VideoIngressQueue(maxFrames = 1, consume = {
            if (it.data[0] == 1.toByte()) { entered.countDown(); release.await() }
            seen += it.data[0].toInt(); delivered.countDown()
        })
        try {
            assertFalse(queue.offer(byteArrayOf(0), false, null))
            assertTrue(queue.offer(byteArrayOf(1), true, null))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.offer(byteArrayOf(2), false, null))
            assertFalse(queue.offer(byteArrayOf(3), false, null))
            assertFalse(queue.offer(byteArrayOf(4), false, null))
            assertTrue(queue.offer(byteArrayOf(5), true, null))
            release.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 5), seen.toList())
        } finally { release.countDown(); queue.close() }
    }

    @Test fun agedBacklogRequiresNewIdrAndCloseRejectsInput() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = AtomicLong(0)
        val queue = VideoIngressQueue(clock = clock::get, maxAgeNs = 100, consume = {
            entered.countDown(); release.await()
        })
        try {
            queue.offer(byteArrayOf(1), true, null)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            queue.offer(byteArrayOf(2), false, null)
            clock.set(101)
            assertFalse(queue.offer(byteArrayOf(3), false, null))
            assertTrue(queue.offer(byteArrayOf(4), true, null))
            queue.close()
            assertFalse(queue.offer(byteArrayOf(5), true, null))
        } finally { release.countDown(); queue.close() }
    }

    @Test fun resettingRetiresInFlightEpochAndOversizeBreaksGop() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = CopyOnWriteArrayList<VideoIngressQueue.Frame>()
        val queue = VideoIngressQueue(maxBytes = 4, consume = {
            current += it; entered.countDown(); release.await()
        })
        try {
            queue.offer(byteArrayOf(1), true, null)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(queue.isCurrent(current.first()))
            queue.reset()
            assertFalse(queue.isCurrent(current.first()))
            assertFalse(queue.offer(ByteArray(5), true, null))
            assertFalse(queue.offer(byteArrayOf(2), false, null))
        } finally { release.countDown(); queue.close() }
    }
}

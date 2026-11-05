package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncLogWriterTest {
    @Test
    fun stalledDiskDoesNotBlockProducerAndOverflowIsBoundedAndReported() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lines = mutableListOf<String>()
        var lost = 0
        val writer =
            AsyncLogWriter("test-logger", maxChars = 20, maxLines = 3) { batch, dropped ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                lines.addAll(batch)
                lost += dropped
            }
        writer.append("first")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            // 磁盘线程被阻塞时仍可完成全部入队；队列只保留最后三条。
            repeat(100) { writer.append("$it") }
        } finally {
            release.countDown()
        }
        assertTrue(writer.flush())
        assertEquals(listOf("first", "97", "98", "99"), lines)
        assertEquals(97, lost)
    }

    @Test
    fun flushIncludesPendingRecordsInOrderAndCapsLargeMessages() {
        val lines = mutableListOf<String>()
        val writer = AsyncLogWriter("test-flush", maxChars = 10) { batch, _ -> lines.addAll(batch) }
        writer.append("abcdefghijklmnop")
        assertTrue(writer.flush())
        assertEquals(listOf("abcdefghij"), lines)
        writer.append("A")
        writer.append("B")
        assertTrue(writer.flush())
        assertEquals(listOf("abcdefghij", "A", "B"), lines)
    }
    @Test
    fun sinkFailureMakesFlushReportIncompleteEvenAfterRecovery() {
        var fail = true
        val writer = AsyncLogWriter("test-failure") { _, _ ->
            if (fail) throw java.io.IOException("disk full")
        }
        writer.append("lost")
        assertTrue(!writer.flush())
        fail = false
        writer.append("recovered")
        assertTrue(!writer.flush()) // 已丢失的批次不能因后续成功而宣称完整。
    }

    @Test
    fun flushTimeoutAndInterruptionAreReported() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = AsyncLogWriter("test-timeout") { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        writer.append("pending")
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            assertTrue(!writer.flush(10))
            Thread.currentThread().interrupt()
            assertTrue(!writer.flush())
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.countDown()
        }
        assertTrue(writer.flush())
    }
}

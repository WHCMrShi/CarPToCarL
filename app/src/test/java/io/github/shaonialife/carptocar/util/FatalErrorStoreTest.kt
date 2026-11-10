package io.github.shaonialife.carptocar.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FatalErrorStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun restartKeepsCauseAndRevisionAndBoundedUnicodeEvidence() {
        FatalErrorStore(directory.root).save(Thread("bt-worker"), IllegalStateException("outer", IllegalArgumentException("inner")), "revision-1")
        val saved = FatalErrorStore(directory.root).read()!!
        assertTrue(saved.contains("revision-1"))
        assertTrue(saved.contains("bt-worker"))
        assertTrue(saved.contains("Caused by: java.lang.IllegalArgumentException: inner"))
        FatalErrorStore(directory.root).save(Thread.currentThread(), RuntimeException("错".repeat(100_000)), "revision-2")
        val latest = FatalErrorStore(directory.root).read()!!
        assertTrue(latest.startsWith("FATAL:"))
        assertTrue(latest.endsWith("[TRUNCATED]\n"))
        assertTrue(latest.length < 65_600)
    }

    @Test fun diskFailureStillDelegatesSameThrowable() {
        val error = RuntimeException("fatal")
        var delegated = false
        FatalErrorHandler({ _, _ -> throw java.io.IOException("full") }, { thread, value ->
            assertSame(Thread.currentThread(), thread); assertSame(error, value); delegated = true
        }).uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
    }

    @Test fun concurrentFatalCannotTerminateBeforeFirstEvidenceIsPersisted() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val writes = AtomicInteger()
        val delegates = AtomicInteger()
        val handler = FatalErrorHandler({ _, _ ->
            writes.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }, { _, _ -> delegates.incrementAndGet() })
        val first = Thread { handler.uncaughtException(Thread.currentThread(), RuntimeException()) }
        val second = Thread { secondStarted.countDown(); handler.uncaughtException(Thread.currentThread(), RuntimeException()) }
        try {
            first.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            second.start(); assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (second.state != Thread.State.BLOCKED && second.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, second.state)
            assertEquals(0, delegates.get())
        } finally {
            release.countDown(); first.join(2_000); second.join(2_000)
        }
        assertFalse(first.isAlive); assertFalse(second.isAlive)
        assertEquals(1, writes.get()); assertEquals(2, delegates.get())
    }
}

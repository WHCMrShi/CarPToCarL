package io.github.shaonialife.carptocar.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A queued dialog and its late callbacks share a single, revocable decision. */
internal class GuardedDecision<T>(private val valid: () -> Boolean) {
    private val latch = CountDownLatch(1)
    private var resolved = false
    private var value: T? = null

    val pending: Boolean get() = synchronized(this) { !resolved && valid() }

    @Synchronized fun complete(choice: T?): Boolean {
        if (resolved) return false
        val accepted = valid()
        value = choice.takeIf { accepted }
        resolved = true
        latch.countDown()
        return accepted
    }

    fun await(timeoutMs: Long): T? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (pending) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            if (latch.await(minOf(remaining, 200_000_000), TimeUnit.NANOSECONDS)) break
        }
        synchronized(this) {
            if (!resolved) { resolved = true; latch.countDown() }
            return value.takeIf { valid() }
        }
    }
}

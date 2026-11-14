package io.github.shaonialife.carptocar.carlife

import java.util.concurrent.atomic.AtomicLong

/** Evidence belongs to one HU source, including frames received before that source is adopted. */
internal class CarLifeSourceEvidence(
    private val clock: () -> Long,
    private val freshnessMs: Long = 10_000,
) {
    private val lastFrame = AtomicLong(NO_FRAME)

    fun frame(bytes: Int) {
        if (bytes <= 0) return
        val now = clock()
        while (true) {
            val observed = lastFrame.get()
            if (observed == RETIRED) return
            if (lastFrame.compareAndSet(observed, now)) return
        }
    }

    fun retire() { lastFrame.set(RETIRED) }

    private fun fresh(): Boolean {
        val at = lastFrame.get()
        return at >= 0 && clock() - at in 0 until freshnessMs
    }

    /** The lease rechecks liveness/ownership and freshness each time; it never caches a true result. */
    fun lease(stillCurrent: () -> Boolean): (() -> Boolean)? {
        val valid = { fresh() && stillCurrent() && fresh() }
        return valid.takeIf { it() }
    }

    private companion object {
        const val NO_FRAME = -1L
        const val RETIRED = -2L
    }
}

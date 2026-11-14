package io.github.shaonialife.carptocar.carlife

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeSourceEvidenceTest {
    @Test
    fun `CarPlay first waits for a nonempty live CarLife frame`() {
        val source = CarLifeSourceEvidence({ 0L })
        assertNull(source.lease { true })
        source.frame(0)
        assertNull(source.lease { true })
        source.frame(128)
        assertNotNull(source.lease { true })
    }

    @Test
    fun `CarLife first can qualify after source adoption and projection attachment`() {
        var attached = false
        val source = CarLifeSourceEvidence({ 100L })
        source.frame(128)
        assertNull(source.lease { attached })
        attached = true
        assertTrue(source.lease { attached }!!.invoke())
    }

    @Test
    fun `same source lease follows fresh frames instead of freezing initial timestamp`() {
        var now = 100L
        val source = CarLifeSourceEvidence({ now })
        source.frame(128)
        val lease = source.lease { true }!!
        now = 10_099
        assertTrue(lease())
        now = 10_100
        assertFalse(lease())
        source.frame(128)
        assertTrue(lease())
        now = 10_099 // A backwards clock is not fresh evidence.
        assertFalse(lease())
    }

    @Test
    fun `late old source frames cannot qualify replacement source`() {
        var now = 100L
        val old = CarLifeSourceEvidence({ now })
        var current = old
        old.frame(128)
        val lease = old.lease { current === old }!!
        val replacement = CarLifeSourceEvidence({ now })
        current = replacement
        now = 200
        old.frame(128)
        assertFalse(lease())
        assertNull(replacement.lease { current === replacement })
        replacement.frame(128)
        assertNotNull(replacement.lease { current === replacement })
    }

    @Test
    fun `queued lease rechecks live HU and current projection before handoff`() {
        val source = CarLifeSourceEvidence({ 100L })
        val projection = ProjectionOwner<Any>()
        val owner = Any()
        var huRunning = true
        projection.bind(owner)
        source.frame(128)
        val lease = source.lease { huRunning && projection.isCurrent(owner) }!!
        huRunning = false
        assertFalse(lease())
        huRunning = true
        projection.bind(Any())
        assertFalse(lease())
        projection.clear()
        assertFalse(lease())
    }

    @Test
    fun `retirement during frame publication cannot revive a stopped source`() {
        val clockEntered = CountDownLatch(1)
        val releaseClock = CountDownLatch(1)
        val source = CarLifeSourceEvidence({
            clockEntered.countDown()
            check(releaseClock.await(2, TimeUnit.SECONDS))
            100L
        })
        val frameThread = Thread { source.frame(128) }
        frameThread.start()
        try {
            assertTrue(clockEntered.await(2, TimeUnit.SECONDS))
            source.retire()
        } finally {
            releaseClock.countDown()
            frameThread.join(2_000)
        }
        assertFalse(frameThread.isAlive)
        assertNull(source.lease { true })
        source.frame(128)
        assertNull(source.lease { true })
    }

    @Test
    fun `retirement while queued invalidates captured lease`() {
        val source = CarLifeSourceEvidence({ 100L })
        source.frame(128)
        val lease = source.lease { true }!!
        source.retire()
        assertFalse(lease())
        source.frame(128)
        assertFalse(lease())
    }
}

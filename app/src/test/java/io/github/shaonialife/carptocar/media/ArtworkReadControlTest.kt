package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class ArtworkReadControlTest {
    @Test fun continuousSlowProgressCannotExtendTotalBudget() {
        var now = 0L
        val closed = AtomicInteger()
        val control = ArtworkReadControl(timeoutMs = 10000, clock = { now })
        control.onCancel { closed.incrementAndGet() }
        val input = object : InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                now += 2900 // each read completes within the HTTP 3-second read timeout
                bytes[offset] = 1
                return 1
            }
        }
        try { control.read(input); fail("total deadline was not enforced") } catch (_: IOException) { }
        assertTrue(control.cancelled)
        assertEquals(11600L, now)
        assertEquals(1, closed.get())
    }

    @Test fun lateResourceRegistrationAfterCancellationStillClosesItOnce() {
        val closed = AtomicInteger()
        val control = ArtworkReadControl()
        control.cancel()
        control.onCancel { closed.incrementAndGet() }
        control.cancel()
        assertEquals(1, closed.get())
        try { control.check(); fail("cancelled work continued") } catch (_: IOException) { }
    }

    @Test fun finishDetachesResourceAndOversizeReadIsRejected() {
        val closed = AtomicInteger()
        val control = ArtworkReadControl()
        control.onCancel { closed.incrementAndGet() }
        control.finish(); control.cancel()
        assertEquals(0, closed.get())
        val large = java.io.ByteArrayInputStream(ByteArray(EncodedArtwork.MAX_BYTES + 1))
        try { ArtworkReadControl().read(large); fail("oversized input accepted") } catch (_: IOException) { }
    }
}

package io.github.shaonialife.carptocar.video

import org.junit.Assert.*
import org.junit.Test

class SourceKeyFrameRequestTest {
    @Test fun coalescesInFlightAndLimitsCompletedRequests() {
        val gate = SourceKeyFrameRequest()
        val ticket = gate.begin(1, 100)!!
        assertNull(gate.begin(1, 20_000))
        assertTrue(gate.forwarded(ticket, true))
        assertNull(gate.begin(1, 6_099))
        assertNotNull(gate.begin(1, 6_100))
    }
    @Test fun sourceRestartRetiresQueuedAndCompletedWork() {
        val gate = SourceKeyFrameRequest()
        val old = gate.begin(1, 0)!!
        gate.reset()
        val next = gate.begin(2, 1)!!
        assertFalse(gate.current(old, 2))
        assertFalse(gate.forwarded(old, true))
        assertTrue(gate.current(next, 2))
        assertNull(gate.arrived(1))
        assertNull(gate.written(1))
    }
    @Test fun completionRequiresMatchingSourceAndRealArrival() {
        val gate = SourceKeyFrameRequest()
        val ticket = gate.begin(4, 0)!!
        assertNull(gate.written(4))
        assertNull(gate.arrived(3))
        assertSame(ticket, gate.arrived(4))
        assertNull(gate.arrived(4))
        assertSame(ticket, gate.written(4))
        assertFalse(gate.current(ticket, 4))
        assertNotNull(gate.begin(4, 6_000)) // even if the old command completion races behind the IDR
    }
    @Test fun failedForwardDoesNotClaimAnIdrButRetainsRateLimit() {
        val gate = SourceKeyFrameRequest()
        val ticket = gate.begin(0, 0)!!
        assertTrue(gate.forwarded(ticket, false))
        assertNull(gate.arrived(0))
        assertNull(gate.begin(0, 1))
        assertNotNull(gate.begin(0, 6_000))
    }
}

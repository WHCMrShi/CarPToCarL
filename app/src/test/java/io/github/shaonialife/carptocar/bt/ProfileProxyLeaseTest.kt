package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class ProfileProxyLeaseTest {
    @Test fun cancellationReleasesBothEarlyAndLateResults() {
        val closed = mutableListOf<String>()
        val early = ProfileProxyLease<String> { closed += it }
        early.deliver("early")
        early.retire(); early.retire()
        assertNull(early.get())
        val late = ProfileProxyLease<String> { closed += it }
        late.retire(); late.deliver("late")
        assertNull(late.get())
        assertEquals(listOf("early", "late"), closed)
    }

    @Test fun retiringOldAcquisitionDoesNotDisposeReplacement() {
        val closed = mutableListOf<String>()
        val old = ProfileProxyLease<String> { closed += it }
        val next = ProfileProxyLease<String> { closed += it }
        old.deliver("old"); next.deliver("next"); old.retire()
        assertEquals("next", next.get())
        assertEquals(listOf("old"), closed)
    }

    @Test fun interruptedWaitReturnsNoProxyAndKeepsInterruptFlag() {
        try {
            assertFalse(awaitProfileReady { throw InterruptedException() })
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(awaitProfileReady { fail("retired worker must not wait again"); true })
        } finally { Thread.interrupted() }
    }
}

package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleLogBufferTest {
    @Test
    fun clearDiscardsQueuedLinesAndRejectsLateOldGenerationCallbacks() {
        val pending = ConsoleLogBuffer()
        assertTrue(pending.append("queued old", 0))
        pending.clear(1)
        assertFalse(pending.append("late old", 0))
        assertEquals("", pending.drain())
        assertTrue(pending.append("new", 1))
        assertEquals("new", pending.drain())
        assertEquals("", pending.drain())
    }
}

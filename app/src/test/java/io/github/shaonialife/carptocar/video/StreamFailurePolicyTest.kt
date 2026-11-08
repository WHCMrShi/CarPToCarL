package io.github.shaonialife.carptocar.video

import org.junit.Assert.*
import org.junit.Test

class StreamFailurePolicyTest {
    @Test fun transientFailureDoesNotEndHuAndExhaustedRecoveryDoes() {
        val ended = mutableListOf<String>()
        var recovered = true
        val policy = StreamFailurePolicy({ recovered }, ended::add)
        assertTrue(policy.failed("write"))
        assertTrue(ended.isEmpty())
        recovered = false
        assertFalse(policy.failed("retry-exhausted"))
        assertEquals(listOf("retry-exhausted"), ended)
    }
}

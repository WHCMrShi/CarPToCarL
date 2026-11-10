package io.github.shaonialife.carptocar.audio

import org.junit.Assert.*
import org.junit.Test

class SilenceFillPolicyTest {
    @Test fun stopUsesNextTickButRealInputOrStartRestoresNormalThreshold() {
        val policy = SilenceFillPolicy()
        assertEquals(300L, policy.thresholdMs())
        repeat(2) { policy.engineStopped() }
        assertEquals(50L, policy.thresholdMs())
        policy.resumed()
        assertEquals(300L, policy.thresholdMs())
        policy.engineStopped(); policy.resumed(); policy.resumed()
        assertEquals(300L, policy.thresholdMs())
        assertEquals(300L, SilenceFillPolicy().thresholdMs())
    }
}

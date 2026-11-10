package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class VoiceModuleObservationTest {
    @Test fun partialDuplicateAndAmbiguousReportsDoNotInventTransitions() {
        val observer = VoiceModuleObservation()
        assertNotNull(observer.update(listOf(4 to 0)))
        assertNull(observer.update(listOf(2 to 1)))
        assertNull(observer.update(listOf(4 to 0)))
        assertNull(observer.update(listOf(4 to 1, 4 to 2)))
        assertTrue(observer.update(listOf(4 to 2))!!.contains("previous=0 current=2"))
        assertNotNull(VoiceModuleObservation().update(listOf(4 to 2)))
    }
    @Test fun transitionsAreBounded() {
        val observer = VoiceModuleObservation()
        assertEquals(32, (0..99).count { observer.update(listOf(4 to it % 2)) != null })
    }
}

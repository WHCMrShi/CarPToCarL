package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticModuleControlTest {
    @Test fun idleNavigationAndEdogAloneSendNoAutomaticControl() {
        assertTrue(CarLifeProtocol.automaticModuleControlIds(listOf(2 to 0, 8 to 0)).isEmpty())
        assertTrue(CarLifeProtocol.automaticModuleControlIds(listOf(2 to 1, 8 to 1)).isEmpty())
    }

    @Test fun idleNavigationDoesNotTriggerControlOfRunningMedia() {
        assertTrue(CarLifeProtocol.automaticModuleControlIds(listOf(2 to 0, 3 to 1, 8 to 0)).isEmpty())
    }

    @Test fun mediaControlExcludesBothIdleAndActiveNavigation() {
        for (state in listOf(0, 1)) {
            val ids = CarLifeProtocol.automaticModuleControlIds(listOf(2 to state, 3 to 0, 8 to state, 6 to 1))
            assertEquals(listOf(3, 6), ids)
            val wire = CarLifeProtocol.moduleControl(ids)
            assertEquals(listOf(3 to 1, 6 to 1), CarLifeProtocol.parseModuleStatus(wire))
        }
    }

    @Test fun nonNavigationModulesKeepExistingBatchBehavior() {
        assertEquals(listOf(1, 3, 6), CarLifeProtocol.automaticModuleControlIds(listOf(1 to 1, 3 to 0, 6 to 1)))
        assertTrue(CarLifeProtocol.automaticModuleControlIds(listOf(1 to 1, 3 to 1)).isEmpty())
        assertTrue(CarLifeProtocol.automaticModuleControlIds(emptyList()).isEmpty())
    }

    @Test fun explicitModuleControlStillSupportsNavigation() {
        assertEquals(listOf(2 to 1, 8 to 1), CarLifeProtocol.parseModuleStatus(CarLifeProtocol.moduleControl(listOf(2, 8))))
    }
}

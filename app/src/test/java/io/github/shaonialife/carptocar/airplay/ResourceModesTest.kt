package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.*
import org.junit.Test

class ResourceModesTest {
    private fun change(
        m: ResourceModes, id: Int, type: Int, borrow: String? = null,
        reason: String = "test", takeConstraint: Int? = null,
    ) {
        val resource = mutableMapOf<String, Any>("resourceID" to id, "transferType" to type)
        borrow?.let { resource["borrowID"] = it }
        takeConstraint?.let { resource["takeConstraint"] = it }
        m.change(mapOf("reasonStr" to reason, "resources" to listOf(resource)))
    }
    private fun resource(m: ResourceModes, id: Long) =
        (m.snapshot().params["resources"] as List<*>).map { it as Map<*, *> }.single { it["resourceID"] == id }
    @Test fun excessBorrowersCannotAccidentallyReturnTheScreen() {
        val m = ResourceModes()
        repeat(17) { change(m, 1, 3, "borrow-$it") }
        repeat(17) { change(m, 1, 4, "borrow-$it") }
        m.requestUi()
        assertFalse(m.canRecoverScreen())
        assertEquals(2L, resource(m, 1)["entity"])
    }
    @Test fun defaultStartupKeepsControllerScreenAndAudio() {
        val m = ResourceModes()
        assertEquals(1L, resource(m, 1)["entity"])
        assertEquals(1L, resource(m, 2)["permanentEntity"])
        assertTrue(m.canRecoverScreen())
    }
    @Test fun cameraBorrowDoesNotStealAudioAndReturnsToPreviousOwner() {
        val m = ResourceModes()
        change(m, 1, 3, "backupCamera")
        assertEquals(2L, resource(m, 1)["entity"])
        assertEquals(1L, resource(m, 1)["permanentEntity"])
        assertEquals(1L, resource(m, 2)["entity"])
        assertFalse(m.prepareScreenRecovery())
        change(m, 1, 4, "backupCamera")
        assertTrue(m.canRecoverScreen())
    }
    @Test fun nestedAndDuplicateBorrowingNeedsMatchingReleases() {
        val m = ResourceModes()
        change(m, 1, 3, "phoneCall")
        val version = m.snapshot().version
        change(m, 1, 3, "phoneCall")
        assertEquals(version, m.snapshot().version)
        change(m, 1, 3, "backupCamera")
        change(m, 1, 4, "phoneCall")
        change(m, 1, 4, "phoneCall")
        assertFalse(m.canRecoverScreen())
        change(m, 1, 4, "unknown")
        assertFalse(m.canRecoverScreen())
        change(m, 1, 4, "backupCamera")
        assertTrue(m.canRecoverScreen())
    }
    @Test fun nativeTakeAfterStartupSurvivesTemporaryBorrowAndRequiresReturnIntent() {
        val m = ResourceModes()
        m.markSourceStarted()
        change(m, 1, 1, reason = "screen native")
        change(m, 1, 3, "phoneCall")
        change(m, 1, 4, "phoneCall")
        assertEquals(2L, resource(m, 1)["permanentEntity"])
        assertFalse(m.prepareScreenRecovery())
        m.requestUi()
        assertTrue(m.prepareScreenRecovery())
    }
    @Test fun earlyNativeTakeRetainsOneLateSourceRecoveryButNeverOverridesBorrow() {
        val m = ResourceModes()
        change(m, 1, 1, reason = "screen native")
        m.markSourceStarted()
        change(m, 1, 3, "phoneCall")
        assertFalse(m.prepareScreenRecovery())
        change(m, 1, 4, "phoneCall")
        assertTrue(m.prepareScreenRecovery())
        assertEquals(1L, resource(m, 1)["entity"])
        change(m, 1, 1, reason = "screen native")
        assertFalse(m.prepareScreenRecovery())
    }
    @Test fun requestUiDoesNotCancelActiveCameraOrAudioBorrow() {
        val m = ResourceModes()
        change(m, 1, 3, "backupCamera"); change(m, 2, 3, "phoneCall")
        m.requestUi()
        assertFalse(m.canRecoverScreen())
        change(m, 1, 4, "backupCamera")
        assertTrue(m.prepareScreenRecovery())
        assertEquals(2L, resource(m, 2)["entity"])
    }

    @Test fun audioTakeRequiresExplicitCommitAfterTemporaryAudioBorrowEnds() {
        val m = ResourceModes()
        change(m, 2, 3, "standby")
        assertNull(m.mainAudioTakeVersion())
        change(m, 2, 4, "standby")
        assertNull(m.mainAudioTakeVersion())

        change(m, 2, 1, reason = "audio native", takeConstraint = 500)
        change(m, 2, 3, "standby")
        assertNull(m.mainAudioTakeVersion())
        m.requestUi()
        change(m, 2, 4, "standby")
        change(m, 1, 3, "backupCamera")
        assertEquals(2L, resource(m, 2)["permanentEntity"])
        assertTrue(m.commitMainAudioTake(requireNotNull(m.mainAudioTakeVersion())))
        assertEquals(1L, resource(m, 2)["entity"])
        assertEquals(1L, resource(m, 2)["permanentEntity"])
        assertEquals(2L, resource(m, 1)["entity"])
        assertNull(m.mainAudioTakeVersion())
    }

    @Test fun unsupportedOrMissingTakeConstraintsDoNotPermitRecovery() {
        val m = ResourceModes()
        change(m, 2, 1)
        assertNull(m.mainAudioTakeVersion())
        change(m, 2, 1, takeConstraint = 1000)
        assertNull(m.mainAudioTakeVersion())
        change(m, 2, 1, takeConstraint = 100)
        assertNotNull(m.mainAudioTakeVersion())
        change(m, 2, 2)
        assertNull(m.mainAudioTakeVersion())
    }

    @Test fun repeatedCarTakeAndNewAudioBorrowInvalidateAnInFlightTake() {
        val m = ResourceModes()
        change(m, 2, 1, takeConstraint = 500)
        val first = requireNotNull(m.mainAudioTakeVersion())
        change(m, 2, 1, takeConstraint = 500)
        assertFalse(m.commitMainAudioTake(first))
        val second = requireNotNull(m.mainAudioTakeVersion())
        change(m, 2, 3, "standby")
        change(m, 2, 4, "standby")
        assertFalse(m.commitMainAudioTake(second))
        assertEquals(2L, resource(m, 2)["permanentEntity"])
    }
    @Test fun malformedAndUnknownEntriesDoNotChangeKnownState() {
        val m = ResourceModes(); val before = m.snapshot()
        m.change(mapOf("resources" to listOf(null, mapOf("resourceID" to 1, "transferType" to 3), mapOf("resourceID" to 99, "transferType" to 1))))
        assertEquals(before, m.snapshot())
        change(m, 1, 99)
        assertEquals(before, m.snapshot())
    }
    @Test fun phoneAndSpeechStateAreIndependentOfMediaResource() {
        val m = ResourceModes()
        m.change(mapOf("appStates" to listOf(mapOf("appStateID" to 2, "state" to true), mapOf("appStateID" to 1, "speechMode" to 1))))
        val apps = (m.snapshot().params["appStates"] as List<*>).map { it as Map<*, *> }
        assertEquals(2L, apps.single { it["appStateID"] == 2L }["entity"])
        assertEquals(1L, apps.single { it["appStateID"] == 1L }["speechMode"])
        assertEquals(1L, resource(m, 2)["entity"])
    }
}

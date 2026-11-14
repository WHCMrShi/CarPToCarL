package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.*
import org.junit.Test

class Q4AudioResourcesTest {
    private fun event(type: Int, id: String? = null) = mutableMapOf<String, Any>("transferType" to type).apply { id?.let { put("borrowID", it) } }
    @Test fun navigationBorrowPreservesRadioAndMediaUpgradeIsPermanent() {
        val state = Q4AudioResources()
        state.change(event(1))
        assertTrue(state.commit(checkNotNull(state.prepare(true))))
        assertTrue(state.snapshot().owned)
        assertEquals(2L, state.snapshot().permanent)
        assertTrue(state.commit(checkNotNull(state.release())))
        assertFalse(state.snapshot().owned)
        assertTrue(state.commit(checkNotNull(state.prepare(true))))
        assertTrue(state.commit(checkNotNull(state.prepare(false))))
        assertEquals(1L, state.snapshot().permanent)
        assertFalse(state.snapshot().borrowedByPhone)
    }
    @Test fun nestedDuplicateAndAnonymousBorrowersMustAllReturn() {
        val state = Q4AudioResources()
        state.change(event(3, "mute")); state.change(event(3, "mute"))
        state.change(event(3, "call")); state.change(event(3)); state.change(event(3))
        assertEquals(4, state.snapshot().borrowers)
        assertNull(state.prepare(false)); assertNull(state.prepare(true))
        state.change(event(4, "mute")); state.change(event(4, "unknown")); state.change(event(4))
        assertFalse(state.snapshot().owned)
        state.change(event(4, "call")); state.change(event(4))
        assertTrue(state.snapshot().owned)
    }
    @Test fun constraintsBlockBackgroundButAllowExplicitMediaWhenDeclared() {
        val state = Q4AudioResources()
        state.change(event(1).apply { put("takeConstraint", 500); put("borrowConstraint", 500) })
        assertNull(state.prepare(true)); assertNotNull(state.prepare(false))
        state.change(event(1).apply { put("takeConstraint", 1000); put("borrowConstraint", 1000) })
        assertNull(state.prepare(true)); assertNull(state.prepare(false))
    }
    @Test fun carBorrowDuringRequestInvalidatesLateSuccessfulPublication() {
        val state = Q4AudioResources()
        state.change(event(1))
        val request = checkNotNull(state.prepare(false))
        state.change(event(3, "call"))
        assertFalse(state.commit(request))
        assertFalse(state.snapshot().owned)
        assertEquals(2L, state.snapshot().permanent)
    }
    @Test fun malformedAndOverflowBorrowingNeverAccidentallyUnmutes() {
        val state = Q4AudioResources()
        val initial = state.snapshot()
        state.change(event(99)); state.change(mapOf("transferType" to "borrow"))
        assertEquals(initial, state.snapshot())
        repeat(17) { state.change(event(3, "b$it")) }
        repeat(17) { state.change(event(4, "b$it")) }
        assertFalse(state.snapshot().owned)
    }
    @Test fun rtsp200WithExplicitRejectionOrMalformedBodyIsNotOwnership() {
        assertTrue(Q4AudioResources.accepted("RTSP/1.0 200 OK", ByteArray(0)))
        assertTrue(Q4AudioResources.accepted("RTSP/1.0 200 OK", BinaryPlist.encode(mapOf("status" to 0))))
        assertFalse(Q4AudioResources.accepted("RTSP/1.0 200 OK", BinaryPlist.encode(mapOf("status" to -1))))
        assertFalse(Q4AudioResources.accepted("RTSP/1.0 200 OK", BinaryPlist.encode(mapOf("status" to "0"))))
        assertFalse(Q4AudioResources.accepted("RTSP/1.0 200 OK", byteArrayOf(1, 2)))
        assertFalse(Q4AudioResources.accepted("RTSP/1.0 500 Error", ByteArray(0)))
    }
    @Test fun audioTakeAndReturnDoNotChangeAnActiveReverseCameraBorrow() {
        val modes = ResourceModes()
        modes.change(mapOf("resources" to listOf(mapOf("resourceID" to 1, "transferType" to 3, "borrowID" to "camera"))))
        modes.change(mapOf("resources" to listOf(event(1).apply { put("resourceID", 2) })))
        assertTrue(modes.audio.commit(checkNotNull(modes.audio.prepare(true))))
        assertFalse(modes.canRecoverScreen())
        assertTrue(modes.audio.commit(checkNotNull(modes.audio.release())))
        assertFalse(modes.canRecoverScreen())
        modes.change(mapOf("resources" to listOf(mapOf("resourceID" to 1, "transferType" to 4, "borrowID" to "camera"))))
        assertTrue(modes.canRecoverScreen())
    }
}

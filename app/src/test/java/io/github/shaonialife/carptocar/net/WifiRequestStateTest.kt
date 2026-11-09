package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.util.OwnedSlot
import org.junit.Assert.*
import org.junit.Test

class WifiRequestStateTest {
    @Test fun unavailableAfterSuccessfulBindRemovesResultAndPreventsLateRebind() {
        val state = WifiRequestState<String>()
        assertTrue(state.available("old") { true })
        assertEquals("old", state.unavailable())
        assertNull(state.get())
        assertFalse(state.available("late") { error("retired request must not bind") })
        assertNull(state.unavailable())
    }
    @Test fun unavailableBeforeAvailableDoesNotCreateAConnection() {
        val state = WifiRequestState<String>()
        assertNull(state.unavailable())
        assertFalse(state.available("late") { error("terminal") })
    }
    @Test fun lateLossOfOlderNetworkCannotRemoveReplacement() {
        val state = WifiRequestState<String>()
        state.available("old") { true }; state.available("new") { true }
        assertFalse(state.lost("old"))
        assertEquals("new", state.get())
        assertTrue(state.lost("new"))
        assertNull(state.get())
        assertFalse(state.lost("new"))
    }
    @Test fun failedProcessBindCannotPublishReusableNetwork() {
        val state = WifiRequestState<String>()
        assertFalse(state.available("candidate") { false })
        assertNull(state.get())
    }
    @Test fun retiredCallbackCannotWithdrawAnotherOwnersNetwork() {
        val slot = OwnedSlot<Any>()
        val old = Any(); val next = Any()
        val state = WifiRequestState<String>()
        slot.replace(old, { true }, {})
        slot.runIfCurrent(old) { state.available("old") { true } }
        slot.replace(next, { true }, { state.unavailable() })
        var lost = false
        assertFalse(slot.runIfCurrent(old) { state.unavailable(); lost = true })
        assertFalse(lost)
    }
}

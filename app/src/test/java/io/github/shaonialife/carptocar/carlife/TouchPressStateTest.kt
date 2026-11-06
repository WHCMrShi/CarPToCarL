package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPressStateTest {
    @Test fun heldTipStaysOneGestureUntilActualRelease() {
        val state = TouchPressState()
        assertEquals(TouchPressState.Change.DOWN, state.accept(true))
        // 报告可在任意长停顿后到达；状态机没有 elapsed-time 猜测或计时器。
        repeat(100) { assertEquals(TouchPressState.Change.MOVE, state.accept(true)) }
        assertTrue(state.pressed)
        assertEquals(TouchPressState.Change.UP, state.accept(false))
        assertEquals(TouchPressState.Change.IDLE, state.accept(false))
        assertEquals(TouchPressState.Change.DOWN, state.accept(true))
    }

    @Test fun explicitSessionResetStartsFreshGesture() {
        val state = TouchPressState()
        state.accept(true)
        state.reset()
        assertFalse(state.pressed)
        assertEquals(TouchPressState.Change.IDLE, state.accept(false))
        assertEquals(TouchPressState.Change.DOWN, state.accept(true))
    }

    @Test fun retiringInputRejectsOldReaderTokenAndNewInputStartsReleased() {
        val state = TouchPressState()
        val old = state.newSession()
        state.accept(true)
        val fresh = state.newSession()
        assertFalse(state.isCurrent(old))
        assertTrue(state.isCurrent(fresh))
        assertFalse(state.pressed)
        assertEquals(TouchPressState.Change.DOWN, state.accept(true))
    }
}

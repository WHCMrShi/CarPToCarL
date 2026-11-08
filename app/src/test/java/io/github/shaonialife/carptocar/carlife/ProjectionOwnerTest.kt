package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class ProjectionOwnerTest {
    @Test fun completionBeforeAttachmentStillRetiresCurrentOwnerOnce() {
        val state = ProjectionOwner<Any>()
        val video = Any()
        state.bind(video)
        assertTrue(state.end(video))
        assertFalse(state.isCurrent(video))
        assertFalse(state.end(video))
    }

    @Test fun lateOldCallbackCannotClearReplacementOwner() {
        val state = ProjectionOwner<Any>()
        val old = Any()
        val next = Any()
        state.bind(old)
        state.bind(next)
        assertFalse(state.end(old))
        assertTrue(state.isCurrent(next))
        assertTrue(state.end(next))
    }

    @Test fun temporaryDetachClearsOwnershipAndAllowsFreshBinding() {
        val state = ProjectionOwner<Any>()
        val video = Any()
        state.bind(video)
        state.clear()
        assertFalse(state.end(video))
        state.bind(video)
        assertTrue(state.end(video))
    }
}

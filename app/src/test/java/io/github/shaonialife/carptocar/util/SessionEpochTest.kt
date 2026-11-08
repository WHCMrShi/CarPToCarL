package io.github.shaonialife.carptocar.util

import org.junit.Assert.*
import org.junit.Test

class SessionEpochTest {
    @Test fun setupFinishingAfterEndCannotPublish() {
        val state = SessionEpoch(); val client = Any()
        state.begin(client)
        val lease = state.capture(client)!!
        assertTrue(state.end(client) {})
        var published = false
        assertFalse(state.commit(lease) { published = true })
        assertFalse(published)
    }
    @Test fun lateEndCannotRetireReplacementAndReusedOwnerHasNewEpoch() {
        val state = SessionEpoch(); val first = Any(); val next = Any()
        state.begin(first); val old = state.capture(first)!!
        state.begin(next)
        assertFalse(state.end(first) { fail("retired replacement") })
        assertTrue(state.commit(state.capture(next)!!) {})
        state.begin(first)
        assertFalse(state.commit(old) {})
    }
}

package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class WifiIdentityProofTest {
    @Test fun hiddenSsidRequiresFreshMatchingReceiverAndExactInterface() {
        var now = 1_000L
        val proof = WifiIdentityProof({ now })
        val target = "11:22:33:44:55:66"
        assertFalse(proof.accepts("car", null, target, listOf("10.0.0.2")))
        assertTrue(proof.record(target, "112233445566".toLong(16).toString(), "10.0.0.2"))
        assertTrue(proof.accepts("car", null, target, listOf("10.0.0.2")))
        assertFalse(proof.accepts("car", null, target, listOf("10.0.0.3")))
        assertFalse(proof.accepts("car", null, "AA:BB:CC:DD:EE:FF", listOf("10.0.0.2")))
        assertFalse(proof.accepts("car", "home", target, listOf("10.0.0.2")))
        now += 30_001
        assertFalse(proof.accepts("car", null, target, listOf("10.0.0.2")))
        assertTrue(proof.accepts("car", "car", target, emptyList()))
    }
    @Test fun invalidInvitesAndClearedSessionsCannotAuthorizeBinding() {
        val proof = WifiIdentityProof({ 0 })
        assertFalse(proof.record("11:22:33:44:55:66", "other", "10.0.0.2"))
        assertFalse(proof.record("11:22:33:44:55:66", "112233445566", "127.0.0.1"))
        assertTrue(proof.record("11:22:33:44:55:66", "112233445566", "10.0.0.2"))
        proof.clear()
        assertFalse(proof.accepts("car", null, "11:22:33:44:55:66", listOf("10.0.0.2")))
    }
}

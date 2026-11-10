package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class LegacySuggestionIsolationTest {
    @Test fun failedUpgradeCleanupNeverAdmitsRequestsAndSuccessRunsOnlyOnce() {
        val gate = LegacySuggestionIsolation()
        var cleanupCalls = 0
        var requests = 0
        if (gate.ensure { cleanupCalls++; false }) requests++
        assertEquals(0, requests)
        if (gate.ensure { cleanupCalls++; true }) requests++
        if (gate.ensure { fail("must not remove suggestions during an established session"); false }) requests++
        assertEquals(2, cleanupCalls)
        assertEquals(2, requests)
    }
}

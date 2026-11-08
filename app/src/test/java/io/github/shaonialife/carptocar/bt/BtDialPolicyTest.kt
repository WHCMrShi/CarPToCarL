package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class BtDialPolicyTest {
    @Test fun explicitTargetNeverBroadensAfterFailuresOrDuringRecovery() {
        repeat(8) {
            assertFalse(BtDialPolicy.allows("car", "headset", false, false, null))
            assertFalse(BtDialPolicy.allows("car", "other-car", false, true, "other-car"))
        }
        assertTrue(BtDialPolicy.allows("CAR", "car", false, false, null))
    }
    @Test fun newInboundOrProjectionStopsAnEnumerationAlreadyInProgress() {
        assertTrue(BtDialPolicy.allows(null, "car", false, false, null))
        assertFalse(BtDialPolicy.allows(null, "car", true, false, null))
        assertFalse(BtDialPolicy.allows(null, "car", false, true, null))
        assertTrue(BtDialPolicy.allows("car", "car", false, true, "car"))
        assertFalse(BtDialPolicy.allows(null, "headset", false, true, "car"))
    }
}

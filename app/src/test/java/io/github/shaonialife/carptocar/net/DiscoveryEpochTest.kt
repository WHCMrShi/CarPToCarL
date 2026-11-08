package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class DiscoveryEpochTest {
    @Test fun lostServiceCannotBeResurrectedByOldResolveOrRetry() {
        val epochs = DiscoveryEpoch()
        val old = epochs.found("car")
        epochs.lost("car")
        assertFalse(epochs.publish(old) { fail("lost endpoint must not be cached") })
        val fresh = epochs.found("car")
        assertFalse(epochs.publish(old) { fail("old resolve must not win over new discovery") })
        assertTrue(epochs.publish(fresh) {})
    }
    @Test fun unrelatedLossDoesNotDiscardCurrentReceiver() {
        val epochs = DiscoveryEpoch()
        val car = epochs.found("car")
        epochs.found("other")
        epochs.lost("other")
        assertTrue(epochs.publish(car) {})
        epochs.reset()
        assertFalse(epochs.publish(car) {})
    }
}

package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class WifiNetworkLeaseTest {
    @Test fun replacementWifiCannotBeBoundByAnOldRequest() {
        val lease = WifiNetworkLease<String>()
        assertTrue(lease.accept("car"))
        assertTrue(lease.accept("car"))
        assertFalse(lease.accept("home"))
        assertFalse(lease.lost("other"))
        assertTrue(lease.lost("car"))
        assertFalse(lease.accept("home"))
        assertFalse(lease.accept("car"))
        assertFalse(lease.lost("car"))
        assertTrue(WifiNetworkLease<String>().accept("new-car-request"))
    }
}

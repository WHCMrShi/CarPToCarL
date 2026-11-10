package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test

class WifiAssociationCacheTest {
    @Test fun unknownUnrelatedAndLostNetworkNeverAuthorizeVehicleReuse() {
        val cache = WifiAssociationCache<Int>()
        cache.update(1, "home")
        assertFalse(cache.matches("car", listOf(1)))
        cache.update(2, "car")
        assertTrue(cache.matches("car", listOf(1, 2)))
        assertFalse(cache.matches("car", listOf(1)))
        cache.update(2, null)
        assertFalse(cache.matches("car", listOf(1, 2)))
        cache.update(2, "car"); cache.lost(2)
        assertFalse(cache.matches("car", listOf(1, 2)))
        cache.update(2, "car"); cache.clear()
        assertFalse(cache.matches("car", listOf(2)))
    }
}

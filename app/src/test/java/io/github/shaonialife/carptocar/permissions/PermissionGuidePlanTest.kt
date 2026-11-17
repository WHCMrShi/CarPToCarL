package io.github.shaonialife.carptocar.permissions

import org.junit.Assert.*
import org.junit.Test

class PermissionGuidePlanTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val nearby = "android.permission.BLUETOOTH_CONNECT"
    private val notification = "android.permission.POST_NOTIFICATIONS"

    @Test fun approximateLocationCanBeUpgradedWithPairedRequest() {
        // Coarse was granted before; precise is still missing. Android requires the pair.
        assertEquals(listOf(fine, coarse), PermissionGuidePlan.runtimeRequest(listOf(fine), setOf(coarse)))
    }

    @Test fun deniedPermissionsStayQuietAcrossLaterRequests() {
        val attempted = setOf(fine, coarse, notification)
        assertEquals(listOf(nearby), PermissionGuidePlan.runtimeRequest(listOf(nearby, fine, notification), attempted))
        assertTrue(PermissionGuidePlan.runtimeRequest(listOf(fine, coarse, notification), attempted).isEmpty())
    }

    @Test fun revokedPermissionDoesNotCauseAutomaticReprompt() {
        assertTrue(PermissionGuidePlan.runtimeRequest(listOf(nearby), setOf(nearby)).isEmpty())
    }

    @Test fun fullyGrantedSetupRequiresNoRuntimeRequest() {
        assertTrue(PermissionGuidePlan.runtimeRequest(emptyList(), emptySet()).isEmpty())
    }
}

package io.github.shaonialife.carptocar.net

import io.github.shaonialife.carptocar.util.WorkEpoch
import org.junit.Assert.*
import org.junit.Test

class RememberedWifiPresenceTest {
    private val target = "AA:BB:CC:DD:EE:FF"

    @Test fun cachedPasswordAndUnrelatedBluetoothAreNotPresence() {
        assertNull(RememberedWifiPresence.evidence(target, false, false, target, false))
        assertNull(RememberedWifiPresence.evidence(target, false, true, "00:11:22:33:44:55", false))
        assertEquals(RememberedWifiPresence.Evidence.TARGET_ACL,
            RememberedWifiPresence.evidence(target, true, false, null, false))
        assertEquals(RememberedWifiPresence.Evidence.TARGET_IAP2,
            RememberedWifiPresence.evidence(target, false, true, target.lowercase(), false))
        assertEquals(RememberedWifiPresence.Evidence.ASSOCIATED_WIFI,
            RememberedWifiPresence.evidence(target, false, false, null, true))
    }

    @Test fun absentVehicleWaitsWithinOneBudgetWithoutAdmittingNetworkRequests() {
        var time = 0L
        var observations = 0
        var waitingLogs = 0
        val admitted = RememberedWifiPresence.await(
            now = { time }, pause = { time += it }, valid = { true },
            observe = { observations++; null }, onWaiting = { waitingLogs++ }, timeoutMillis = 2_500,
        )
        assertNull(admitted)
        assertEquals(2_500L, time)
        assertEquals(3, observations)
        assertEquals(1, waitingLogs)
    }

    @Test fun lateTargetIap2ReleasesSameWaitingAttemptWithoutRequiringPersistentBluetooth() {
        var time = 0L
        var ready = false
        val admitted = RememberedWifiPresence.await(
            now = { time }, pause = { time += it; ready = true }, valid = { true },
            observe = { RememberedWifiPresence.evidence(target, false, ready, target, false) },
            onWaiting = {},
        )
        ready = false // Admission is a snapshot; Wi-Fi handoff does not depend on a retained ACL.
        assertEquals(RememberedWifiPresence.Evidence.TARGET_IAP2, admitted)
        assertFalse(ready)
        assertEquals(1_000L, time)
    }

    @Test fun disconnectThenNewRoundCannotReviveOldWaiter() {
        val work = WorkEpoch()
        val original = work.capture()
        var time = 0L
        var observations = 0
        val admitted = RememberedWifiPresence.await(
            now = { time }, pause = { time += it; work.end(); work.begin() }, valid = { original.isCurrent },
            observe = { observations++; null }, onWaiting = {},
        )
        assertNull(admitted)
        assertEquals(1, observations)
        assertTrue(work.capture().isCurrent)
    }

    @Test fun freshCredentialsOrTargetChangeDuringObservationPreventAdmission() {
        var valid = true
        val admitted = RememberedWifiPresence.await(
            now = { 0L }, pause = { fail("should not sleep") }, valid = { valid },
            observe = { valid = false; RememberedWifiPresence.Evidence.TARGET_ACL }, onWaiting = {},
        )
        assertNull(admitted)
    }

    @Test fun alreadyAssociatedWifiAdmitsImmediatelyButEvidenceAtDeadlineIsTooLate() {
        assertEquals(RememberedWifiPresence.Evidence.ASSOCIATED_WIFI, RememberedWifiPresence.await(
            now = { 0L }, pause = { fail("already associated") }, valid = { true },
            observe = { RememberedWifiPresence.Evidence.ASSOCIATED_WIFI }, onWaiting = { fail("already associated") },
        ))
        var time = 0L
        assertNull(RememberedWifiPresence.await(
            now = { time }, pause = { fail("expired") }, valid = { true },
            observe = { time = 3_000L; RememberedWifiPresence.Evidence.TARGET_ACL },
            onWaiting = {}, timeoutMillis = 3_000L,
        ))
    }
}

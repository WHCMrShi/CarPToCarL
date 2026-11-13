package io.github.shaonialife.carptocar.bt

import org.junit.Assert.*
import org.junit.Test

class BtProfileReleaseResultTest {
    private val links = BtLinkOwnership { 1L }
    private val scope = BtProfileOperationScope()

    @Test fun missingProxyFailedQueryAndUnexpectedValueRemainUnknown() {
        assertEquals(BtProfileState.UNKNOWN, observeBtProfileState { null })
        assertEquals(BtProfileState.UNKNOWN, observeBtProfileState { throw SecurityException() })
        assertEquals(BtProfileState.UNKNOWN, observeBtProfileState { 99 })
        assertEquals(BtProfileState.DISCONNECTED, observeBtProfileState { 0 })
        assertEquals(BtProfileState.CONNECTING, observeBtProfileState { 1 })
        assertEquals(BtProfileState.CONNECTED, observeBtProfileState { 2 })
        assertEquals(BtProfileState.DISCONNECTING, observeBtProfileState { 3 })
    }

    @Test fun reflectionResultIsNotAnObservationOfDisconnection() {
        assertEquals(BtDisconnectRequest.ACCEPTED, submitBtDisconnect { true })
        assertEquals(BtDisconnectRequest.REJECTED, submitBtDisconnect { false })
        assertEquals(BtDisconnectRequest.UNKNOWN, submitBtDisconnect { null })
        assertEquals(BtDisconnectRequest.UNKNOWN, submitBtDisconnect { throw ReflectiveOperationException() })
    }

    @Test fun interruptedQueryAndSubmissionPreserveCancellation() {
        try {
            assertEquals(BtProfileState.UNKNOWN, observeBtProfileState { throw InterruptedException() })
            assertTrue(Thread.interrupted())
            assertEquals(BtDisconnectRequest.UNKNOWN, submitBtDisconnect { throw InterruptedException() })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun unknownOrTransitioningProfilesDoNotAuthorizeDisconnect() {
        for (state in listOf(BtProfileState.UNKNOWN, BtProfileState.CONNECTING, BtProfileState.DISCONNECTING, BtProfileState.DISCONNECTED)) {
            val result = disconnectProfilesForOperation(
                scope.capture(links), { mapOf("HFP" to state) },
                { error("no confirmed connection") }, { _, _, _ -> error("nothing submitted") },
                { error("no device fallback") },
            )
            assertEquals(state, result.after["HFP"])
            assertTrue(result.requests.isEmpty())
            assertNull(result.deviceRequest)
        }
    }

    @Test fun acceptedRequestStillOnlineIsReportedAsOnlineAfterBoundedFallback() {
        val waits = mutableListOf<Long>()
        val result = disconnectProfilesForOperation(
            scope.capture(links), { mapOf("HFP" to BtProfileState.CONNECTED) },
            { { BtDisconnectRequest.ACCEPTED } },
            { _, timeout, _ -> waits += timeout; mapOf("HFP" to BtProfileState.CONNECTED) },
            { BtDisconnectRequest.ACCEPTED },
        )
        assertEquals(listOf(1500L, 500L), waits)
        assertEquals(BtDisconnectRequest.ACCEPTED, result.requests["HFP"])
        assertEquals(BtDisconnectRequest.ACCEPTED, result.deviceRequest)
        assertEquals(BtProfileState.CONNECTED, result.after["HFP"])
        assertTrue(result.describe().contains("after={HFP=CONNECTED}"))
    }

    @Test fun rejectedRequestCanBeFollowedByObservedDisconnection() {
        val result = disconnectProfilesForOperation(
            scope.capture(links), { mapOf("HFP" to BtProfileState.CONNECTED) },
            { { BtDisconnectRequest.REJECTED } },
            { _, _, _ -> mapOf("HFP" to BtProfileState.DISCONNECTED) },
            { error("already offline") },
        )
        assertEquals(BtDisconnectRequest.REJECTED, result.requests["HFP"])
        assertEquals(BtProfileState.DISCONNECTED, result.after["HFP"])
    }

    @Test fun failedPostQueryOrDisconnectingStateDoesNotAuthorizeDeviceFallback() {
        for (state in listOf(BtProfileState.UNKNOWN, BtProfileState.DISCONNECTING)) {
            val result = disconnectProfilesForOperation(
                scope.capture(links), { mapOf("HFP" to BtProfileState.CONNECTED) },
                { { BtDisconnectRequest.ACCEPTED } },
                { _, _, _ -> mapOf("HFP" to state) },
                { error("unknown/transition is not confirmed online") },
            )
            assertEquals(state, result.after["HFP"])
            assertNull(result.deviceRequest)
        }
    }

    @Test fun unavailableLeAudioDoesNotHideHfpOutcomeOrBecomeAReleaseTarget() {
        val result = disconnectProfilesForOperation(
            scope.capture(links), { mapOf("HFP" to BtProfileState.CONNECTED, "LE" to BtProfileState.UNKNOWN) },
            { profile -> assertEquals("HFP", profile); null },
            { targets, _, _ ->
                assertEquals(setOf("HFP"), targets)
                mapOf("HFP" to BtProfileState.DISCONNECTED, "LE" to BtProfileState.UNKNOWN)
            },
            { error("HFP already offline") },
        )
        assertEquals(mapOf("HFP" to BtDisconnectRequest.UNKNOWN), result.requests)
        assertEquals(BtProfileState.DISCONNECTED, result.after["HFP"])
        assertEquals(BtProfileState.UNKNOWN, result.after["LE"])
    }

    @Test fun supersededQueryCannotBeReportedAsCurrentReleaseCompletion() {
        val result = disconnectProfilesForOperation(
            scope.capture(links), { mapOf("HFP" to BtProfileState.CONNECTED) },
            { { BtDisconnectRequest.ACCEPTED } },
            { _, _, _ -> links.claim("inbound"); mapOf("HFP" to BtProfileState.DISCONNECTED) },
            { error("superseded") },
        )
        assertTrue(result.superseded)
        assertTrue(result.describe().contains("superseded=true"))
        assertNull(result.deviceRequest)
    }

    @Test fun permissionLostWhilePreparingRequestIsUnknownAndStillRechecked() {
        val result = disconnectProfilesForOperation(
            scope.capture(links), { mapOf("HFP" to BtProfileState.CONNECTED) },
            { throw SecurityException() },
            { _, _, _ -> mapOf("HFP" to BtProfileState.UNKNOWN) },
            { error("unknown state cannot authorize fallback") },
        )
        assertEquals(BtDisconnectRequest.UNKNOWN, result.requests["HFP"])
        assertEquals(BtProfileState.UNKNOWN, result.after["HFP"])
    }
}

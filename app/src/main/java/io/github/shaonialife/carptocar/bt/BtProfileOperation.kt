package io.github.shaonialife.carptocar.bt

import java.util.concurrent.atomic.AtomicLong

/** Invalidates delayed work on stop/start or target changes without owning Android I/O locks. */
internal class BtProfileOperationScope {
    private val generation = AtomicLong()
    fun invalidate() { generation.incrementAndGet() }
    fun capture(links: BtLinkOwnership, current: () -> Boolean = { true }): BtProfileOperation {
        val epoch = generation.get()
        return BtProfileOperation(links.observe(), { generation.get() == epoch }, current)
    }
}

internal class BtProfileOperation(
    val observation: BtLinkOwnership.Observation,
    private val sameGeneration: () -> Boolean,
    private val current: () -> Boolean,
) {
    // Safe to inspect from an inbound/stop thread: no caller-thread interruption predicates.
    fun isSuperseded(): Boolean = !sameGeneration() || !observation.isCurrent
    fun isCurrent(): Boolean = !Thread.currentThread().isInterrupted && !isSuperseded() && current()
    fun permitsDeviceDisconnect(): Boolean = isCurrent() && observation.permitsDeviceDisconnect
}

internal enum class BtProfileState { CONNECTED, DISCONNECTED, CONNECTING, DISCONNECTING, UNKNOWN }

/** ACCEPTED means the platform accepted the request, not that the profile is offline. */
internal enum class BtDisconnectRequest { ACCEPTED, REJECTED, UNKNOWN }

/** BluetoothProfile's four connection states; failed/missing queries remain UNKNOWN. */
internal fun observeBtProfileState(query: () -> Int?): BtProfileState = try {
    when (query()) {
        0 -> BtProfileState.DISCONNECTED
        1 -> BtProfileState.CONNECTING
        2 -> BtProfileState.CONNECTED
        3 -> BtProfileState.DISCONNECTING
        else -> BtProfileState.UNKNOWN
    }
} catch (error: Exception) {
    if (error is InterruptedException) Thread.currentThread().interrupt()
    BtProfileState.UNKNOWN
}

internal fun submitBtDisconnect(request: () -> Any?): BtDisconnectRequest = try {
    when (request()) {
        true -> BtDisconnectRequest.ACCEPTED
        false -> BtDisconnectRequest.REJECTED
        else -> BtDisconnectRequest.UNKNOWN
    }
} catch (error: Exception) {
    if (error is InterruptedException) Thread.currentThread().interrupt()
    BtDisconnectRequest.UNKNOWN
}

internal data class BtProfileReleaseResult<P>(
    val before: Map<P, BtProfileState> = emptyMap(),
    val requests: Map<P, BtDisconnectRequest> = emptyMap(),
    val after: Map<P, BtProfileState> = emptyMap(),
    val deviceRequest: BtDisconnectRequest? = null,
    val superseded: Boolean = false,
) {
    fun describe(): String =
        "before=$before requests=$requests after=$after deviceRequest=${deviceRequest ?: "NOT_ATTEMPTED"} superseded=$superseded"
}

/** Checks again after each potentially blocking query/wait. Submitted platform calls cannot be recalled. */
internal fun <P> disconnectProfilesForOperation(
    operation: BtProfileOperation,
    snapshot: () -> Map<P, BtProfileState>,
    prepareDisconnect: (P) -> (() -> BtDisconnectRequest)?,
    awaitDisconnected: (Set<P>, Long, () -> Boolean) -> Map<P, BtProfileState>,
    disconnectDevice: () -> BtDisconnectRequest,
): BtProfileReleaseResult<P> {
    var before = emptyMap<P, BtProfileState>()
    val requests = linkedMapOf<P, BtDisconnectRequest>()
    var after = emptyMap<P, BtProfileState>()
    var deviceRequest: BtDisconnectRequest? = null
    fun result() = BtProfileReleaseResult(before, requests.toMap(), after, deviceRequest, !operation.isCurrent())
    if (!operation.isCurrent()) return result()
    before = snapshot()
    if (!operation.isCurrent()) return result()
    val targets = before.filterValues { it == BtProfileState.CONNECTED }.keys
    if (targets.isEmpty()) { after = before; return result() }
    for (profile in targets) {
        if (!operation.isCurrent()) return result()
        val action = try {
            prepareDisconnect(profile)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            null
        }
        if (!operation.isCurrent()) return result()
        requests[profile] = action?.invoke() ?: BtDisconnectRequest.UNKNOWN
    }
    if (!operation.isCurrent()) return result()
    after = awaitDisconnected(targets, 1_500L, operation::isCurrent)
    // Unknown/transitioning status alone must never escalate to a whole-device disconnect.
    if (operation.isCurrent() && after.values.any { it == BtProfileState.CONNECTED } &&
        operation.permitsDeviceDisconnect()) {
        deviceRequest = disconnectDevice()
        if (operation.isCurrent()) {
            val remaining = after.filterValues { it == BtProfileState.CONNECTED }.keys
            after = awaitDisconnected(remaining, 500L, operation::isCurrent)
        }
    }
    return result()
}

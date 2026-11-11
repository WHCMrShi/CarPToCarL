package io.github.shaonialife.carptocar.bt

import java.util.concurrent.atomic.AtomicLong

/** Invalidates delayed work on stop/start or target changes without owning Android I/O locks. */
internal class BtProfileOperationScope {
    private val generation = AtomicLong()
    fun invalidate() { generation.incrementAndGet() }
    fun capture(links: BtLinkOwnership, current: () -> Boolean = { true }): BtProfileOperation {
        val epoch = generation.get()
        return BtProfileOperation(links.observe()) { generation.get() == epoch && current() }
    }
}

internal class BtProfileOperation(
    val observation: BtLinkOwnership.Observation,
    private val current: () -> Boolean,
) {
    fun isCurrent(): Boolean = !Thread.currentThread().isInterrupted && current() && observation.isCurrent
    fun permitsDeviceDisconnect(): Boolean = isCurrent() && observation.permitsDeviceDisconnect
}

/** Checks again after each potentially blocking query/wait. Submitted platform calls cannot be recalled. */
internal fun <P> disconnectProfilesForOperation(
    operation: BtProfileOperation,
    connected: () -> List<P>,
    prepareDisconnect: (P) -> (() -> Unit)?,
    awaitDisconnected: (Long, () -> Boolean) -> Boolean,
    disconnectDevice: () -> Unit,
): List<P> {
    if (!operation.isCurrent()) return emptyList()
    val before = connected()
    if (!operation.isCurrent() || before.isEmpty()) return emptyList()
    val submitted = mutableListOf<P>()
    for (profile in before) {
        if (!operation.isCurrent()) return submitted
        val action = prepareDisconnect(profile)
        if (!operation.isCurrent()) return submitted
        if (action != null) { action(); submitted += profile }
    }
    if (operation.isCurrent() && !awaitDisconnected(1_500L, operation::isCurrent) &&
        operation.permitsDeviceDisconnect()) {
        disconnectDevice()
        if (operation.isCurrent()) awaitDisconnected(500L, operation::isCurrent)
    }
    return submitted
}

package io.github.shaonialife.carptocar.bt

/** Keeps the original recovery window, but pauses it for an accepted, still-live Wi-Fi handoff. */
internal class BtLinkRecovery(private val clock: () -> Long, private val windowMs: Long) {
    data class Decision(
        val target: String? = null,
        val expectedHandoff: Boolean = false,
        val owner: BtLinkOwnership.Token? = null,
        val expired: Boolean = false,
    )
    private data class Loss(val owner: BtLinkOwnership.Token, val address: String, val at: Long)
    private data class Handoff(val owner: BtLinkOwnership.Token, val acceptedAndLive: () -> Boolean)

    @Volatile private var loss: Loss? = null
    @Volatile private var handoff: Handoff? = null

    val address: String? get() = loss?.address
    val lostAt: Long get() = loss?.at ?: 0L

    @Synchronized fun expectHandoff(owner: BtLinkOwnership.Token, acceptedAndLive: () -> Boolean) {
        if (owner.isCurrent) handoff = Handoff(owner, acceptedAndLive)
    }

    @Synchronized fun noteLost(address: String?, owner: BtLinkOwnership.Token) {
        if (address != null && owner.isCurrent) loss = Loss(owner, address, clock())
    }

    fun expectedHandoff(): Boolean = loss?.let(::expectedHandoff) == true

    private fun expectedHandoff(observed: Loss): Boolean {
        val expected = handoff ?: return false
        // The callback reads AirPlay/work ownership; never call it under this object's monitor.
        return expected.owner === observed.owner && observed.owner.isCurrent &&
            expected.acceptedAndLive() && handoff === expected && loss === observed && observed.owner.isCurrent
    }

    fun decision(sessionActive: Boolean): Decision {
        if (!sessionActive) return Decision()
        val observed = loss ?: return Decision()
        if (expectedHandoff(observed)) return Decision(expectedHandoff = true, owner = observed.owner)
        if (loss !== observed) return Decision()
        if (!observed.owner.isCurrent) return Decision(owner = observed.owner)
        if (clock() - observed.at > windowMs) return Decision(owner = observed.owner, expired = true)
        return Decision(target = observed.address, owner = observed.owner)
    }

    fun target(sessionActive: Boolean): String? = decision(sessionActive).target

    @Synchronized fun clearLoss(owner: BtLinkOwnership.Token? = null) {
        if (owner == null || loss?.owner === owner) loss = null
    }
    @Synchronized fun reset() { loss = null; handoff = null }
}

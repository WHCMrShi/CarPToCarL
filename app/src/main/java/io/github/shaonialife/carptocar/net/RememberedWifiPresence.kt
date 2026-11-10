package io.github.shaonialife.carptocar.net

/** Admission only: loss of Bluetooth after admission must not cancel the Wi-Fi handoff. */
internal object RememberedWifiPresence {
    enum class Evidence { TARGET_ACL, TARGET_IAP2, ASSOCIATED_WIFI }

    fun evidence(target: String, targetAcl: Boolean, iap2Ready: Boolean, peer: String?, associated: Boolean): Evidence? = when {
        targetAcl -> Evidence.TARGET_ACL
        iap2Ready && target.equals(peer, ignoreCase = true) -> Evidence.TARGET_IAP2
        associated -> Evidence.ASSOCIATED_WIFI
        else -> null
    }

    /** Reuses the existing preconnect worker; no scans, network requests or renewal of the budget. */
    fun await(
        now: () -> Long,
        pause: (Long) -> Unit,
        valid: () -> Boolean,
        observe: () -> Evidence?,
        onWaiting: () -> Unit,
        timeoutMillis: Long = 180_000L,
    ): Evidence? {
        val deadline = now() + timeoutMillis
        var announced = false
        while (valid() && now() < deadline) {
            val evidence = observe()
            if (!valid() || now() >= deadline) return null
            if (evidence != null) return evidence
            if (!announced) {
                onWaiting()
                announced = true
            }
            val remaining = deadline - now()
            if (remaining <= 0L) return null
            pause(minOf(1_000L, remaining))
        }
        return null
    }
}

package io.github.shaonialife.carptocar.net

/** A fresh target receiver invitation, tied to the TCP socket's actual local interface address. */
internal class WifiIdentityProof(private val clock: () -> Long, private val ttlMs: Long = 30_000L) {
    private data class Evidence(val target: String, val local: String, val at: Long)
    private var evidence: Evidence? = null

    @Synchronized fun clear() { evidence = null }

    @Synchronized fun record(target: String?, receiver: String?, localAddress: String?): Boolean {
        val expected = mac(target) ?: return false
        val raw = receiver?.trim() ?: return false
        val matches = mac(raw) == expected || raw.toLongOrNull()?.takeIf { it in 0..0xffffffffffffL }
            ?.toString(16)?.padStart(12, '0')?.uppercase() == expected
        val local = localAddress?.substringBefore('%') ?: return false
        if (!matches || local == "::1" || local == "0.0.0.0" || local.startsWith("127.")) return false
        evidence = Evidence(expected, local, clock())
        return true
    }

    @Synchronized fun accepts(expectedSsid: String?, observedSsid: String?, target: String?, localAddresses: List<String>): Boolean {
        if (expectedSsid.isNullOrEmpty()) return false
        if (observedSsid != null) return expectedSsid == observedSsid
        val proof = evidence ?: return false
        return mac(target) == proof.target && clock() - proof.at in 0..ttlMs &&
            localAddresses.any { it.substringBefore('%') == proof.local }
    }

    private fun mac(raw: String?): String? = raw?.replace(":", "")?.replace("-", "")
        ?.uppercase()?.takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
}

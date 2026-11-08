package io.github.shaonialife.carptocar.bt

/** An explicit target is an allow-list, including during recovery. */
internal object BtDialPolicy {
    fun allows(target: String?, candidate: String?, inbound: Boolean, session: Boolean, recovery: String?): Boolean =
        candidate != null && (target == null || target.equals(candidate, true)) && !inbound &&
            (!session || recovery?.equals(candidate, true) == true)
}

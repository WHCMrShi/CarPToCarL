package io.github.shaonialife.carptocar.bt

internal object BtCompatResumePolicy {
    fun needsNewConnection(fromUser: Boolean, suppressed: Boolean, cancelled: Boolean): Boolean =
        fromUser && (suppressed || cancelled)
}

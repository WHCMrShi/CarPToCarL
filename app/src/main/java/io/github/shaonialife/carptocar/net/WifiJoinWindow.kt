package io.github.shaonialife.carptocar.net

/** Explicit unavailability and cancellation end waiting immediately, including during grace. */
internal class WifiJoinWindow(private val start: Long, private val timeout: Long, private val grace: Long) {
    fun waiting(now: Long, unavailable: Boolean, cancelled: Boolean): Boolean =
        !unavailable && !cancelled && now - start < timeout + grace
    fun inGrace(now: Long): Boolean = grace > 0 && now - start >= timeout
}

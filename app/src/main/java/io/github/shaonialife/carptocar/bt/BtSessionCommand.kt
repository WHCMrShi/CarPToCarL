package io.github.shaonialife.carptocar.bt

/** A command belongs to the ready RFCOMM owner and its original user connection. */
internal class BtSessionCommand(
    private val owner: BtLinkOwnership.Token,
    private val current: () -> Boolean,
    private val log: (String) -> Unit = {},
    private val send: (String) -> Boolean,
) {
    fun run(reason: String): Boolean {
        var sent = false
        var result = "owner-inactive"
        try {
            owner.runIfCurrent {
                result = if (!owner.ready) "not-ready" else if (!current()) "connection-stale" else "sending"
                if (result == "sending") {
                    log("BT-COMMAND: availability begin owner=${owner.id} direction=${owner.direction} reason=$reason")
                    sent = send(reason)
                    result = if (sent) "written" else "channel-unavailable"
                }
            }
        } catch (error: Exception) {
            result = "write-failed:${error.javaClass.simpleName}"
            throw error
        } finally {
            log("BT-COMMAND: availability owner=${owner.id} direction=${owner.direction} result=$result")
        }
        return sent
    }
}

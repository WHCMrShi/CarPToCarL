package io.github.shaonialife.carptocar.bt

/** A command belongs to the ready RFCOMM owner and its original user connection. */
internal class BtSessionCommand(
    private val owner: BtLinkOwnership.Token,
    private val current: () -> Boolean,
    private val send: (String) -> Boolean,
) {
    fun run(reason: String): Boolean {
        var sent = false
        owner.runIfCurrent {
            if (owner.ready && current()) sent = send(reason)
        }
        return sent
    }
}

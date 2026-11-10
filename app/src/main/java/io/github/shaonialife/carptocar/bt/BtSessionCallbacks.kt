package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession

/** Captured before a BT session is published. Delegates retain that connection's immutable ticket. */
class BtSessionCallbacks(
    private val isCurrent: () -> Boolean = { true },
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint, BtLinkOwnership.Token) -> Unit = { _, _ -> },
    private val onWifi: (Iap2SenderSession.WifiConfiguration, BtLinkOwnership.Token) -> Unit = { _, _ -> },
    private val onUpdate: (Iap2SenderSession.CarPlayEndpoint, BtLinkOwnership.Token) -> Unit = { _, _ -> },
    private val onClosed: (BtLinkOwnership.Token) -> Unit = {},
    private val onConnected: (BtLinkOwnership.Token) -> Unit = {},
    val diagnosticConnection: String = "unbound",
    private val diagnosticLog: (String) -> Unit = {},
) {
    fun current(): Boolean = isCurrent()
    private fun deliver(link: BtLinkOwnership.Token, event: String, action: () -> Unit) {
        if (isCurrent() && link.isCurrent) action()
        else runCatching { diagnosticLog("FLOW-DIAG: connection=$diagnosticConnection btOwner=${link.id} direction=${link.direction} event=BT_INPUT_IGNORED kind=$event connectionCurrent=${isCurrent()} ownerCurrent=${link.isCurrent}") }
    }
    fun endpoint(value: Iap2SenderSession.CarPlayEndpoint, link: BtLinkOwnership.Token) = deliver(link, "endpoint") { onEndpoint(value, link) }
    fun wifi(value: Iap2SenderSession.WifiConfiguration, link: BtLinkOwnership.Token) = deliver(link, "wifi") { onWifi(value, link) }
    fun update(value: Iap2SenderSession.CarPlayEndpoint, link: BtLinkOwnership.Token) = deliver(link, "update") { onUpdate(value, link) }
    fun closed(link: BtLinkOwnership.Token) = deliver(link, "closed") { onClosed(link) }
    fun connected(link: BtLinkOwnership.Token) = deliver(link, "connected") { onConnected(link) }
}

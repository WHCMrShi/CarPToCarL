package io.github.shaonialife.carptocar

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.github.shaonialife.carptocar.airplay.AirPlayClient
import io.github.shaonialife.carptocar.bt.BtLinkOwnership
import io.github.shaonialife.carptocar.bt.BtSessionCallbacks
import io.github.shaonialife.carptocar.bt.BtSessionGate
import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import io.github.shaonialife.carptocar.util.WorkEpoch
import io.github.shaonialife.carptocar.util.Bg
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual controller. Compile-only without an Android device; not a hardware BT test. */
class ConnectionReviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private val endpoint = Iap2SenderSession.CarPlayEndpoint("fixture", "fixed-password", 149, 2, 7000, null, null, null, emptyList())
    private fun field(name: String) = SessionController::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) { field(name).set(SessionController, value) }
    private fun work(name: String) = field(name).get(SessionController) as WorkEpoch
    private fun prepare() {
        work("connectionWork").end(); work("connectionWork").begin(); work("setupWork").replace()
        set("appContext", context) // The test package's preferences, not the user's vehicle preferences.
        set("sessionWanted", true); set("connectCancelled", false); set("currentClient", null)
        set("videoSender", null); set("boundCarSsid", null); set("pendingEndpointUpdate", null)
        set("lastGoodEndpoint", endpoint); set("connectRetryScheduled", false); set("connectRetryAttempt", 0)
        SessionController.logEnabled = false
        SessionController.sessionStarting.set(true); BtSessionGate.sessionActive = true
    }
    private fun cancel(link: BtLinkOwnership.Token, parent: WorkEpoch.Ticket, setup: WorkEpoch.Ticket, reason: String) {
        SessionController::class.java.getDeclaredMethod("cancelBtSetup", BtLinkOwnership.Token::class.java,
            WorkEpoch.Ticket::class.java, WorkEpoch.Ticket::class.java, String::class.java).apply { isAccessible = true }
            .invoke(SessionController, link, parent, setup, reason)
    }
    private fun cleanup() {
        work("connectionWork").end(); work("setupWork").end()
        set("connectCancelled", true); set("sessionWanted", false)
        set("connectRetryGeneration", (field("connectRetryGeneration").get(SessionController) as Int) + 1)
        (field("currentClient").get(SessionController) as? AirPlayClient)?.dispose()
        set("currentClient", null); set("connectRetryScheduled", false)
        SessionController.sessionStarting.set(false); BtSessionGate.sessionActive = false
        // Drain cancelled retry sleepers before the next fixture; none can reach a network entry.
        val end = System.nanoTime() + 4_000_000_000L
        while ((Bg.pool as ThreadPoolExecutor).activeCount != 0 && System.nanoTime() < end) Thread.sleep(10)
        check((Bg.pool as ThreadPoolExecutor).activeCount == 0)
    }
    override fun onStart() {
        val results = Bundle()
        try {
            cancellationReleasesGate("BT_SETUP_CLOSED")
            cancellationReleasesGate("BT_PRE_TCP_GRACE_EXPIRED")
            obsoleteCancellationKeepsSuccessor()
            for (kind in listOf("endpoint", "wifi", "update")) pausedOldInputCannotPublishIntoRestart(kind)
            results.putString("result", "6 controller fixtures passed")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("failure", "${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED, results)
        } finally { cleanup() }
    }
    private fun cancellationReleasesGate(reason: String) {
        prepare()
        val links = BtLinkOwnership { 0L }; val link = links.claim("inbound")!!
        val parent = work("connectionWork").capture(); val setup = work("setupWork").capture()
        val client = AirPlayClient(endpoint, log = {})
        set("currentClient", client)
        cancel(link, parent, setup, reason)
        check(!setup.isCurrent); check(!SessionController.sessionStarting.get())
        check(!BtSessionGate.sessionActive); check(field("connectCancelled").get(SessionController) == false)
        check(field("currentClient").get(SessionController) == null); check(client.cancelled)
        check(field("connectRetryScheduled").get(SessionController) == true)
        // The old finally is deliberately invalid, so cancellation must have done all cleanup itself.
        check(!setup.runIfCurrent { error("old finally must be retired") })
        cleanup()
    }
    private fun obsoleteCancellationKeepsSuccessor() {
        prepare()
        val links = BtLinkOwnership { 0L }; val link = links.claim("inbound")!!
        val parent = work("connectionWork").capture(); val old = work("setupWork").capture()
        val next = work("setupWork").replace(); val client = AirPlayClient(endpoint, log = {}); set("currentClient", client)
        cancel(link, parent, old, "BT_PRE_TCP_GRACE_EXPIRED")
        check(next.isCurrent); check(SessionController.sessionStarting.get()); check(BtSessionGate.sessionActive)
        check(field("currentClient").get(SessionController) === client); check(!client.cancelled)
        cleanup()
    }
    private fun pausedOldInputCannotPublishIntoRestart(kind: String) {
        prepare(); SessionController.sessionStarting.set(kind == "update"); set("freshWifiThisStart", null)
        val callbacks = SessionController::class.java.getDeclaredMethod("captureBtCallbacks", String::class.java)
            .apply { isAccessible = true }.invoke(SessionController, "fixture") as BtSessionCallbacks
        val links = BtLinkOwnership { 0L }; val link = links.claim("inbound")!!
        val memory = checkNotNull(field("wifiMemoryLock").get(SessionController))
        lateinit var caller: Thread
        val failure = AtomicReference<Throwable>()
        synchronized(memory) {
            caller = Thread { runCatching {
                when (kind) {
                    "endpoint" -> callbacks.endpoint(endpoint, link)
                    "wifi" -> callbacks.wifi(Iap2SenderSession.WifiConfiguration("fixture", "fixed-password", 149, 2), link)
                    else -> callbacks.update(endpoint, link)
                }
            }.onFailure(failure::set) }.apply { isDaemon = true; start() }
            val end = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < end && !(caller.state == Thread.State.BLOCKED &&
                caller.stackTrace.any { it.methodName == "loadFixedWifi" })) Thread.yield()
            check(caller.state == Thread.State.BLOCKED && caller.stackTrace.any { it.methodName == "loadFixedWifi" })
            work("connectionWork").end(); work("connectionWork").begin(); work("setupWork").replace()
        }
        caller.join(3000); check(!caller.isAlive); check(failure.get() == null)
        check(SessionController.sessionStarting.get() == (kind == "update"))
        check(field("freshWifiThisStart").get(SessionController) == null)
        check(field("pendingEndpointUpdate").get(SessionController) == null)
        cleanup()
    }
}

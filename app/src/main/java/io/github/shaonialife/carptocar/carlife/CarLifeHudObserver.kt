package io.github.shaonialife.carptocar.carlife

/** 每个 HU 会话独立探测；不更改导航/媒体状态，所有错误仅诊断。时钟单位毫秒。 */
internal class CarLifeHudObserver(
    private val send: (Int, ByteArray) -> Boolean,
    private val log: (String) -> Unit,
) {
    private var enabled = false
    private var requestedAt: Long? = null
    private var reply: CarLifeHudProtocol.SubscriptionReply? = null
    private var startAttempted = false
    private var lastSummary = 0L
    private var turns = 0L
    private var assistants = 0L

    @Synchronized fun versionMatched() { enabled = true }

    @Synchronized fun tick(now: Long) {
        if (!enabled) return
        if (requestedAt == null) {
            requestedAt = now
            lastSummary = now
            val ok = send(CarLifeHudProtocol.SUBSCRIBE, CarLifeHudProtocol.subscription(CarLifeHudProtocol.modules))
            log("HUD-DIAG: SUBSCRIBE id=0x18043 modules=[0,1] sent=$ok (TBT=0, assistant=1)")
            return
        }
        if (!startAttempted && (reply != null || now - requestedAt!! >= 5_000)) {
            startAttempted = true
            val response = reply
            val ids = when {
                response == null -> CarLifeHudProtocol.modules
                response.error != null -> emptyList()
                else -> response.entries.filter { it.second == 1 && it.first in CarLifeHudProtocol.modules }.map { it.first!! }
            }
            if (ids.isEmpty()) {
                log("HUD-DIAG: START skipped: no confirmed supported modules / malformed reply")
            } else {
                val ok = send(CarLifeHudProtocol.START, CarLifeHudProtocol.subscription(ids))
                log("HUD-DIAG: START id=0x18045 modules=$ids sent=$ok confirmed=${response != null}; no-reply fallback is attempted once only")
            }
        }
        if (now - lastSummary >= 30_000) {
            lastSummary = now
            log("HUD-DIAG: summary elapsedMs=${now - requestedAt!!} reply=${reply != null} startAttempted=$startAttempted nextTurnRx=$turns assistantRx=$assistants; zero packets does not prove lack of navigation support")
        }
    }

    @Synchronized fun receive(id: Int, payload: ByteArray) {
        if (id == CarLifeHudProtocol.SUBSCRIBE_DONE) {
            reply = CarLifeHudProtocol.reply(payload)
            log("HUD-DIAG: SUBSCRIBE_DONE id=0x10044 bytes=${payload.size} modules=${reply!!.entries} parseError=${reply!!.error ?: "none"}")
        } else {
            if (id == CarLifeHudProtocol.NEXT_TURN) turns++ else assistants++
            log("HUD-DIAG: ${CarLifeHudProtocol.describe(id, payload)}")
        }
    }

    @Synchronized fun moduleStatus(modules: List<Pair<Int, Int>>) {
        val navigation = modules.filter { it.first == 2 || it.first == 8 }
        if (navigation.isNotEmpty()) log("HUD-DIAG: moduleStatus=$navigation (status namespace NAVI=2/EDOG=8)")
    }
}

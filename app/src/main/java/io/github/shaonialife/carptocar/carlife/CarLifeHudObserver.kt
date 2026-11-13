package io.github.shaonialife.carptocar.carlife

/** 每个 HU 会话独立订阅并转交导航数据；诊断只保留状态变化和累计收包数。 */
internal class CarLifeHudObserver(
    private val send: (Int, ByteArray) -> Boolean,
    private val log: (String) -> Unit,
    private val onTurn: (io.github.shaonialife.carptocar.media.HudTurn) -> Unit = {},
) {
    private var enabled = false
    private var requestedAt: Long? = null
    private var reply: CarLifeHudProtocol.SubscriptionReply? = null
    private var startAttempted = false
    private var lastSummary = 0L
    private var turns = 0L
    private var assistants = 0L
    private var invalidTurns = 0L
    private var lastAction: Int? = null
    private var lastTurn: Int? = null
    private val navigation = mutableMapOf<Int, Int>()
    private var navigationChangedAt: Long? = null
    private var lastHudAt: Long? = null

    @Synchronized fun versionMatched() { enabled = true }

    @Synchronized fun tick(now: Long) {
        if (!enabled) return
        if (requestedAt == null) {
            requestedAt = now
            lastSummary = now
            val ok = send(CarLifeHudProtocol.SUBSCRIBE, CarLifeHudProtocol.subscription(CarLifeHudProtocol.modules))
            log("HUD-DIAG: SUBSCRIBE id=0x18043 modules=[0,1] localWrite=$ok")
            if (!ok) {
                startAttempted = true
                log("HUD-DIAG: abandoned subscription; local write failed")
            }
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
                log("HUD-DIAG: START id=0x18045 modules=$ids localWrite=$ok supportConfirmed=${response != null}; activation unverified")
            }
        }
        if (now - lastSummary >= 30_000) {
            lastSummary = now
            log("HUD-DIAG: summary nextTurnRx=$turns invalidTurns=$invalidTurns assistantRx=$assistants " +
                "navigation=$navigation navigationStateAgeMs=${navigationChangedAt?.let { now - it }} lastHudAgeMs=${lastHudAt?.let { now - it }}")
        }
    }

    @Synchronized fun receive(id: Int, payload: ByteArray, now: Long? = null) {
        if (id == CarLifeHudProtocol.SUBSCRIBE_DONE) {
            reply = CarLifeHudProtocol.reply(payload)
            log("HUD-DIAG: SUBSCRIBE_DONE modules=${reply!!.entries} parseError=${reply!!.error ?: "none"} latencyMs=${now?.let { at -> requestedAt?.let { at - it } }}; capability reply only")
        } else {
            lastHudAt = now
            if (id == CarLifeHudProtocol.NEXT_TURN) {
                turns++
                val turn = CarLifeHudProtocol.turn(payload)
                if (turn == null) {
                    invalidTurns++
                    if (invalidTurns == 1L) log("HUD-DIAG: NEXT_TURN invalid bytes=${payload.size}")
                } else {
                    if (lastAction != turn.action || lastTurn != turn.turn) {
                        lastAction = turn.action; lastTurn = turn.turn
                        log("HUD-DIAG: NEXT_TURN action=${turn.action} nextTurn=${turn.turn} distanceM=${turn.distance} iconBytes=${turn.icon?.size ?: 0}")
                    }
                    onTurn(turn)
                }
            } else assistants++
        }
    }

    @Synchronized fun moduleStatus(modules: List<Pair<Int, Int>>, now: Long? = null) {
        val updates = modules.filter { it.first == 2 || it.first == 8 }
        if (updates.any { navigation[it.first] != it.second }) {
            navigationChangedAt = now
            updates.forEach { navigation[it.first] = it.second }
            log("HUD-DIAG: moduleStatus=$updates (NAVI=2/EDOG=8)")
        }
    }
}

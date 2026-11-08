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
    private var startSent = false
    private var lastSummary = 0L
    private var turns = 0L
    private var assistants = 0L
    private val controlCounts = linkedMapOf<Int, Long>()
    private var otherControl = 0L
    private val navigation = mutableMapOf<Int, Int>()
    private var navigationChangedAt: Long? = null
    private var lastHudAt: Long? = null

    /** 只统计消息编号，不保留控制报文正文；最多 32 个编号，额外编号合并计数。 */
    @Synchronized fun controlMessage(id: Int) {
        if (id in controlCounts || controlCounts.size < 32) controlCounts[id] = (controlCounts[id] ?: 0) + 1
        else otherControl++
    }

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
                startSent = ok
                log("HUD-DIAG: START id=0x18045 modules=$ids sent=$ok confirmed=${response != null}; no-reply fallback is attempted once only")
            }
        }
        if (now - lastSummary >= 30_000) {
            lastSummary = now
            log("HUD-DIAG: summary elapsedMs=${now - requestedAt!!} reply=${reply != null} startAttempted=$startAttempted startSent=$startSent nextTurnRx=$turns assistantRx=$assistants " +
                "navigation=$navigation navigationStateAgeMs=${navigationChangedAt?.let { now - it }} lastHudAgeMs=${lastHudAt?.let { now - it }}; zero packets does not prove lack of navigation support")
            log("HUD-DIAG: controlRx=[${controlCounts.entries.joinToString(",") { "0x${it.key.toString(16)}:${it.value}" }}] otherControlRx=$otherControl (bounded ID histogram, not payload capture)")
        }
    }

    @Synchronized fun receive(id: Int, payload: ByteArray, now: Long? = null) {
        if (id == CarLifeHudProtocol.SUBSCRIBE_DONE) {
            reply = CarLifeHudProtocol.reply(payload)
            log("HUD-DIAG: SUBSCRIBE_DONE id=0x10044 bytes=${payload.size} modules=${reply!!.entries} parseError=${reply!!.error ?: "none"} latencyMs=${now?.let { at -> requestedAt?.let { at - it } }}")
        } else {
            lastHudAt = now
            if (id == CarLifeHudProtocol.NEXT_TURN) turns++ else assistants++
            log("HUD-DIAG: ${CarLifeHudProtocol.describe(id, payload)}")
        }
    }

    @Synchronized fun moduleStatus(modules: List<Pair<Int, Int>>, now: Long? = null) {
        val updates = modules.filter { it.first == 2 || it.first == 8 }
        if (updates.isNotEmpty()) {
            if (updates.any { navigation[it.first] != it.second }) navigationChangedAt = now
            updates.forEach { navigation[it.first] = it.second }
            log("HUD-DIAG: moduleStatus=$updates (status namespace NAVI=2/EDOG=8) startSent=$startSent nextTurnRx=$turns")
        }
    }
}

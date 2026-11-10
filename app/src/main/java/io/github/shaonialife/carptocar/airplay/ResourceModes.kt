package io.github.shaonialife.carptocar.airplay

/** Q4 AirPlay resource intents. Controller=1, accessory=2; transfer 1/2/3/4=take/untake/borrow/unborrow.
 * Source: WirelessCarPlay AirPlayCommon.h at 51145ef; corroborated by Q4 PHONE/RVC event pairs.
 */
internal class ResourceModes {
    private class Resource(var permanent: Long = 1) { val borrowers = linkedSetOf<String>(); var overflow = false }
    private val resources = linkedMapOf(1 to Resource(), 2 to Resource())
    private val apps = linkedMapOf<Int, Map<String, Any>>(
        2 to mapOf("appStateID" to 2L, "entity" to 0L),
        3 to mapOf("appStateID" to 3L, "entity" to 0L),
        1 to mapOf("appStateID" to 1L, "entity" to 0L, "speechMode" to -1L),
    )
    private var version = 0L
    private var sourceStarted = false
    private var startupNativeTake = false
    data class Snapshot(val version: Long, val params: Map<String, Any>)
    @Synchronized fun snapshot(): Snapshot = Snapshot(version, mapOf(
        "appStates" to apps.values.toList(),
        "resources" to resources.map { (id, r) -> mapOf("resourceID" to id.toLong(),
            "entity" to if (r.borrowers.isEmpty() && !r.overflow) r.permanent else 2L, "permanentEntity" to r.permanent) },
    ))
    @Synchronized fun markSourceStarted() { sourceStarted = true }
    @Synchronized fun canRecoverAudio(): Boolean = resources.getValue(2).let {
        it.permanent == 1L && it.borrowers.isEmpty() && !it.overflow
    }
    @Synchronized fun canRecoverScreen(): Boolean = resources.getValue(1).let {
        !it.overflow && it.borrowers.isEmpty() && (it.permanent == 1L || startupNativeTake)
    }
    @Synchronized fun prepareScreenRecovery(): Boolean {
        if (!canRecoverScreen()) return false
        val screen = resources.getValue(1)
        if (screen.permanent != 1L) { screen.permanent = 1; version++ }
        startupNativeTake = false
        return true
    }
    @Synchronized fun requestUi() {
        val screen = resources.getValue(1)
        if (screen.permanent != 1L) { screen.permanent = 1; version++ }
        startupNativeTake = false // explicit request returns screen after any temporary borrowers finish
    }
    @Synchronized fun change(params: Map<*, *>) {
        for (raw in (params["resources"] as? List<*>)?.take(16).orEmpty()) {
            val item = raw as? Map<*, *> ?: continue
            val id = (item["resourceID"] as? Number)?.toInt() ?: continue
            val resource = resources[id] ?: continue
            when ((item["transferType"] as? Number)?.toInt()) {
                1 -> {
                    if (id == 1) startupNativeTake = !sourceStarted && params["reasonStr"] == "screen native"
                    if (resource.permanent != 2L) { resource.permanent = 2; version++ }
                }
                2 -> {
                    if (resource.permanent == 2L) { resource.permanent = 1; version++ }
                    if (id == 1) startupNativeTake = false
                }
                3, 4 -> {
                    val borrow = (item["borrowID"] as? String)?.takeIf { it.isNotBlank() && it.length <= 128 } ?: continue
                    if ((item["transferType"] as Number).toInt() == 3) {
                        if (resource.borrowers.size < 16) {
                            if (resource.borrowers.add(borrow)) version++
                        } else if (borrow !in resource.borrowers && !resource.overflow) {
                            resource.overflow = true; version++ // fail closed until a fresh client; never forget a live borrower
                        }
                    } else if (resource.borrowers.remove(borrow)) version++
                }
            }
        }
        for (raw in (params["appStates"] as? List<*>)?.take(8).orEmpty()) {
            val item = raw as? Map<*, *> ?: continue
            val id = (item["appStateID"] as? Number)?.toInt()?.takeIf { it in 1..3 } ?: continue
            val next: Map<String, Any> = if (id == 1) {
                val mode = (item["speechMode"] as? Number)?.toLong()?.takeIf { it in listOf(-1L, 1L, 2L) } ?: continue
                mapOf("appStateID" to 1L, "entity" to if (mode == -1L) 0L else 2L, "speechMode" to mode)
            } else {
                val state = item["state"] as? Boolean ?: continue
                mapOf("appStateID" to id.toLong(), "entity" to if (state) 2L else 0L)
            }
            if (apps[id] != next) { apps[id] = next; version++ }
        }
    }
}

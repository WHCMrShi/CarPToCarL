package io.github.shaonialife.carptocar.media

import java.util.Locale

/** Per-HU input, independent of the selected instrument display mode. No Android or transport IO. */
data class HudTurn(val action: Int, val turn: Int, val road: String, val distance: Int, val icon: ByteArray?)
internal data class NavigationDisplay(
    val title: String, val album: String, val artist: String, val duration: Int,
    val elapsed: Int, val icon: ByteArray?, val iconVersion: Long, val phase: String,
) {
    fun nowPlaying(artwork: ArtworkSource? = null) = io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying(
        title, artist, album, duration, io.github.shaonialife.carptocar.iap2.Iap2NowPlaying.STATUS_PAUSED,
        elapsed, appName = "导航", artworkSource = artwork,
    )
}

internal class NavigationState {
    private var owner: Any? = null
    private var active = false
    private var destination = ""
    private var destinationImage: ByteArray? = null
    private var phase = "idle"
    private var endAt: Long? = null
    private var pending: HudTurn? = null
    private var pendingAt = 0L
    private var pendingStart: Int? = null
    private var lastShow: HudTurn? = null
    private var lastShowAt = 0L
    private var segment: HudTurn? = null
    private var initial = 0
    private var image: ByteArray? = null
    private var imageVersion = 0L
    private var current: HudTurn? = null

    @Synchronized fun begin(source: Any) { reset(); owner = source }
    @Synchronized fun clear() { reset(); owner = null }
    private fun reset() {
        active = false; destination = ""; destinationImage = null; phase = "idle"; endAt = null
        pending = null; segment = null; initial = 0; current = null; setImage(null)
        pendingStart = null; lastShow = null
    }
    private fun setImage(bytes: ByteArray?) {
        if (image?.contentEquals(bytes ?: byteArrayOf()) == true || image == null && bytes == null) return
        image = bytes?.copyOf(); imageVersion++
    }

    @Synchronized fun receive(source: Any, event: HudTurn, now: Long) {
        if (owner !== source || event.distance < 0) return
        expire(now)
        if (event.action == 3 && event.turn == 24) {
            if (event.distance == 1) {
                // User contract: a duplicate destination notification must change nothing.
                if (active) return
                reset(); active = true; destination = event.road; phase = "prepare"
                destinationImage = event.icon?.takeIf { it.isNotEmpty() }?.copyOf()
                setImage(destinationImage)
            } else if (event.distance == 0 && (phase != "end" || active || pending != null)) {
                active = false; pending = null; pendingStart = null; phase = "end"; endAt = now + 5_000
                if (destination.isBlank() && event.road.isNotBlank()) destination = event.road
                setImage(event.icon?.takeIf { it.isNotEmpty() } ?: destinationImage)
            }
            return
        }
        if (event.action !in 1..2) return
        flush(now)
        active = true; endAt = null
        if (event.action == 1) {
            val previous = lastShow
            val duplicate = previous != null && now - lastShowAt in 0..1_000 &&
                previous.turn == event.turn && previous.road == event.road && previous.distance == event.distance &&
                (previous.icon?.contentEquals(event.icon ?: byteArrayOf()) == true || previous.icon == null && event.icon == null)
            if (!duplicate) {
                pendingStart = event.distance
                lastShow = event.copy(icon = event.icon?.copyOf()); lastShowAt = now
            }
        }
        // Collect rapid distance/road/turn updates into one coherent candidate.
        if (pending == null) pendingAt = now
        pending = event.copy(action = if (pending?.action == 1) 1 else event.action, icon = event.icon?.copyOf())
    }

    private fun flush(now: Long) {
        val next = pending ?: return
        if (now - pendingAt < 250) return
        pending = null
        val start = pendingStart
        pendingStart = null
        active = true; endAt = null; phase = "guide"
        val old = segment
        val distanceJump = old != null && next.distance - old.distance > maxOf(100, initial / 5)
        val newSegment = start != null || old == null || next.turn != old.turn ||
            (old.distance <= 5 && next.distance > 20) || distanceJump
        if (newSegment) initial = start ?: next.distance
        initial = maxOf(initial, next.distance)
        segment = next; current = next
        if (next.icon != null) setImage(next.icon.takeIf { it.isNotEmpty() }) else if (newSegment) setImage(null)
    }

    private fun expire(now: Long) {
        if (endAt?.let { now >= it } == true) reset()
    }
    @Synchronized fun display(now: Long): NavigationDisplay {
        // A fresh guide arriving during the end hold takes precedence over its old expiry.
        if (pending != null) flush(now)
        expire(now)
        val turn = current
        val guide = phase == "guide" && turn != null
        val elapsed = if (guide && initial > 0) (((initial - turn!!.distance).toLong() * 100_000) / initial).coerceIn(0, 100_000).toInt() else 0
        return NavigationDisplay(
            title = if (guide) "${formatDistance(turn!!.distance)} ${turnName(turn.turn)}" else when (phase) {
                "prepare" -> "导航目的地"
                "end" -> "导航结束"
                else -> "暂无导航"
            },
            album = if (guide) turn!!.road.ifBlank { "—" } else if (phase == "idle") "等待手机导航" else "—",
            artist = if (destination.isBlank()) "" else "🧭 $destination",
            duration = if (guide && initial > 0) 100_000 else 0,
            elapsed = elapsed, icon = image, iconVersion = imageVersion, phase = phase,
        )
    }
    companion object {
        fun formatDistance(meters: Int): String = if (meters < 1000) "${meters}米" else String.format(Locale.ROOT, "%.1f公里", meters / 1000.0)
        fun turnName(code: Int): String = when (code) {
            1 -> "直行"; 2 -> "右前方转弯"; 3 -> "右转"; 4 -> "右后方转弯"; 5 -> "掉头"
            6 -> "左后方转弯"; 7 -> "左转"; 8 -> "左前方转弯"; 9 -> "进入环岛"; 10 -> "出环岛"
            11 -> "靠左行驶"; 12 -> "靠右行驶"; 13 -> "靠左进入主路"; 14 -> "左分叉直行"
            15 -> "靠右进入主路"; 16 -> "右分叉直行"; 17 -> "分叉路走中间"; 18 -> "靠左驶入辅路"; 19 -> "靠右驶入辅路"
            20 -> "走左侧分叉"; 21 -> "走右侧分叉"; 22 -> "走中间分叉"; 23 -> "出发点"
            24 -> "到达目的地"; 25 -> "进入隧道"; 29 -> "进入轮渡"; 30 -> "离开轮渡"; 31 -> "经过收费站"
            else -> "导航指引"
        }
    }
}

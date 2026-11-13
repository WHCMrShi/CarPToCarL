package io.github.shaonialife.carptocar.media

import java.util.Locale

/** Per-HU input, independent of the selected instrument display mode. No Android or transport IO. */
data class HudTurn(val action: Int, val turn: Int, val road: String, val distance: Int, val icon: ByteArray?)
internal data class NavigationDisplay(
    val title: String, val artist: String,
    val icon: ByteArray?, val iconVersion: Long, val phase: String,
    val navigationActive: Boolean, val endedDurationMs: Long?,
) {
    fun nowPlaying(artwork: ArtworkSource? = null, music: io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying? = null) = io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying(
        title, artist, music?.title?.takeIf { it.isNotBlank() } ?: "暂无音乐",
        music?.durationMs?.coerceAtLeast(0) ?: 0,
        music?.status ?: io.github.shaonialife.carptocar.iap2.Iap2NowPlaying.STATUS_PAUSED,
        music?.elapsedMs?.coerceIn(0, music.durationMs.coerceAtLeast(0)) ?: 0,
        shuffleMode = music?.shuffleMode ?: 0, repeatMode = music?.repeatMode ?: 0,
        appName = "导航", artworkSource = artwork,
    )
}

internal class NavigationState {
    private var owner: Any? = null
    private var active = false
    private var startedAt: Long? = null
    private var endedDurationMs: Long? = null
    private var phase = "idle"
    private var endAt: Long? = null
    private var pending: HudTurn? = null
    private var pendingAt = 0L
    // These segment markers only retain the existing missing-icon policy, never media progress.
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
        active = false; startedAt = null; endedDurationMs = null; phase = "idle"; endAt = null
        pending = null; current = null; setImage(null)
        pendingStart = null; lastShow = null; segment = null; initial = 0
    }
    private fun activate(now: Long) {
        if (active) return
        reset(); active = true; startedAt = now
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
                // A status hint only: no destination/prepare layout or icon replacement.
                activate(now)
            } else if (event.distance == 0 && (phase != "end" || active || pending != null)) {
                val duration = startedAt?.let { (now - it).coerceAtLeast(0) }
                reset(); endedDurationMs = duration; phase = "end"; endAt = now + 10_000
            }
            return
        }
        if (event.action !in 1..2) return
        activate(now)
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
        return NavigationDisplay(
            title = if (guide) "${formatDistance(turn!!.distance)} ${turnName(turn.turn)}" else when (phase) {
                "end" -> "导航结束"
                else -> "等待导航"
            },
            artist = if (guide) turn!!.road.ifBlank { BLANK } else if (phase == "end") "驾驶时长：${endedDurationMs?.let(::formatDrivingDuration) ?: "--:--"}" else BLANK,
            icon = image, iconVersion = imageVersion, phase = phase,
            navigationActive = active, endedDurationMs = endedDurationMs,
        )
    }
    companion object {
        const val BLANK = "   " // Three ASCII spaces: Q4 trim/placeholder behavior is an experiment.
        fun formatDrivingDuration(milliseconds: Long): String {
            val seconds = milliseconds.coerceAtLeast(0) / 1_000
            return if (seconds < 3_600) String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
            else String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        }
        fun formatDistance(meters: Int): String = if (meters < 1000) "${meters}米"
        else String.format(Locale.ROOT, "%.1f", meters / 1000.0).removeSuffix(".0") + "千米"
        /*
         * Previous mapping (9768a2a), retained for comparison.
         * Unmapped codes use the original road name, including 25..28 and Base 45..68.
        fun turnName(code: Int): String? = when (code) {
            1 -> "直行"; 2 -> "右前方转弯"; 3 -> "右转"; 4 -> "右后方转弯"; 5 -> "掉头"
            6 -> "左后方转弯"; 7 -> "左转"; 8 -> "左前方转弯"; 9 -> "进入环岛"; 10 -> "出环岛"
            11 -> "靠左行驶"; 12 -> "靠右行驶"; 13 -> "靠左进入主路"; 14 -> "左分叉直行"
            15 -> "靠右进入主路"; 16 -> "右分叉直行"; 17 -> "分叉路走中间"; 18 -> "靠左驶入辅路"; 19 -> "靠右驶入辅路"
            20 -> "走左侧分叉"; 21 -> "走右侧分叉"; 22 -> "走中间分叉"; 23 -> "出发点"
            24 -> "到达目的地"; 29 -> "进入轮渡"; 30 -> "离开轮渡"; 31 -> "经过收费站"
            else -> null
        }
         */
        fun turnName(code: Int): String = when (code) {
            1 -> "直行"; 2 -> "右前方转弯"; 3 -> "右转"; 4 -> "右后方转弯"; 5 -> "掉头"
            6 -> "左后方转弯"; 7 -> "左转"; 8 -> "左前方转弯"; 9 -> "进入环岛"; 10 -> "出环岛"
            11 -> "靠左行驶"; 12 -> "靠右行驶"; 13 -> "靠左进入主路"; 14 -> "左分叉直行"
            15 -> "靠右进入主路"; 16 -> "右分叉直行"; 17 -> "分叉路走中间"; 18 -> "靠左驶入辅路"; 19 -> "靠右驶入辅路"
            20 -> "走左侧分叉"; 21 -> "走右侧分叉"; 22 -> "走中间分叉"; 23 -> "出发点"
            24 -> "到达目的地"; 29 -> "进入轮渡"; 30 -> "离开轮渡"; 31 -> "经过收费站"
            // Car+ action names: IC is interpreted as 辅路; unmapped codes use the neutral 途径.
            32 -> "靠左直行进入辅路"; 33 -> "靠右直行进入辅路"
            34 -> "靠左直行"; 35 -> "靠右直行"
            36 -> "左分叉直行"; 37 -> "中间分叉直行"; 38 -> "右分叉直行"
            39 -> "走左侧分叉进入辅路"; 40 -> "走中间分叉进入辅路"; 41 -> "走右侧分叉进入辅路"
            42 -> "左分叉直行进入辅路"; 43 -> "中间分叉直行进入辅路"; 44 -> "右分叉直行进入辅路"
            69 -> "前方驶出环岛"; 70 -> "左侧驶出环岛"; 71 -> "左后方驶出环岛"; 72 -> "左前方驶出环岛"
            73 -> "右侧驶出环岛"; 74 -> "右后方驶出环岛"; 75 -> "右前方驶出环岛"; 76 -> "环岛内掉头"
            else -> "途径"
        }
    }
}

package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying

/** 同一 MediaSession 快照的协议映射，不拼接无法确认属于同曲的备用数据源。 */
internal fun MediaSessionsState.NowPlaying.toCarPlayNowPlaying(nowMs: Long): CarPlayNowPlaying =
    CarPlayNowPlaying(
        title = title,
        artist = artist,
        album = album?.takeIf { it.isNotBlank() },
        durationMs = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        status = if (playing) Iap2NowPlaying.STATUS_PLAYING else Iap2NowPlaying.STATUS_PAUSED,
        elapsedMs = displayPositionAt(nowMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        appName = pkg,
    )

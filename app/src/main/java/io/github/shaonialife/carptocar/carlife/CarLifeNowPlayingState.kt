package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying

/** One HU generation. Positions are authoritative CarLife milliseconds, never guessed from metadata. */
internal class CarLifeNowPlayingState {
    private var media: CarLifeMediaInfo? = null
    private var track: List<String>? = null
    private var position: Int? = null
    private var active: Boolean? = null

    @Synchronized fun media(value: CarLifeMediaInfo) {
        val identity = listOf(value.source, value.songId, value.song, value.artist, value.album)
        if (track != identity) { track = identity; position = null }
        media = value
    }

    @Synchronized fun progress(value: Int) {
        if (media == null || value < 0) return
        // Forward progress is newer evidence than a previous STOP; a seek alone is not START.
        if (position?.let { value > it } == true) active = true
        position = value
    }

    @Synchronized fun gate(playing: Boolean) { active = playing }

    @Synchronized fun reset() { media = null; track = null; position = null; active = null }

    @Synchronized fun snapshot(): Pair<CarLifeMediaInfo, CarPlayNowPlaying>? {
        val info = media ?: return null
        // This engine reports total duration in seconds (253/231/287), progress in ms.
        // Preserve raw wire duration in CarLifeMediaInfo; normalize only at the iAP2 boundary.
        val duration = (info.duration.toLong().coerceAtLeast(0) * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val elapsed = position?.coerceAtLeast(0)?.let { if (duration > 0) it.coerceAtMost(duration) else it } ?: 0
        return info to CarPlayNowPlaying(
            title = info.song.takeIf { it.isNotBlank() }, artist = info.artist.takeIf { it.isNotBlank() },
            album = info.album.takeIf { it.isNotBlank() }, durationMs = duration,
            status = if (active == false) Iap2NowPlaying.STATUS_PAUSED else Iap2NowPlaying.STATUS_PLAYING,
            elapsedMs = elapsed, appName = info.source.takeIf { it.isNotBlank() },
        )
    }
}

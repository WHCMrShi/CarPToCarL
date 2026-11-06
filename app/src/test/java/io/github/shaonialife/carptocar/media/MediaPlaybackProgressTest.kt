package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class MediaPlaybackProgressTest {
    private val song = MediaSessionsState.NowPlaying(
        "song", "artist", true, positionMs = 10_000, playbackSpeed = 1f,
        durationMs = 180_000, anchoredAtMs = 1_000,
    )

    @Test fun stalePlayerSampleKeepsItsAnchorAcrossPollingAndSupportsSpeed() {
        for (second in 1..60) {
            assertEquals(10_000L + second * 1000, song.displayPositionAt(1_000L + second * 1000))
        }
        assertEquals(30_000L, song.copy(playbackSpeed = 2f).displayPositionAt(11_000))
        assertEquals(10_000L, song.copy(playing = false).displayPositionAt(61_000))
    }

    @Test fun unknownAndFutureAnchorsDoNotInventProgressAndBoundsAreSafe() {
        assertEquals(10_000L, song.copy(anchoredAtMs = 0).displayPositionAt(61_000))
        assertEquals(10_000L, song.displayPositionAt(500))
        assertEquals(0L, song.copy(positionMs = -1).displayPositionAt(61_000))
        assertEquals(10_000L, song.copy(playbackSpeed = Float.NaN).displayPositionAt(61_000))
        assertEquals(180_000L, song.displayPositionAt(900_000))
        assertEquals(0L, song.copy(playbackSpeed = -2f).displayPositionAt(61_000))
    }

    @Test fun positionSamplesReachBridgeWithoutRepeatedUiContentNotifications() {
        val previous = MediaSessionsState.nowPlaying
        var uiCalls = 0
        var bridgeCalls = 0
        val ui: () -> Unit = { uiCalls++ }
        val bridge: () -> Unit = { bridgeCalls++ }
        MediaSessionsState.publish(song)
        MediaSessionsState.addListener(ui)
        MediaSessionsState.addPlaybackListener(bridge)
        try {
            MediaSessionsState.publish(song)
            MediaSessionsState.publish(song.copy(positionMs = 30_000))
            assertEquals(0, uiCalls)
            assertEquals(2, bridgeCalls)
            MediaSessionsState.publish(song.copy(playing = false, positionMs = 30_000))
            assertEquals(1, uiCalls)
            assertEquals(3, bridgeCalls)
        } finally {
            MediaSessionsState.removeListener(ui)
            MediaSessionsState.removePlaybackListener(bridge)
            MediaSessionsState.publish(previous)
        }
    }
}

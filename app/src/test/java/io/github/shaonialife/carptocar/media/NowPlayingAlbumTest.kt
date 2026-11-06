package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.NowPlayingUpdates
import org.junit.Assert.*
import org.junit.Test

class NowPlayingAlbumTest {
    private val song = MediaSessionsState.NowPlaying("song", "artist", true,
        pkg = "player", positionMs = 1000, durationMs = 180_000, anchoredAtMs = 1000)

    private fun encodedAlbum(value: MediaSessionsState.NowPlaying): String {
        val frame = Iap2Csm.Parser().offer(Iap2NowPlaying.encode(value.toCarPlayNowPlaying(2000))).single()
        val media = Iap2Csm.parseParams(frame.body).single { it.id == 0 }
        return Iap2Csm.parseParams(media.value).single { it.id == 6 }.value.decodeToString().trimEnd('\u0000')
    }

    @Test fun albumUsesExistingWireFieldWithoutChangingPlayback() {
        val value = song.copy(album = "测试专辑")
        assertEquals("测试专辑", encodedAlbum(value))
        val body = Iap2Csm.parseParams(Iap2NowPlaying.body(value.toCarPlayNowPlaying(2000))).single { it.id == 0 }
        assertFalse("文字不能写到 artwork transfer ID 字段", Iap2Csm.parseParams(body.value).any { it.id == 0x1A })
        val mapped = value.toCarPlayNowPlaying(2000)
        assertEquals(2000, mapped.elapsedMs)
        assertEquals(180_000, mapped.durationMs)
        assertEquals(Iap2NowPlaying.STATUS_PLAYING, mapped.status)
    }

    @Test fun lateAlbumAndSameTitleDifferentAlbumAreDeliveredWhilePaused() {
        val updates = NowPlayingUpdates()
        updates.subscribe()
        val paused = song.copy(playing = false)
        for (album in listOf(null, "first", "second")) {
            val mapped = paused.copy(album = album).toCarPlayNowPlaying(2000)
            assertTrue(updates.push(mapped) { true })
            assertFalse(updates.push(mapped) { true })
        }
    }

    @Test fun missingOrBlankAlbumClearsPreviousWireValue() {
        assertEquals("first", encodedAlbum(song.copy(album = "first")))
        assertEquals("", encodedAlbum(song.copy(album = null)))
        assertEquals("", encodedAlbum(song.copy(album = "  ")))
    }

    @Test fun albumOnlyUpdateNotifiesContentListenersOnce() {
        val old = MediaSessionsState.nowPlaying
        MediaSessionsState.publish(song)
        var count = 0
        val listener: () -> Unit = { count++ }
        MediaSessionsState.addListener(listener)
        try {
            MediaSessionsState.publish(song.copy(album = "late"))
            MediaSessionsState.publish(song.copy(album = "late", positionMs = 2000))
            assertEquals(1, count)
        } finally {
            MediaSessionsState.removeListener(listener)
            MediaSessionsState.publish(old)
        }
    }
}

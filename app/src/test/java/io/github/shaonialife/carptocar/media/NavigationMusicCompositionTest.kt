package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.NowPlayingUpdates
import org.junit.Assert.*
import org.junit.Test

class NavigationMusicCompositionTest {
    private val owner = Any()
    private val music = CarPlayNowPlaying("歌曲A", "歌手A", "专辑A", 200000, Iap2NowPlaying.STATUS_PLAYING, 60000, appName = "music")
    private fun guide(): NavigationDisplay {
        val state = NavigationState().apply { begin(owner) }
        state.receive(owner, HudTurn(1, 3, "道路A", 500, byteArrayOf(1)), 0)
        return state.display(300)
    }
    @Test fun playPauseSeekAndTrackSwitchUseTheWholeCurrentMusicSnapshot() {
        val view = guide()
        for (song in listOf(music, music.copy(status = Iap2NowPlaying.STATUS_PAUSED),
            music.copy(elapsedMs = 10000), music.copy(title = "歌曲B", durationMs = 300000, elapsedMs = 0))) {
            val mixed = view.nowPlaying(music = song)
            assertEquals("500米 右转", mixed.title)
            assertEquals("道路A", mixed.artist)
            assertEquals(song.title, mixed.album)
            assertEquals(song.durationMs, mixed.durationMs)
            assertEquals(song.elapsedMs, mixed.elapsedMs)
            assertEquals(song.status, mixed.status)
        }
    }
    @Test fun advancingMusicUsesExistingPlaybackAnchorRatherThanNavigationDistance() {
        val snapshot = MediaSessionsState.NowPlaying("歌曲", "歌手", true, positionMs = 10000,
            durationMs = 200000, anchoredAtMs = 1000)
        val view = guide()
        assertEquals(12000, view.nowPlaying(music = snapshot.toCarPlayNowPlaying(3000)).elapsedMs)
        assertEquals(14000, view.nowPlaying(music = snapshot.toCarPlayNowPlaying(5000)).elapsedMs)
        assertEquals(10000, view.nowPlaying(music = snapshot.copy(playing = false).toCarPlayNowPlaying(5000)).elapsedMs)
    }
    @Test fun noMusicClearsOldMusicFieldsAndNeverInventsDistanceTime() {
        val mixed = guide().nowPlaying()
        assertEquals("暂无音乐", mixed.album)
        assertEquals(0, mixed.durationMs)
        assertEquals(0, mixed.elapsedMs)
        assertEquals(Iap2NowPlaying.STATUS_PAUSED, mixed.status)
        assertEquals(0, guide().nowPlaying(music = music.copy(durationMs = -1, elapsedMs = -3)).elapsedMs)
        assertEquals(200000, guide().nowPlaying(music = music.copy(elapsedMs = 300000)).elapsedMs)
    }
    @Test fun musicCoverIsNeverLoadedOrUsedAndNavigationArtworkIdentityRemainsStable() {
        ArtworkRepository().use { repo ->
            var loads = 0
            val songCover = repo.source("song", 1) { loads++; byteArrayOf(1) }
            ArtworkRepository().use { navRepo ->
                val navCover = navRepo.source("nav", 1) { byteArrayOf(2) }
                val view = guide()
                repeat(20) {
                    val mixed = view.nowPlaying(navCover, music.copy(artworkSource = songCover, artworkTransferId = 200, elapsedMs = it))
                    assertSame(navCover, mixed.artworkSource)
                    assertNull(mixed.artworkTransferId)
                }
                assertEquals(0, loads)
            }
        }
    }
    @Test fun wireFieldsAndDedupCarryNavigationAndMusicTogether() {
        val view = guide()
        val mixed = view.nowPlaying(music = music)
        val groups = Iap2Csm.parseParams(Iap2NowPlaying.body(mixed))
        val media = Iap2Csm.parseParams(groups.single { it.id == 0 }.value)
        fun text(id: Int) = media.single { it.id == id }.value.decodeToString().trimEnd('\u0000')
        assertEquals("500米 右转", text(1))
        assertEquals("道路A", text(12))
        assertEquals("歌曲A", text(6))
        fun u32(bytes: ByteArray) = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        assertEquals(200000L, u32(media.single { it.id == 4 }.value))
        val playback = Iap2Csm.parseParams(groups.single { it.id == 1 }.value)
        assertEquals(Iap2NowPlaying.STATUS_PLAYING, playback.single { it.id == 0 }.value.single().toInt())
        assertEquals(60000L, u32(playback.single { it.id == 1 }.value))
        val updates = NowPlayingUpdates().apply { subscribe() }
        assertTrue(updates.push(mixed) { true })
        assertFalse(updates.push(mixed) { true })
        assertTrue(updates.push(view.nowPlaying(music = music.copy(status = Iap2NowPlaying.STATUS_PAUSED))) { true })
        assertTrue(updates.push(view.nowPlaying(music = music.copy(title = "歌曲B", elapsedMs = 0))) { true })
    }
}

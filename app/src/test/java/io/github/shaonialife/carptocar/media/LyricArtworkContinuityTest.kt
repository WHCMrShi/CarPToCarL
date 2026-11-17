package io.github.shaonialife.carptocar.media

import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2ArtworkSender
import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.NowPlayingUpdates
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LyricArtworkContinuityTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
    private fun track() = MediaArtworkIdentity.of("session", "qqmusic", null, "artist", "album", 200000).toString()
    private fun song(title: String, source: ArtworkSource) =
        MediaSessionsState.NowPlaying(title, "artist", true, artworkSource = source, album = "album",
            pkg = "qqmusic", durationMs = 200000).toCarPlayNowPlaying(0)
    private fun loaded(source: ArtworkSource) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!source.request().complete && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(source.request().complete)
    }
    private fun complete(sender: Iap2ArtworkSender, value: CarPlayNowPlaying): Int {
        val id = sender.decorate(value, 26, true).artworkTransferId!!
        sender.metadataSent(id)
        val setup = sender.poll(1024)!!
        sender.submitted(setup, true)
        sender.response(byteArrayOf(id.toByte(), 1))
        val data = sender.poll(1024)!!
        sender.submitted(data, true)
        sender.response(byteArrayOf(id.toByte(), 5))
        assertFalse(sender.pending())
        return id
    }
    private fun text(value: CarPlayNowPlaying, field: Int): String {
        val media = Iap2Csm.parseParams(Iap2NowPlaying.body(value)).single { it.id == 0 }
        return Iap2Csm.parseParams(media.value).single { it.id == field }.value.decodeToString().trimEnd('\u0000')
    }

    @Test fun normalMusicKeepsReferenceDuringLyricReloadAndDoesNotRetransferIdenticalImage() {
        ArtworkRepository().use { repo ->
            val first = repo.source(track(), 1) { jpeg }
            loaded(first)
            val sender = Iap2ArtworkSender()
            val id = complete(sender, song("歌名", first))
            val updates = NowPlayingUpdates().apply { subscribe() }
            for (revision in 2L..5L) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    val source = repo.source(track(), revision) {
                        entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); jpeg
                    }
                    val line = "歌词第 $revision 行"
                    val pending = sender.decorate(song(line, source), 26, true)
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    assertEquals(id, pending.artworkTransferId)
                    assertEquals(line, text(pending, 1))
                    assertTrue(updates.push(pending) { true })
                    assertNull(sender.poll(1024))
                    release.countDown()
                    loaded(source)
                    assertEquals(id, sender.decorate(song(line, source), 26, true).artworkTransferId)
                    assertFalse(sender.pending())
                    assertNull(sender.poll(1024))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun changedOrRemovedCoverStillPublishesWithoutStableMediaId() {
        ArtworkRepository().use { repo ->
            val first = repo.source(track(), 1) { jpeg }
            loaded(first)
            val sender = Iap2ArtworkSender()
            val oldId = complete(sender, song("歌名", first))
            // 同歌手/专辑/时长的新曲无法从标题判断；真实图片仍通过 revision 更新。
            val replacement = repo.source(track(), 2) { jpeg + byteArrayOf(2) }
            loaded(replacement)
            val newId = complete(sender, song("另一首", replacement))
            assertNotEquals(oldId, newId)
            val absent = repo.source(track(), 3) { null }
            loaded(absent)
            assertEquals(128, sender.decorate(song("无封面", absent), 26, true).artworkTransferId)
        }
    }

    @Test fun navigationShowsLyricInAlbumWithoutLoadingMusicCoverAndCanReturnToNormal() {
        ArtworkRepository().use { musicRepo ->
            ArtworkRepository().use { navRepo ->
                var musicLoads = 0
                val navCover = navRepo.source("nav", 1) { jpeg }
                loaded(navCover)
                val view = NavigationDisplay("500m 右转", "道路", null, 1, "guide", true, null)
                val sender = Iap2ArtworkSender()
                val navId = complete(sender, view.nowPlaying(navCover))
                var lastMusic: CarPlayNowPlaying? = null
                for (revision in 1L..5L) {
                    val source = musicRepo.source(track(), revision) { musicLoads++; jpeg + byteArrayOf(3) }
                    val music = song("歌词第 $revision 行", source)
                    lastMusic = music
                    val mixed = sender.decorate(view.nowPlaying(navCover, music), 26, true)
                    assertEquals("500m 右转", text(mixed, 1))
                    assertEquals(music.title, text(mixed, 6))
                    assertEquals(navId, mixed.artworkTransferId)
                    assertNull(sender.poll(1024))
                }
                assertEquals(0, musicLoads)
                // 返回普通音乐，加载的是音乐封面，不携带导航图引用。
                val latest = requireNotNull(lastMusic)
                val source = latest.artworkSource!!
                loaded(source)
                val normal = sender.decorate(latest, 26, true)
                assertEquals(latest.title, text(normal, 1))
                assertNotEquals(navId, normal.artworkTransferId)
                assertEquals(1, musicLoads)
            }
        }
    }
}

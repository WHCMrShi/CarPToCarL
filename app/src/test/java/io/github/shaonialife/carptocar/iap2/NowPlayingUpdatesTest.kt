package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class NowPlayingUpdatesTest {
    private val song = CarPlayNowPlaying("song", "artist", "album", 180_000, 1, 0, appName = "player")

    @Test fun sameSongProgressAdvancesForOneMinuteInEncodedPayload() {
        val updates = NowPlayingUpdates()
        updates.subscribe()
        val positions = ArrayList<Int>()
        for (second in 0..60) {
            val value = song.copy(elapsedMs = second * 1000)
            val send: (CarPlayNowPlaying) -> Boolean = {
                val frame = Iap2Csm.Parser().offer(Iap2NowPlaying.encode(it)).single()
                assertEquals(Iap2MessageIds.NOW_PLAYING_UPDATE, frame.messageId)
                val playback = Iap2Csm.parseParams(frame.body).single { it.id == 1 }
                val elapsed = Iap2Csm.parseParams(playback.value).single { it.id == 1 }.value
                positions.add(java.nio.ByteBuffer.wrap(elapsed).int)
                true
            }
            assertTrue(updates.push(value, send))
            assertFalse(updates.push(value, send))
        }
        assertEquals((0..60).map { it * 1000 }, positions)
    }

    @Test fun sameTitlePauseResumeSeekAndMetadataChangesAreDelivered() {
        val updates = NowPlayingUpdates()
        updates.subscribe()
        val values = listOf(
            song,
            song.copy(elapsedMs = 30_000),
            song.copy(elapsedMs = 30_000, status = Iap2NowPlaying.STATUS_PAUSED),
            song.copy(elapsedMs = 10_000, status = Iap2NowPlaying.STATUS_PAUSED),
            song.copy(elapsedMs = 10_000),
            song.copy(elapsedMs = 60_000),
            song.copy(album = "other album"),
            song.copy(durationMs = 240_000),
        )
        val delivered = ArrayList<CarPlayNowPlaying>()
        for (value in values) {
            assertTrue(updates.push(value) { delivered.add(it); true })
            assertFalse(updates.push(value) { fail("重复状态不得重发"); true })
        }
        assertEquals(values, delivered)
    }

    @Test fun subscriptionsArePerLinkAndResubscriptionReplaysUnchangedPausedSong() {
        val first = NowPlayingUpdates()
        val second = NowPlayingUpdates()
        val paused = song.copy(status = Iap2NowPlaying.STATUS_PAUSED)
        assertFalse(first.push(paused) { fail("未订阅不得发送"); true })
        first.subscribe()
        assertTrue(first.push(paused) { true })
        second.subscribe()
        assertTrue(second.push(paused) { true })
        first.unsubscribe()
        assertFalse(first.push(paused) { fail("退订不得发送"); true })
        first.subscribe()
        assertTrue(first.push(paused) { true })
    }

    @Test fun failedSendIsNotCachedAndConcurrentDuplicateCallbacksSendOnce() {
        val updates = NowPlayingUpdates()
        updates.subscribe()
        assertFalse(updates.push(song) { false })
        val sent = AtomicInteger()
        val gate = CountDownLatch(1)
        val threads = List(8) {
            Thread { gate.await(); updates.push(song) { sent.incrementAndGet(); true } }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join(3000); assertFalse(it.isAlive) }
        assertEquals(1, sent.get())
    }
}

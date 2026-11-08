package io.github.shaonialife.carptocar.carlife

import io.github.shaonialife.carptocar.iap2.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CarLifeNowPlayingStateTest {
    private val song = CarLifeMediaInfo("player", "song", "artist", "album", null, 231, 1, "id", 0)

    @Test fun noMediaSessionStillSendsAFullMinuteOfRealProgress() {
        val state = CarLifeNowPlayingState()
        val updates = NowPlayingUpdates()
        updates.subscribe()
        state.media(song)
        state.gate(true)
        val elapsed = mutableListOf<Int>()
        for (second in 0..60) {
            val protobuf = byteArrayOf(8) + varint(second * 1000)
            state.progress(CarLifeMediaProtocol.progress(protobuf)!!)
            val value = state.snapshot()!!.second
            assertTrue(updates.push(value) {
                val frame = Iap2Csm.Parser().offer(Iap2NowPlaying.encode(it)).single()
                val groups = Iap2Csm.parseParams(frame.body)
                val media = Iap2Csm.parseParams(groups.single { it.id == 0 }.value)
                val playback = Iap2Csm.parseParams(groups.single { it.id == 1 }.value)
                assertEquals(231_000, ByteBuffer.wrap(media.single { it.id == 4 }.value).int)
                elapsed += ByteBuffer.wrap(playback.single { it.id == 1 }.value).int
                true
            })
            assertFalse(updates.push(value) { fail("identical samples must stay deduplicated"); true })
        }
        assertEquals((0..60).map { it * 1000 }, elapsed)
    }

    @Test fun pauseSeekAndResumeDoNotInventProgress() {
        val state = CarLifeNowPlayingState()
        state.media(song); state.progress(20_000); state.gate(false)
        repeat(30) { assertEquals(20_000, state.snapshot()!!.second.elapsedMs) }
        state.progress(20_000)
        assertEquals(Iap2NowPlaying.STATUS_PAUSED, state.snapshot()!!.second.status)
        state.progress(5_000)
        assertEquals(5_000, state.snapshot()!!.second.elapsedMs)
        assertEquals(Iap2NowPlaying.STATUS_PAUSED, state.snapshot()!!.second.status)
        state.gate(true); state.progress(6_000)
        assertEquals(Iap2NowPlaying.STATUS_PLAYING, state.snapshot()!!.second.status)
    }

    @Test fun repeatedMetadataPreservesPositionButNewTrackAndHuResetIt() {
        val state = CarLifeNowPlayingState()
        state.media(song); state.progress(60_000)
        state.media(song.copy(albumArt = byteArrayOf(1)))
        assertEquals(60_000, state.snapshot()!!.second.elapsedMs)
        state.media(song.copy(songId = "next"))
        assertEquals(0, state.snapshot()!!.second.elapsedMs)
        state.reset(); state.progress(99_000)
        assertNull(state.snapshot())
        state.media(song)
        assertEquals(0, state.snapshot()!!.second.elapsedMs)
    }

    @Test fun durationConversionAndPositionBoundsDoNotOverflow() {
        val state = CarLifeNowPlayingState()
        state.media(song); state.progress(Int.MAX_VALUE)
        assertEquals(231_000, state.snapshot()!!.second.elapsedMs)
        state.media(song.copy(duration = Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, state.snapshot()!!.second.durationMs)
        state.media(song.copy(duration = -1))
        state.progress(1_000); state.progress(-1)
        assertEquals(0, state.snapshot()!!.second.durationMs)
        assertEquals(1_000, state.snapshot()!!.second.elapsedMs)
    }

    private fun varint(value: Int): ByteArray {
        var rest = value
        val bytes = mutableListOf<Byte>()
        do { val part = rest and 127; rest = rest ushr 7; bytes += (part or if (rest > 0) 128 else 0).toByte() } while (rest > 0)
        return bytes.toByteArray()
    }
}

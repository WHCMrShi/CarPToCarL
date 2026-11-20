package io.github.shaonialife.carptocar.carlife

import android.view.KeyEvent
import io.github.shaonialife.carptocar.audio.AudioIngressQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarLifeMergedAudioTest {
    @Test fun mergedSamplesRemainIntactBeforeImmediatelyAfterAndLongAfterMusicPause() {
        val received = mutableListOf<ByteArray>()
        var now = 1L
        val sink = CarLifeAudioSink(log = {}, clock = { now })
        val forward: (ByteArray, Int, Int, Int, Boolean) -> Boolean = { pcm, size, rate, channels, speech ->
            assertEquals(48000, rate); assertEquals(2, channels); assertFalse(speech)
            received.add(pcm.copyOf(size)); true
        }
        val merged = List(3) { block -> ByteArray(32) { (it * 7 + block).toByte() } }
        sink.configure(48000, 2)
        sink.write(48000, 2, merged[0], merged[0].size, forward)
        CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE, true, { true }, { true }, { true })
        sink.write(48000, 2, merged[1], merged[1].size, forward)
        now += 16000
        sink.write(48000, 2, merged[2], merged[2].size, forward)
        assertEquals(3, received.size)
        received.forEachIndexed { index, samples -> assertArrayEquals(merged[index], samples) }
    }

    @Test fun queuedMixedSamplesSurvivePauseAlongWithLaterSamples() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val received = CopyOnWriteArrayList<ByteArray>()
        val merged = List(3) { block -> ByteArray(32) { (it * 7 + block).toByte() } }
        AudioIngressQueue(consume = { bytes, _, _, _ ->
            if (received.isEmpty()) { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            received.add(bytes)
            completed.countDown()
        }, clock = { 0L }).use { queue ->
            val sink = CarLifeAudioSink(log = {}, clock = { 1L })
            val forward = queue::offer
            try {
                sink.write(48000, 2, merged[0], merged[0].size, forward)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                sink.write(48000, 2, merged[1], merged[1].size, forward)
                CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE, true, { true }, { true }, { true })
                sink.write(48000, 2, merged[2], merged[2].size, forward)
                release.countDown()
                assertTrue(completed.await(3, TimeUnit.SECONDS))
                received.forEachIndexed { index, samples -> assertArrayEquals(merged[index], samples) }
            } finally { release.countDown() }
        }
    }

    @Test fun explicitTtsPortRetainsItsFormatAndQueueOriginWithoutInferringResourceOwnership() {
        var forwarded = false
        val data = byteArrayOf(1, 2, 3, 4)
        val sink = CarLifeAudioSink(log = {}, speech = true, clock = { 0L })
        sink.write(16000, 1, data, data.size) { pcm, size, rate, channels, speech ->
            assertArrayEquals(data, pcm); assertEquals(4, size)
            assertEquals(16000, rate); assertEquals(1, channels); assertTrue(speech)
            forwarded = true; true
        }
        assertTrue(forwarded)
    }
}

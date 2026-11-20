package io.github.shaonialife.carptocar.carlife

import android.view.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CarLifeMediaCommandsTest {
    @Test fun repeatedPauseUsesExplicitCommandsAndNeverTogglesPlayback() {
        val session = mutableListOf<Int>(); val hardKeys = mutableListOf<Int>(); var pauses = 0
        repeat(2) {
            val result = CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE, true,
                { session.add(it); true }, { hardKeys.add(it); true }, { pauses++; true })!!
            assertFalse(result.play)
        }
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE), session)
        assertEquals(listOf(0x20, 0x20), hardKeys)
        assertEquals(2, pauses)
    }

    @Test fun toggleExpandsToOneExplicitIntentAcrossBothOutputs() {
        for (playing in listOf(true, false)) {
            var session = 0; var hardKey = 0; var pauses = 0
            val result = CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing,
                { session = it; true }, { hardKey = it; true }, { pauses++; true })!!
            assertEquals(!playing, result.play)
            assertEquals(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY, session)
            assertEquals(if (playing) 0x20 else 0x1F, hardKey)
            assertEquals(if (playing) 1 else 0, pauses)
        }
    }

    @Test fun systemFailureStillDeliversCarLifeCommands() {
        val hardKeys = mutableListOf<Int>()
        val result = CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_STOP, true,
            { throw IllegalStateException("no media permission") }, { hardKeys.add(it); true }, { true })!!
        assertFalse(result.sessionSent); assertTrue(result.hardKeyQueued); assertTrue(result.pauseSent)
        assertTrue(result.submitted)
        assertEquals(listOf(0x20), hardKeys)
    }

    @Test fun nextTrackRemainsOnTheExistingRoutingPath() {
        assertFalse(CarLifeMediaCommands.accepts(KeyEvent.KEYCODE_MEDIA_NEXT))
        val result = CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_NEXT, true,
            { error("must not intercept") }, { error("must not intercept") }, { error("must not intercept") })
        assertNull(result)
    }

    @Test fun failedDeliveryDoesNotPretendPlaybackWasConfirmed() {
        val result = CarLifeMediaCommands.dispatch(KeyEvent.KEYCODE_MEDIA_PLAY, false,
            { false }, { false }, { error("play must not send pause") })!!
        assertTrue(result.play)
        assertFalse(result.sessionSent); assertFalse(result.hardKeyQueued); assertFalse(result.pauseSent)
        assertFalse("all failed must preserve the original system-key fallback", result.submitted)
    }

    @Test(timeout = 10000) fun blockedSocketWorkDoesNotBlockCallerOrRunRetiredCommands() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val ownerCurrent = AtomicBoolean(true)
        val events = CopyOnWriteArrayList<String>()
        val caller = Thread.currentThread()
        assertTrue(CarLifeMediaCommands.enqueue {
            events.add(if (Thread.currentThread() === caller) "caller" else "worker")
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            events.add("sent")
        })
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(CarLifeMediaCommands.enqueue(ownerCurrent::get, {
                events.add("retired")
                retired.countDown()
            }) { events.add("wrong-session") })
            assertTrue(CarLifeMediaCommands.enqueue { events.add("next"); finished.countDown() })
            assertEquals(listOf("worker"), events.toList())
            ownerCurrent.set(false)
            release.countDown()
            assertTrue(retired.await(3, TimeUnit.SECONDS))
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("worker", "sent", "retired", "next"), events.toList())
        } finally { release.countDown() }
    }

    @Test(timeout = 10000) fun fullQueueRejectsWorkWithoutRunningItOnCaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CopyOnWriteArrayList<CountDownLatch>()
        assertTrue(CarLifeMediaCommands.enqueue { entered.countDown(); release.await(5, TimeUnit.SECONDS) })
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            var rejected = false
            for (i in 0 until 64) {
                val done = CountDownLatch(1)
                if (!CarLifeMediaCommands.enqueue { done.countDown() }) {
                    assertEquals(1L, done.count)
                    rejected = true
                    break
                }
                completed.add(done)
                assertEquals("accepted work stays queued", 1L, done.count)
            }
            assertTrue("queue must have a finite budget", rejected)
        } finally {
            release.countDown()
            completed.forEach { assertTrue(it.await(3, TimeUnit.SECONDS)) }
        }
    }
}

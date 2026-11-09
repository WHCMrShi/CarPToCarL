package io.github.shaonialife.carptocar.net

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PendingCtrlInviteTest {
    @Test fun diagnosticReadDoesNotConsumeOrExtendInvite() {
        val now = AtomicLong(100)
        val invite = PendingCtrlInvite(now::get, ttlMs = 30_000)
        invite.begin(); val generation = invite.currentGeneration()!!; invite.record(generation)
        now.set(20_100)
        assertEquals(20_000L, invite.peekAge(generation))
        assertEquals(20_000L, invite.peekAge(generation))
        assertEquals(20_000L, invite.consume(generation))
        assertNull(invite.peekAge(generation))
        invite.record(generation); now.set(50_101)
        assertNull(invite.peekAge(generation))
        assertNull(invite.consume(generation))
    }
    @Test fun diagnosticReadCannotSeeRetiredGeneration() {
        val invite = PendingCtrlInvite({ 100 })
        invite.begin(); val old = invite.currentGeneration()!!; invite.record(old)
        invite.end(); invite.begin(); val current = invite.currentGeneration()!!; invite.record(current)
        assertNull(invite.peekAge(old))
        assertEquals(0L, invite.peekAge(current))
        assertEquals(0L, invite.consume(current))
    }
    @Test fun lostServiceInvalidatesOldInviteButKeepsNewInvitationUsable() {
        val invite = PendingCtrlInvite({ 100L })
        invite.begin()
        val current = invite.currentGeneration()!!
        invite.record(current)
        assertTrue(invite.invalidate(current))
        assertNull(invite.consume(current))
        invite.record(current)
        assertEquals(0L, invite.consume(current))
    }
    @Test fun oldUserGenerationCannotInvalidateNewUsersInvite() {
        val invite = PendingCtrlInvite({ 100L })
        invite.begin()
        val old = invite.currentGeneration()!!
        invite.end(); invite.begin()
        val current = invite.currentGeneration()!!
        invite.record(current)
        assertFalse(invite.invalidate(old))
        assertEquals(0L, invite.consume(current))
    }
    @Test fun earlyInviteSurvivesEndpointHandoffAndIsConsumedOnlyOnce() {
        val now = AtomicLong(0)
        val invite = PendingCtrlInvite(now::get)
        invite.begin()
        val callbackGeneration = invite.currentGeneration()!!
        assertTrue(invite.record(callbackGeneration))
        // 端点晚到只捕获本轮代次；到绑定完成后的等待窗口才消费。
        val handoffGeneration = invite.currentGeneration()!!
        now.set(12_000)
        assertEquals(12_000L, invite.consume(handoffGeneration))
        assertNull(invite.consume(handoffGeneration))
    }

    @Test fun repeatedStartKeepsActiveHandoffAndItsInvitationValid() {
        val now = AtomicLong(100)
        val invite = PendingCtrlInvite(now::get)
        invite.begin()
        val handoffGeneration = invite.currentGeneration()!!
        invite.record(handoffGeneration)
        invite.begin()
        now.set(200)
        assertEquals(handoffGeneration, invite.currentGeneration())
        assertEquals(100L, invite.consume(handoffGeneration))
    }

    @Test fun ttlBoundaryIsAcceptedButOlderInvitationIsDiscarded() {
        val now = AtomicLong(100)
        val invite = PendingCtrlInvite(now::get)
        invite.begin()
        val generation = invite.currentGeneration()!!
        invite.record(generation)
        now.set(30_100)
        assertEquals(30_000L, invite.consume(generation))
        invite.record(generation)
        now.set(60_101)
        assertNull(invite.consume(generation))
        assertNull(invite.consume(generation))
    }

    @Test fun invalidClockAgeDoesNotReleaseConnection() {
        val now = AtomicLong(100)
        val invite = PendingCtrlInvite(now::get)
        invite.begin()
        val generation = invite.currentGeneration()!!
        invite.record(generation)
        now.set(99)
        assertNull(invite.consume(generation))
    }

    @Test fun disconnectInvalidatesCallbackCapturedBeforeStop() {
        val invite = PendingCtrlInvite({ 100L })
        invite.begin()
        val old = invite.currentGeneration()!!
        invite.record(old)
        invite.end()
        assertNull(invite.currentGeneration())
        assertFalse(invite.record(old))
        assertNull(invite.consume(old))
    }

    @Test fun restartClearsOldInviteAndOldConsumerCannotClearNewOne() {
        val invite = PendingCtrlInvite({ 100L })
        invite.begin()
        val old = invite.currentGeneration()!!
        invite.record(old)
        invite.end()
        invite.begin()
        val current = invite.currentGeneration()!!
        assertNull(invite.consume(current))
        assertTrue(invite.record(current))
        assertFalse(invite.record(old))
        assertNull(invite.consume(old))
        assertEquals(0L, invite.consume(current))
    }

    @Test fun concurrentConsumersReleaseAtMostOneWaiter() {
        val invite = PendingCtrlInvite({ 100L })
        invite.begin()
        val generation = invite.currentGeneration()!!
        invite.record(generation)
        val gate = CountDownLatch(1)
        val accepted = AtomicInteger()
        val consumers = List(8) {
            Thread { gate.await(); if (invite.consume(generation) != null) accepted.incrementAndGet() }.apply { start() }
        }
        gate.countDown()
        consumers.forEach { it.join(3000); assertFalse(it.isAlive) }
        assertEquals(1, accepted.get())
    }

    @Test fun racingRecordIsNotErasedByOldConsumption() {
        val now = AtomicLong(100)
        val pause = AtomicBoolean()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invite = PendingCtrlInvite({
            if (pause.compareAndSet(true, false)) {
                reading.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            now.get()
        })
        invite.begin()
        val generation = invite.currentGeneration()!!
        invite.record(generation)
        now.set(200)
        pause.set(true)
        val age = AtomicReference<Long>()
        val consumer = Thread { age.set(invite.consume(generation)) }.apply { start() }
        var writer: Thread? = null
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            writer = Thread { invite.record(generation) }.apply { start() }
            release.countDown()
            consumer.join(3000)
            writer.join(3000)
            assertFalse(consumer.isAlive)
            assertFalse(writer.isAlive)
            assertEquals(100L, age.get())
            assertEquals(0L, invite.consume(generation))
        } finally {
            release.countDown()
            consumer.join(3000); writer?.join(3000)
        }
    }

    @Test fun lateOldCallbackAfterDisconnectAndRestartCannotReplaceCurrentInvite() {
        val now = AtomicLong(100)
        val invite = PendingCtrlInvite(now::get)
        invite.begin()
        val old = invite.currentGeneration()!!
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val recorded = AtomicBoolean(true)
        val callback = Thread {
            captured.countDown()
            release.await()
            recorded.set(invite.record(old))
        }.apply { start() }
        try {
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            invite.end()
            invite.begin()
            val current = invite.currentGeneration()!!
            invite.record(current)
            now.set(200)
            release.countDown()
            callback.join(3000)
            assertFalse(callback.isAlive)
            assertFalse(recorded.get())
            assertEquals(100L, invite.consume(current))
        } finally { release.countDown(); callback.join(3000) }
    }
}

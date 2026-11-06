package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NowPlayingDispatcherTest {
    private val song = CarPlayNowPlaying("song", "artist", null, 180_000, 1, 1000, appName = "player")

    @Test fun blockedWriteDoesNotBlockRequestsAndOnlyLatestPendingValueIsSent() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestSent = CountDownLatch(1)
        val source = AtomicReference(song)
        val sent = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val dispatcher = NowPlayingDispatcher({ true }, { source.get() }, {
            sent.add(it.elapsedMs)
            if (sent.size == 1) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            else latestSent.countDown()
            true
        })
        try {
            dispatcher.subscribe()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val callbacksDone = CountDownLatch(1)
            val caller = Thread {
                for (position in 2..9000) {
                    source.set(song.copy(elapsedMs = position))
                    dispatcher.request()
                }
                callbacksDone.countDown()
            }.apply { start() }
            assertTrue("媒体回调不得等待发送 IO", callbacksDone.await(1, TimeUnit.SECONDS))
            caller.join(1000)
            release.countDown()
            assertTrue(latestSent.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(1000, 9000), sent.toList())
        } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun unsubscribeAndCloseReturnDuringBlockedWriteAndInvalidateQueuedUpdate() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val source = AtomicReference(song)
        val calls = AtomicInteger()
        val worker = AtomicReference<Thread>()
        val dispatcher = NowPlayingDispatcher({ true }, { source.get() }, {
            worker.set(Thread.currentThread())
            calls.incrementAndGet()
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { interrupted.countDown() }
            true
        })
        try {
            dispatcher.subscribe()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            source.set(song.copy(elapsedMs = 2000))
            dispatcher.request()
            val done = CountDownLatch(1)
            val caller = Thread { dispatcher.unsubscribe(); dispatcher.close(); done.countDown() }.apply { start() }
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertTrue(interrupted.await(3, TimeUnit.SECONDS))
            caller.join(1000)
            worker.get().join(3000)
            assertFalse(worker.get().isAlive)
            dispatcher.subscribe()
            dispatcher.request()
            assertEquals(1, calls.get())
        } finally { dispatcher.close() }
    }

    @Test fun missingMetadataRetryDoesNotDelayFreshMediaNotification() {
        val sampled = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val source = AtomicReference<CarPlayNowPlaying?>(null)
        val dispatcher = NowPlayingDispatcher({ true }, {
            source.get().also { sampled.countDown() }
        }, { delivered.countDown(); true }, longArrayOf(60_000))
        try {
            dispatcher.subscribe()
            assertTrue(sampled.await(3, TimeUnit.SECONDS))
            source.set(song)
            dispatcher.request()
            assertTrue("不能被旧的缺少 metadata 重试睡眠阻塞", delivered.await(1, TimeUnit.SECONDS))
        } finally { dispatcher.close() }
    }

    @Test fun oldSubscriptionSampleCannotBeSentAfterResubscribe() {
        val sampled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val reads = AtomicInteger()
        val sent = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val dispatcher = NowPlayingDispatcher({ true }, {
            if (reads.incrementAndGet() == 1) {
                sampled.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                song
            } else song.copy(elapsedMs = 9000)
        }, { sent.add(it.elapsedMs); delivered.countDown(); true })
        try {
            dispatcher.subscribe()
            assertTrue(sampled.await(3, TimeUnit.SECONDS))
            dispatcher.unsubscribe()
            dispatcher.subscribe()
            release.countDown()
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(9000), sent.toList())
        } finally { release.countDown(); dispatcher.close() }
    }
}

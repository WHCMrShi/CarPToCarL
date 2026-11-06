package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArtworkRepositoryTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
    private fun awaitComplete(source: ArtworkSource): ArtworkResult {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < end) {
            val result = source.request()
            if (result.complete) return result
            Thread.sleep(5)
        }
        error("image did not complete")
    }
    @Test fun sourceIsLazyAndCachedAcrossProgressSamples() {
        ArtworkRepository().use { repo ->
            val count = AtomicInteger()
            val source = repo.source("track", 1) { count.incrementAndGet(); jpeg }
            assertEquals(0, count.get())
            val first = awaitComplete(source).image
            assertNotNull(first)
            repeat(1000) {
                assertSame(source, repo.source("track", 1) { error("unexpected reload") })
                assertSame(first, source.request().image)
            }
            assertEquals(1, count.get())
        }
    }
    @Test fun encodedImageIsBoundedValidatedAndDefensivelyCopied() {
        assertNull(EncodedArtwork.create(ByteArray(0)))
        assertNull(EncodedArtwork.create(byteArrayOf(1, 2, 3)))
        assertNull(EncodedArtwork.create(ByteArray(EncodedArtwork.MAX_BYTES + 1)))
        val bytes = jpeg.copyOf()
        val encoded = EncodedArtwork.create(bytes)!!
        bytes[3] = 99
        val copy = ByteArray(4)
        encoded.copyTo(copy, 0, 0, 4)
        assertArrayEquals(jpeg, copy)
        assertEquals(EncodedArtwork.create(jpeg)!!.digest, encoded.digest)
    }
    @Test fun latePreviousTrackCannotOverwriteNewTrackAndQueueKeepsLatest() {
        ArtworkRepository().use { repo ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val obsoleteLoads = AtomicInteger()
            val old = repo.source("old", 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS); jpeg }
            try {
                old.request()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                repeat(20) { index ->
                    repo.source("obsolete-$index", 1) { obsoleteLoads.incrementAndGet(); jpeg }.request()
                }
                val expected = jpeg + byteArrayOf(7)
                val latest = repo.source("latest", 1) { expected }
                latest.request()
                assertNull(latest.request().image)
                release.countDown()
                assertEquals(EncodedArtwork.create(expected)!!.digest, awaitComplete(latest).image!!.digest)
                assertEquals(0, obsoleteLoads.get())
                assertNull(old.request().image)
            } finally { release.countDown() }
        }
    }
    @Test fun sameTrackRevisionRetainsImageUntilReplacementArrives() {
        ArtworkRepository().use { repo ->
            val initial = repo.source("track", 1) { jpeg }
            val first = awaitComplete(initial).image
            val release = CountDownLatch(1)
            try {
                val next = repo.source("track", 2) { release.await(3, TimeUnit.SECONDS); jpeg + byteArrayOf(2) }
                assertSame(first, next.request().image)
                release.countDown()
                assertNotEquals(first!!.digest, awaitComplete(next).image!!.digest)
            } finally { release.countDown() }
        }
    }
    @Test fun closeInterruptsLoaderAndPreventsLatePublicationOrNewWork() {
        val repo = ArtworkRepository()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val source = repo.source("track", 1) {
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { interrupted.countDown() }
            jpeg
        }
        source.request()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repo.close()
        assertTrue(interrupted.await(3, TimeUnit.SECONDS))
        assertNull(source.request().image)
        val after = repo.source("after", 2) { error("closed repository loaded image") }
        assertTrue(after.request().complete)
        assertNull(after.request().image)
    }
    @Test fun invalidOrFailedImageDoesNotCrashCallerOrSpinRetries() {
        val count = AtomicInteger()
        val logs = mutableListOf<String>()
        ArtworkRepository { synchronized(logs) { logs += it } }.use { repo ->
            val source = repo.source("track", 1) { count.incrementAndGet(); throw java.io.IOException("secret-url") }
            assertNull(awaitComplete(source).image)
            repeat(100) { source.request() }
            assertEquals(1, count.get())
            assertFalse(logs.joinToString().contains("secret-url"))
        }
    }
}

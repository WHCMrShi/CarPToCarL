package io.github.shaonialife.carptocar.iap2

import io.github.shaonialife.carptocar.media.ArtworkRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Iap2ArtworkSenderTest {
    private val song = CarPlayNowPlaying("song", "artist", "album", 180000, 1, 1000, appName = "player")
    private fun image(size: Int) = ByteArray(size) { it.toByte() }.also {
        it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte()
    }
    private fun loaded(repo: ArtworkRepository, key: String = "track", size: Int = 5000): CarPlayNowPlaying {
        val source = repo.source(key, 1) { image(size) }
        source.request()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!source.request().complete && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull(source.request().image)
        return song.copy(artworkSource = source)
    }
    private fun send(sender: Iap2ArtworkSender, limit: Int = 1024): ByteArray =
        sender.poll(limit)!!.let { sender.submitted(it, true); it.bytes }
    private fun start(sender: Iap2ArtworkSender, value: CarPlayNowPlaying): Int {
        val id = sender.decorate(value, 26, true).artworkTransferId!!
        sender.metadataSent(id)
        val setup = send(sender)
        assertEquals(4, setup[1].toInt())
        sender.response(byteArrayOf(id.toByte(), 1))
        return id
    }

    @Test fun capabilityAndExplicitSubscriptionGateNeverLoadsImage() {
        ArtworkRepository().use { repo ->
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val value = song.copy(artworkSource = repo.source("track", 1) { calls.incrementAndGet(); image(10) })
            val sender = Iap2ArtworkSender()
            assertNull(sender.decorate(value, 26, false).artworkTransferId)
            assertNull(sender.decorate(value, null, true).artworkTransferId)
            assertEquals(0, calls.get())
            assertNull(sender.poll(1024))
        }
    }

    @Test fun setupWaitsForMetadataAndStartThenReassemblesExactBytes() {
        ArtworkRepository().use { repo ->
            val value = loaded(repo)
            val sender = Iap2ArtworkSender()
            val id = sender.decorate(value, 26, true).artworkTransferId!!
            assertNull(sender.poll(1024))
            sender.metadataSent(id)
            val setup = send(sender)
            assertEquals(12, setup.size)
            assertEquals(5000L, setup.sliceArray(2..9).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 255) })
            assertArrayEquals(byteArrayOf(0, 2), setup.sliceArray(10..11))
            assertNull(sender.poll(1024))
            sender.response(byteArrayOf((id + 1).toByte(), 1))
            assertNull(sender.poll(1024))
            sender.response(byteArrayOf(id.toByte(), 1))
            val chunks = mutableListOf<ByteArray>()
            while (true) {
                val chunk = send(sender)
                assertTrue(chunk.size <= 1024)
                chunks += chunk
                if (chunk[1].toInt() and 0x40 != 0) break
            }
            assertEquals(0x80, chunks.first()[1].toInt() and 255)
            assertEquals(0x40, chunks.last()[1].toInt() and 255)
            assertArrayEquals(image(5000), chunks.flatMap { it.drop(2) }.toByteArray())
            sender.response(byteArrayOf(id.toByte(), 5))
            assertFalse(sender.pending())
            assertEquals(id, sender.decorate(value.copy(elapsedMs = 2000), 26, true).artworkTransferId)
            assertNull(sender.poll(1024))
        }
    }

    @Test fun singleChunkUsesFirstAndLastFlagsAndCachedSuccessNeedsNoData() {
        ArtworkRepository().use { repo ->
            val sender = Iap2ArtworkSender()
            val value = loaded(repo, size = 10)
            val id = start(sender, value)
            val data = send(sender)
            assertEquals(0xc0, data[1].toInt() and 255)
            sender.response(byteArrayOf(id.toByte(), 5))
            sender.reset()
            val nextId = sender.decorate(value, 26, true).artworkTransferId!!
            assertNotEquals(id, nextId)
            sender.metadataSent(nextId)
            send(sender) // previous cancellation, if needed, then SETUP
            sender.response(byteArrayOf(nextId.toByte(), 5))
            assertNull(sender.poll(1024))
            assertFalse(sender.pending())
        }
    }

    @Test fun failedSubmissionRollsBackOffsetAndSetupState() {
        ArtworkRepository().use { repo ->
            val sender = Iap2ArtworkSender()
            val value = loaded(repo)
            val id = sender.decorate(value, 26, true).artworkTransferId!!
            sender.metadataSent(id)
            val setup = sender.poll(100)!!
            sender.submitted(setup, false)
            assertArrayEquals(setup.bytes, send(sender, 100))
            sender.response(byteArrayOf(id.toByte(), 1))
            val chunk = sender.poll(100)!!
            sender.submitted(chunk, false)
            assertArrayEquals(chunk.bytes, send(sender, 100))
        }
    }

    @Test fun switchCancelsOldTransferAndIgnoresItsLateReply() {
        ArtworkRepository().use { repo ->
            val sender = Iap2ArtworkSender()
            val id = start(sender, loaded(repo))
            val replacement = loaded(repo, "next", 100)
            val nextId = sender.decorate(replacement, 26, true).artworkTransferId!!
            assertNotEquals(id, nextId)
            sender.metadataSent(nextId)
            assertArrayEquals(byteArrayOf(id.toByte(), 2), send(sender))
            assertEquals(4, send(sender)[1].toInt())
            sender.response(byteArrayOf(id.toByte(), 5))
            assertTrue(sender.pending())
            sender.response(byteArrayOf(nextId.toByte(), 1))
            assertEquals(0xc0, send(sender)[1].toInt() and 255)
        }
    }

    @Test fun missingArtworkClearsOldImageWithZeroLengthTransfer() {
        val sender = Iap2ArtworkSender()
        val value = sender.decorate(song, 20, true)
        assertEquals(128, value.artworkTransferId)
        assertEquals(20, value.artworkAttributeId)
        sender.metadataSent(value.artworkTransferId)
        val setup = send(sender)
        assertTrue(setup.sliceArray(2..9).all { it == 0.toByte() })
        sender.response(byteArrayOf(128.toByte(), 1))
        assertArrayEquals(byteArrayOf(128.toByte(), 0xc0.toByte()), send(sender))
    }

    @Test fun timeoutAndPeerFailureStopOnlyArtworkAndDoNotRestartOnProgress() {
        ArtworkRepository().use { repo ->
            var now = 0L
            val sender = Iap2ArtworkSender(clock = { now })
            val value = loaded(repo)
            val id = sender.decorate(value, 26, true).artworkTransferId!!
            sender.metadataSent(id); send(sender)
            now = 10001
            assertArrayEquals(byteArrayOf(id.toByte(), 2), send(sender, 100))
            assertFalse(sender.pending())
            sender.decorate(value.copy(elapsedMs = 3000), 26, true)
            assertNull(sender.poll(100))
            sender.reset(); start(sender, value)
            val nextId = sender.decorate(value, 26, true).artworkTransferId!!
            sender.response(byteArrayOf(nextId.toByte(), 6))
            assertFalse(sender.pending())
        }
    }

    @Test fun pendingEncodingStillSchedulesRefreshAfterEmptyTransferCompletes() {
        ArtworkRepository().use { repo ->
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val source = repo.source("track", 1) { entered.countDown(); release.await(3, TimeUnit.SECONDS); image(10) }
            try {
                val sender = Iap2ArtworkSender()
                val value = song.copy(artworkSource = source)
                val id = sender.decorate(value, 26, true).artworkTransferId!!
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                sender.metadataSent(id); send(sender)
                sender.response(byteArrayOf(id.toByte(), 5))
                assertTrue(sender.pending())
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!source.request().complete && System.nanoTime() < deadline) Thread.sleep(5)
                assertNotEquals(id, sender.decorate(value, 26, true).artworkTransferId)
            } finally { release.countDown() }
        }
    }

    @Test fun unusablySmallPayloadStopsWithoutBusyPolling() {
        val sender = Iap2ArtworkSender()
        val value = sender.decorate(song, 26, true)
        sender.metadataSent(value.artworkTransferId)
        assertNull(sender.poll(11))
        assertFalse(sender.pending())
    }

    @Test fun pauseResumesFromPreviousOffsetWithoutDuplicatingFirstChunk() {
        ArtworkRepository().use { repo ->
            val sender = Iap2ArtworkSender()
            val id = start(sender, loaded(repo))
            val first = send(sender, 100)
            sender.response(byteArrayOf(id.toByte(), 3))
            assertNull(sender.poll(100))
            sender.response(byteArrayOf(id.toByte(), 1))
            val next = send(sender, 100)
            assertEquals(0, next[1].toInt())
            assertArrayEquals(image(5000).sliceArray(98..195), next.drop(2).toByteArray())
            assertEquals(0x80, first[1].toInt() and 255)
        }
    }

    @Test fun transferIdsNeverWrapToAccessoryDirectionOrReuseCachedImageIds() {
        ArtworkRepository().use { repo ->
            val sender = Iap2ArtworkSender()
            val ids = mutableSetOf<Int>()
            repeat(127) { index ->
                val value = loaded(repo, "track-$index", 10 + index)
                val id = sender.decorate(value, 26, true).artworkTransferId!!
                assertTrue(id in 129..255)
                assertTrue(ids.add(id))
                sender.response(byteArrayOf(id.toByte(), 5))
            }
            assertEquals(128, sender.decorate(loaded(repo, "overflow", 200), 26, true).artworkTransferId)
        }
    }

    @Test fun resetAfterSetupProducesOnlyCancellationAndRetriesBlockedCancel() {
        ArtworkRepository().use { repo ->
            val sender = Iap2ArtworkSender()
            val id = start(sender, loaded(repo))
            send(sender, 100)
            sender.reset()
            assertTrue(sender.hasCancellation())
            val cancel = sender.poll(100)!!
            assertArrayEquals(byteArrayOf(id.toByte(), 2), cancel.bytes)
            sender.submitted(cancel, false)
            assertTrue(sender.hasCancellation())
            assertArrayEquals(cancel.bytes, send(sender, 100))
            assertFalse(sender.pending())
            assertNull(sender.poll(100))
        }
    }

    @Test fun artworkFieldRequiresCorrectMediaGroupAndSupportsBothVersions() {
        fun body(vararg ids: Int) = Iap2Csm.group(0, *ids.map { Iap2Csm.u8(it, 1) }.toTypedArray())
        assertEquals(26, Iap2ArtworkSender.requestedField(body(6, 20, 26)))
        assertEquals(20, Iap2ArtworkSender.requestedField(body(20)))
        assertNull(Iap2ArtworkSender.requestedField(body(6)))
        assertNull(Iap2ArtworkSender.requestedField(ByteArray(0)))
        assertNull(Iap2ArtworkSender.requestedField(Iap2Csm.group(1, Iap2Csm.u8(26, 1))))
    }
}

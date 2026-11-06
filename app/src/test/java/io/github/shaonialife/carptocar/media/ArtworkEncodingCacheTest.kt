package io.github.shaonialife.carptocar.media

import org.junit.Assert.*
import org.junit.Test

class ArtworkEncodingCacheTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)

    @Test fun unrelatedMetadataRevisionsDoNotReencodeUnchangedBitmap() {
        val bitmap = Any()
        val cache = ArtworkEncodingCache()
        var encodes = 0
        ArtworkRepository().use { repo ->
            repeat(50) { revision ->
                val source = repo.source("track", revision.toLong()) {
                    cache.bitmap(bitmap, 1) { encodes++; jpeg }
                }
                val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!source.request().complete && System.nanoTime() < end) Thread.sleep(2)
                assertNotNull(source.request().image)
            }
        }
        assertEquals(1, encodes)
        cache.bitmap(bitmap, 2) { encodes++; jpeg }
        assertEquals(2, encodes) // same mutable bitmap, new generation
    }

    @Test fun equalParcelableBitmapCopyReusesEncodingButChangedPixelsDoNot() {
        data class Pixels(val value: Int)
        val cache = ArtworkEncodingCache()
        var encodes = 0
        val original = Pixels(1)
        val first = cache.bitmap(original, 1) { encodes++; jpeg }
        val copy = Pixels(1)
        assertSame(first, cache.bitmap(copy, 1, { old, generation -> old == copy && generation == 1 }) {
            encodes++; jpeg
        })
        cache.bitmap(Pixels(2), 1, { old, _ -> old == Pixels(2) }) { encodes++; jpeg }
        assertEquals(2, encodes)
    }

    @Test fun sameUriUpdatedBytesInvalidateConversionAndFailedEncodingCanRetry() {
        val cache = ArtworkEncodingCache()
        var conversions = 0
        val initial = byteArrayOf(1, 2, 3)
        val result = cache.bytes(initial) { conversions++; jpeg }
        assertSame(result, cache.bytes(initial.copyOf()) { conversions++; jpeg })
        cache.bytes(byteArrayOf(1, 2, 4)) { conversions++; jpeg }
        assertEquals(2, conversions)
        val key = Any()
        assertNull(cache.bitmap(key, 1) { null })
        assertNotNull(cache.bitmap(key, 1) { jpeg })
    }
}

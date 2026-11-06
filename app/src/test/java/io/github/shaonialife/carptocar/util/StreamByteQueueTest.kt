package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StreamByteQueueTest {
    @Test fun cursorConsumptionCompactsAndGrowsWithoutChangingBytes() {
        val q = StreamByteQueue(4)
        q.append(byteArrayOf(1, 2, 3, 4))
        val storage = q.storage
        q.discard(2)
        q.append(byteArrayOf(5, 6))
        assertSame(storage, q.storage)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), q.copy(0, q.size))
        q.append(byteArrayOf(7, 8, 9))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9), q.copy(0, q.size))
    }

    @Test fun appendRangeOwnsBytesAndLargeDrainedBuffersAreReleased() {
        val q = StreamByteQueue(4, 8)
        val source = ByteArray(40) { it.toByte() }
        q.append(source, 5, 20)
        source.fill(0)
        assertEquals(5.toByte(), q.copy(0, 1)[0])
        q.discard(20)
        assertEquals(0, q.size)
        assertEquals(4, q.storage.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSliceRejectedBeforeMutation() {
        StreamByteQueue().append(ByteArray(2), 1, 2)
    }
}

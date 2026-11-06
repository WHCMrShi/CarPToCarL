package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException

class PacketPayloadBufferTest {
    private fun input(vararg values: Int) = DataInputStream(ByteArrayInputStream(values.map { it.toByte() }.toByteArray()))

    @Test fun ownedPacketsRemainIndependentAndExactSized() {
        val buffers = PacketPayloadBuffer()
        val first = buffers.read(input(1, 2, 3), 3, false)
        val second = buffers.read(input(4, 5), 2, false)
        assertNotSame(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        assertArrayEquals(byteArrayOf(4, 5), second)
        assertEquals(2, buffers.lastAllocatedBytes)
    }

    @Test fun borrowedPacketsReuseStorageAndConsumersUseOnlyValidLength() {
        val buffers = PacketPayloadBuffer()
        val first = buffers.read(input(1, 2, 3), 3, true)
        val snapshot = first.copyOf(3)
        val second = buffers.read(input(4, 5), 2, true)
        assertSame(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot)
        assertArrayEquals(byteArrayOf(4, 5), second.copyOf(2))
        assertEquals(0, buffers.lastAllocatedBytes)
    }

    @Test fun ownedInitPacketDoesNotAliasBorrowedPcm() {
        val buffers = PacketPayloadBuffer()
        val pcm = buffers.read(input(1, 2), 2, true)
        val init = buffers.read(input(3), 1, false)
        assertNotSame(pcm, init)
        assertEquals(1, init.size)
        assertArrayEquals(byteArrayOf(1, 2), pcm.copyOf(2))
    }

    @Test fun oversizedPacketIsNotRetainedAndEmptyPacketsAllocateNothing() {
        val buffers = PacketPayloadBuffer(4)
        val retained = buffers.read(input(1, 2), 2, true)
        val large = buffers.read(input(1, 2, 3, 4, 5), 5, true)
        assertEquals(5, large.size)
        assertNotSame(retained, large)
        assertSame(retained, buffers.read(input(6), 1, true))
        assertEquals(0, buffers.read(input(), 0, true).size)
        assertEquals(0, buffers.lastAllocatedBytes)
    }

    @Test(expected = EOFException::class)
    fun truncatedPacketStillFailsRatherThanReusingStaleBytes() {
        PacketPayloadBuffer().read(input(1), 2, true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLength() {
        PacketPayloadBuffer().read(input(), -1, true)
    }
}

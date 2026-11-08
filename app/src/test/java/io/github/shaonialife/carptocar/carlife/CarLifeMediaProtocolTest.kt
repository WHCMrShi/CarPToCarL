package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeMediaProtocolTest {
    private fun string(n: Int, s: String) = s.toByteArray(Charsets.UTF_8).let { byteArrayOf((n * 8 + 2).toByte(), it.size.toByte()) + it }
    private fun media() = string(1, "1794117994180") + string(2, "漫步人生路") + string(3, "Teresa Teng") +
        string(4, "专辑") + byteArrayOf(42, 3, 1, 2, 3, 48, 100, 56, 2) + string(8, "track-id") + byteArrayOf(72, 1)

    @Test fun directMessageMatchesObservedFieldLayout() {
        val info = CarLifeProtocol.parseMediaInfoList(media()).single()
        assertEquals("漫步人生路", info.song)
        assertEquals("Teresa Teng", info.artist)
        assertEquals("专辑", info.album)
        assertArrayEquals(byteArrayOf(1, 2, 3), info.albumArt)
        assertEquals(100, info.duration)
        assertEquals("track-id", info.songId)
    }

    @Test fun wrappedMessageAndUnknownFieldsRemainSupported() {
        val one = media()
        val payload = byteArrayOf(8, 1, 18, one.size.toByte()) + one + byteArrayOf(120, 1)
        assertEquals("漫步人生路", CarLifeProtocol.parseMediaInfoList(payload).single().song)
        assertTrue(CarLifeProtocol.parseMediaInfoList(byteArrayOf(8, 2, 18, one.size.toByte()) + one).isEmpty())
    }

    @Test fun malformedAndAmbiguousDataDoNotLeakPartialTrack() {
        assertTrue(CarLifeProtocol.parseMediaInfoList(media() + byteArrayOf(82, 127)).isEmpty())
        assertTrue(CarLifeProtocol.parseMediaInfoList(byteArrayOf(18, 1, 65)).isEmpty())
        assertTrue(CarLifeProtocol.parseMediaInfoList(byteArrayOf(8, 1, 18, 100, 8)).isEmpty())
    }

    @Test fun progressDecodesVarintRatherThanBigEndianHeader() {
        assertEquals(1500, CarLifeMediaProtocol.progress(byteArrayOf(8, 0xdc.toByte(), 0x0b)))
        assertEquals(0, CarLifeMediaProtocol.progress(byteArrayOf(8, 0)))
        assertNull(CarLifeMediaProtocol.progress(byteArrayOf(8, 0x80.toByte())))
        assertNull(CarLifeMediaProtocol.progress(byteArrayOf(0, 0, 0, 1)))
    }
}

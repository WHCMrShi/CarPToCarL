package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeHudProtocolTest {
    @Test fun subscriptionAndStartUseListWithExplicitZeroModule() {
        assertArrayEquals(byteArrayOf(8, 2, 18, 4, 8, 0, 16, 1, 18, 4, 8, 1, 16, 1), CarLifeHudProtocol.subscription(listOf(0, 1)))
        assertArrayEquals(byteArrayOf(8, 1, 18, 4, 8, 0, 16, 1), CarLifeHudProtocol.subscription(listOf(0)))
    }

    @Test fun modernTurnKeepsUnicodeRoadAndOriginalIcon() {
        val road = "测试路\n右转".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(8, 1, 16, 3, 26, road.size.toByte()) + road +
            byteArrayOf(32, 100, 40, 50, 50, 4, 0x89.toByte(), 0x50, 0x4e, 0x47)
        val turn = CarLifeHudProtocol.turn(payload)!!
        assertEquals("测试路\n右转", turn.road)
        assertEquals(3, turn.turn)
        assertEquals(100, turn.distance)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), turn.icon)
    }

    @Test fun legacyVarintTimeAndFutureUnknownFieldsDoNotBreakFollowingFields() {
        val payload = byteArrayOf(8, 2, 48, 60, 0x79, 0, 0, 0, 0, 0, 0, 0, 0, 0x85.toByte(), 1, 0, 0, 0, 0, 40, 20)
        val fields = CarLifeHudProtocol.parse(payload)
        assertEquals(60, fields.int(6))
        assertEquals(20, fields.int(5))
        assertNull(fields.bytes(6))
        assertNull(fields.error)
    }

    @Test fun malformedWireDataIsContainedAndKeepsEarlierFields() {
        for (tail in listOf(byteArrayOf(50, 10, 1), byteArrayOf(16, 0x80.toByte()), byteArrayOf(0), byteArrayOf(11), ByteArray(11) { 0x80.toByte() })) {
            val f = CarLifeHudProtocol.parse(byteArrayOf(8, 3) + tail)
            assertNotNull(f.error)
            assertEquals(3, f.int(1))
        }
        assertNotNull(CarLifeHudProtocol.parse(ByteArray(65536)).error)
        assertNotNull(CarLifeHudProtocol.parse(ByteArray(258) { if (it % 2 == 0) 8 else 0 }).error)
    }

    @Test fun subscriptionReplyRejectsTruncationCountMismatchAndDuplicates() {
        val valid = byteArrayOf(8, 1, 18, 4, 8, 0, 16, 1)
        assertEquals(listOf(0 to 1), CarLifeHudProtocol.reply(valid).entries)
        assertNull(CarLifeHudProtocol.reply(valid).error)
        assertNotNull(CarLifeHudProtocol.reply(valid.copyOf(valid.size - 1)).error)
        assertNotNull(CarLifeHudProtocol.reply(byteArrayOf(8, 1, 18, 2, 8, 0)).error)
        assertNotNull(CarLifeHudProtocol.reply(byteArrayOf(8, 2) + valid.copyOfRange(2, valid.size)).error)
        assertNotNull(CarLifeHudProtocol.reply(byteArrayOf(8, 2) + valid.copyOfRange(2, valid.size) + valid.copyOfRange(2, valid.size)).error)
    }
}

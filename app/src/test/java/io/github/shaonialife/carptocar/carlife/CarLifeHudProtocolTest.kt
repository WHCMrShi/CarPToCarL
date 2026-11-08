package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeHudProtocolTest {
    @Test fun subscriptionAndStartUseListWithExplicitZeroModule() {
        assertArrayEquals(byteArrayOf(8, 2, 18, 4, 8, 0, 16, 1, 18, 4, 8, 1, 16, 1), CarLifeHudProtocol.subscription(listOf(0, 1)))
        assertArrayEquals(byteArrayOf(8, 1, 18, 4, 8, 0, 16, 1), CarLifeHudProtocol.subscription(listOf(0)))
    }

    @Test fun modernTurnKeepsUnicodeRoadAndSummarizesIcon() {
        val road = "测试路\n右转".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(8, 1, 16, 3, 26, road.size.toByte()) + road +
            byteArrayOf(32, 100, 40, 50, 50, 4, 0x89.toByte(), 0x50, 0x4e, 0x47)
        val text = CarLifeHudProtocol.describe(CarLifeHudProtocol.NEXT_TURN, payload)
        assertTrue(text.contains("roadName=\"测试路\\u000a右转\""))
        assertTrue(text.contains("nextTurn=3"))
        assertTrue(text.contains("remainDistanceM=50"))
        assertTrue(text.contains("icon=4B sha256="))
        assertTrue(text.contains("head=89504e47"))
        assertTrue(text.contains("parseError=none"))
    }

    @Test fun legacyVarintTimeAndFutureUnknownFieldsDoNotBreakFollowingFields() {
        val payload = byteArrayOf(8, 2, 48, 60, 0x79, 0, 0, 0, 0, 0, 0, 0, 0, 0x85.toByte(), 1, 0, 0, 0, 0, 40, 20)
        val text = CarLifeHudProtocol.describe(CarLifeHudProtocol.NEXT_TURN, payload)
        assertTrue(text.contains("legacyTime=60"))
        assertTrue(text.contains("remainDistanceM=20"))
        assertTrue(text.contains("icon=absent"))
        assertTrue(text.contains("parseError=none"))
    }

    @Test fun assistantSpeedIsLoggedWithoutAssumingDisplayUnits() {
        val text = CarLifeHudProtocol.describe(CarLifeHudProtocol.ASSISTANT_GUIDE, byteArrayOf(8, 1, 16, 2, 24, 0, 32, 100, 40, 50, 48, 30))
        assertTrue(text.contains("cameraSpeedRaw=30"))
        assertTrue(text.contains("assistantType=2"))
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

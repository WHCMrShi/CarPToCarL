package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeHudObserverTest {
    private class Fixture {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        val lines = mutableListOf<String>()
        val observer = CarLifeHudObserver({ id, data -> sent += id to data; true }, lines::add)
    }

    @Test fun waitsForVersionAndStartsOnlyConfirmedModulesOnce() {
        val f = Fixture()
        f.observer.tick(0)
        assertTrue(f.sent.isEmpty())
        f.observer.versionMatched()
        f.observer.tick(1)
        f.observer.receive(CarLifeHudProtocol.SUBSCRIBE_DONE, byteArrayOf(8, 2, 18, 4, 8, 0, 16, 1, 18, 4, 8, 1, 16, 0))
        f.observer.tick(2)
        assertEquals(listOf(CarLifeHudProtocol.SUBSCRIBE, CarLifeHudProtocol.START), f.sent.map { it.first })
        assertArrayEquals(CarLifeHudProtocol.subscription(listOf(0)), f.sent.last().second)
        f.observer.versionMatched()
        f.observer.tick(30002)
        assertEquals(2, f.sent.size)
    }

    @Test fun absentReplyGetsOneBoundedUnconfirmedStartAndPeriodicEvidence() {
        val f = Fixture()
        f.observer.versionMatched()
        f.observer.tick(100)
        f.observer.tick(5099)
        assertEquals(1, f.sent.size)
        f.observer.tick(5100)
        f.observer.tick(30100)
        f.observer.tick(60100)
        assertEquals(2, f.sent.size)
        assertTrue(f.lines.any { it.contains("confirmed=false") })
        assertTrue(f.lines.any { it.contains("nextTurnRx=0") })
    }

    @Test fun explicitRejectionOrMalformedAckNeverTriggersFallbackStart() {
        for (reply in listOf(byteArrayOf(8, 1, 18, 4, 8, 0, 16, 0), byteArrayOf(18, 10))) {
            val f = Fixture()
            f.observer.versionMatched()
            f.observer.tick(0)
            f.observer.receive(CarLifeHudProtocol.SUBSCRIBE_DONE, reply)
            f.observer.tick(1)
            f.observer.tick(60000)
            assertEquals(1, f.sent.size)
        }
    }

    @Test fun passivelyLogsDataBeforeHandshakeAndUsesCorrectModuleNamespaces() {
        val f = Fixture()
        f.observer.receive(CarLifeHudProtocol.NEXT_TURN, byteArrayOf(8, 3))
        f.observer.receive(CarLifeHudProtocol.ASSISTANT_GUIDE, byteArrayOf(8, 2))
        f.observer.moduleStatus(listOf(2 to 1, 8 to 0))
        assertTrue(f.lines.any { it.contains("NEXT_TURN action=3") })
        assertTrue(f.lines.any { it.contains("ASSISTANT_GUIDE action=2") })
        assertTrue(f.lines.any { it.contains("NAVI=2/EDOG=8") })
        assertTrue(f.sent.isEmpty())
    }
}

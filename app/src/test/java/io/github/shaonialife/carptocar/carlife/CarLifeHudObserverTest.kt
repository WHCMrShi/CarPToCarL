package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.*
import org.junit.Test

class CarLifeHudObserverTest {
    @Test fun fullImageFieldReachesNavigationWithoutGuessingMissingOrMalformedFields() {
        val received = mutableListOf<io.github.shaonialife.carptocar.media.HudTurn>()
        val observer = CarLifeHudObserver({ _, _ -> true }, {}, received::add)
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val body = byteArrayOf(8, 1, 16, 7, 26, 1, 120, 32, 100, 40, 100, 50, 8) + png
        observer.receive(CarLifeHudProtocol.NEXT_TURN, body)
        assertEquals(1, received.size)
        assertEquals(100, received.single().distance)
        assertArrayEquals(png, received.single().icon)
        observer.receive(CarLifeHudProtocol.NEXT_TURN, byteArrayOf(8, 1))
        observer.receive(CarLifeHudProtocol.NEXT_TURN, body.copyOf(body.size - 1))
        assertEquals(1, received.size)
    }

    @Test fun failedSubscribeCannotFallThroughToUnconfirmedStart() {
        val ids = mutableListOf<Int>()
        val lines = mutableListOf<String>()
        val observer = CarLifeHudObserver({ id, _ -> ids += id; false }, lines::add)
        observer.versionMatched(); observer.tick(0); observer.tick(6000)
        assertEquals(listOf(CarLifeHudProtocol.SUBSCRIBE), ids)
        assertTrue(lines.any { it.contains("abandoned subscription") })
    }
    @Test fun boundedControlHistogramAndNavigationStateExplainMissingTbt() {
        val f = Fixture()
        f.observer.versionMatched()
        f.observer.tick(100)
        repeat(40) { f.observer.controlMessage(0x10100 + it) }
        f.observer.moduleStatus(listOf(2 to 1), 1000)
        f.observer.tick(30100)
        assertTrue(f.lines.any { it.contains("navigation={2=1}") && it.contains("navigationStateAgeMs=29100") })
        assertTrue(f.lines.any { it.contains("otherControlRx=8") })
        assertTrue(f.lines.any { it.contains("0x10100:1") })
        assertFalse(f.lines.any { it.contains("0x10127:1") })
    }

    @Test fun failedStartIsDistinctFromSuccessfulDecisionAndAckLatencyIsRecorded() {
        val lines = mutableListOf<String>()
        val observer = CarLifeHudObserver({ id, _ -> id != CarLifeHudProtocol.START }, lines::add)
        observer.versionMatched()
        observer.tick(100)
        observer.receive(CarLifeHudProtocol.SUBSCRIBE_DONE, byteArrayOf(8, 1, 18, 4, 8, 0, 16, 1), 127)
        observer.tick(1000)
        observer.tick(30100)
        assertTrue(lines.any { it.contains("latencyMs=27") })
        assertTrue(lines.any { it.contains("startAttempted=true startSent=false") })
    }

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
        assertTrue(f.lines.any { it.contains("START id=") && it.contains("supportConfirmed=true") && it.contains("not a START acknowledgement") })
        assertTrue(f.lines.any { it.contains("SUBSCRIBE_DONE") && it.contains("capability reply only") })
        assertFalse(f.lines.any { it.contains(" confirmed=") })
        assertTrue(f.lines.any { it.contains("activation unverified") })
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
        assertTrue(f.lines.any { it.contains("supportConfirmed=false") })
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

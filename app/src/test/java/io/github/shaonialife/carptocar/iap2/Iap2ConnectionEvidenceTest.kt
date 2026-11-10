package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.*
import org.junit.Test

class Iap2ConnectionEvidenceTest {
    @Test fun selectedMessagesKeepTheirObservedOrderAndOriginalScope() {
        var now = 100L
        val logs = mutableListOf<String>()
        val evidence = Iap2ConnectionEvidence("connection=original btOwner=4 direction=inbound", logs::add) { now }
        evidence.phase("availability")
        now += 3
        evidence.message("TX_SUBMITTED", 0x4300, "awaitAck=false")
        now += 2
        evidence.message("TX_SUBMITTED", 0x4e0e, "awaitAck=false")
        evidence.message("RX_PARSED", 0x4301)
        assertTrue(logs[1].contains("elapsedMs=3") && logs[1].contains("id=0x4300"))
        assertTrue(logs[2].contains("elapsedMs=5") && logs[2].contains("id=0x4e0e"))
        assertTrue(logs[3].contains("event=RX_PARSED"))
        assertTrue(logs.all { it.contains("connection=original btOwner=4 direction=inbound") })
    }

    @Test fun repeatedMessagesAreBoundedButSummaryRetainsTotal() {
        val logs = mutableListOf<String>()
        val evidence = Iap2ConnectionEvidence("test", logs::add) { 0L }
        repeat(1000) { evidence.message("RX_PARSED", 0x4301) }
        repeat(1000) { evidence.message("RX_PARSED", 0x6802) }
        evidence.summary("phase1-failed", "alive=false")
        assertEquals(4, logs.size)
        assertTrue(logs.last().contains("RX_PARSED:0x4301=1000"))
        assertFalse(logs.any { it.contains("0x6802") })
        assertFalse(evidence.observes(0x6802))
    }

    @Test fun phasesAndDecisionsCannotProduceAnUnboundedDiagnosticBurst() {
        val logs = mutableListOf<String>()
        val evidence = Iap2ConnectionEvidence("test", logs::add) { 0L }
        repeat(1000) { evidence.phase("phase$it"); evidence.decision("test") }
        assertEquals(22, logs.size)
        evidence.summary("ended", "")
        assertTrue(logs.last().contains("phase=phase999"))
    }

    @Test fun newBtSessionsHaveDistinctDiagnosticSessionsEvenInOneUserRound() {
        val logs = mutableListOf<String>()
        Iap2ConnectionEvidence("connection=same", logs::add) { 0L }.phase("link-ready")
        Iap2ConnectionEvidence("connection=same", logs::add) { 0L }.phase("link-ready")
        assertNotEquals(logs[0].substringBefore("connection="), logs[1].substringBefore("connection="))
    }

    @Test fun loggingFailureDoesNotChangeProtocolControlFlow() {
        val evidence = Iap2ConnectionEvidence("test", { throw IllegalStateException("logger") }) { 0L }
        evidence.phase("link-ready")
        evidence.message("TX_SUBMITTED", 0x4300)
        evidence.decision("wait")
        evidence.summary("failed", "alive=false")
    }
}

package io.github.shaonialife.carptocar.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun earlyHandshakeSurvivesRepeatedMainAndDiagnosticRotation() {
        val main = temp.newFile()
        val writer = SessionLogFile(main, "marker", maxBytes = 30)
        writer.write(listOf("STAGE: first handshake\n"), 0)
        repeat(80) { writer.write(listOf("IAPLINK: periodic media ${"x".repeat(100)}\n"), 0) }
        assertFalse(main.readText().contains("first handshake"))
        assertTrue(DiagnosticLogArchive.read(main).contains("first handshake"))
        assertFalse(DiagnosticLogArchive.read(main).contains("periodic media"))
        val archive = DiagnosticLogArchive(main, limit = 120)
        archive.write(listOf("STAGE: preserved first\n"), 0)
        repeat(100) { archive.write(listOf("ARTWORK: event-$it ${"x".repeat(30)}\n"), 0) }
        val exported = DiagnosticLogArchive.read(main)
        assertTrue(exported.contains("preserved first"))
        assertTrue(exported.contains("event-99"))
        assertTrue(main.parentFile!!.listFiles()!!.filter { it.name.startsWith(main.name + ".diagnostic-") }.all { it.length() <= 120 })
    }

    @Test fun newProcessDiscardsOldDiagnosticSidecarsAndReportsQueueLoss() {
        val main = temp.newFile()
        DiagnosticLogArchive(main).write(listOf("STAGE: old process\n"), 0)
        DiagnosticLogArchive(main).write(listOf("CONNECT-DIAG: new process\n"), 42)
        val text = DiagnosticLogArchive.read(main)
        assertFalse(text.contains("old process"))
        assertTrue(text.contains("new process"))
        assertTrue(text.contains("42"))
    }

    @Test fun diagnosticCopyDoesNotRetainLoggedKeyPrefixes() {
        val text = DiagnosticLogArchive.redact("AIRPLAY: ✓ writeKey=secret readKey=other psk=password done")
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("other"))
        assertFalse(text.contains("password"))
        assertTrue(text.contains("done"))
        assertFalse(DiagnosticLogArchive.isDiagnostic("RAW rx: binary payload"))
        assertTrue(DiagnosticLogArchive.isDiagnostic("HUD-DIAG: NEXT_TURN action=1"))
    }

    @Test fun blockedVideoAndHandshakeEvidenceSurvivesMainLogRotation() {
        val main = temp.newFile()
        val writer = SessionLogFile(main, "marker", maxBytes = 30)
        val evidence = listOf(
            "IDLE: check source=service lateMs=1200 verdict=UI_VISIBLE",
            "APPROACH: target ACL disconnected",
            "SVC: lock-release wakeHeld=false wifiHeld=false",
            "WIFI: fallback ignored replacement network=123",
            "WiFi: fallback binding complete",
            "iap2 并行兜底：0x5702 等待超时",
            "VIDEO-WRITE: timeout kind=frame elapsedMs=4000",
            "VIDEO-QUEUE: dropped=32 waitingIdr=true",
            "VIDEO-PATH: wireFrames=150 write=frame:4000ms",
            "CARLIFE-DIAG: phase=header-read phaseAgeMs=9000",
            "CARLIFE: 控制通道断开 - Connection reset",
            "CARLIFE: 版本握手失败 reason=control-read elapsedMs=2100",
            "EVENT-DIAG: changeModes request={resources=[]}",
            "PATH-DIAG: stage=PAIRING event=END durationMs=500",
            "HANDOVER-DIAG: decision=CANCEL_SETUP",
            "RESOURCE-DIAG: response transportAccepted=true",
            "KEYFRAME-DIAG: IDR_WRITTEN",
        )
        writer.write(evidence.map { "$it\n" }, 0)
        repeat(10) { writer.write(listOf("ordinary line ${"x".repeat(100)}\n"), 0) }
        val exported = DiagnosticLogArchive.read(main)
        evidence.forEach { assertTrue(it, exported.contains(it)) }
        assertFalse(DiagnosticLogArchive.isDiagnostic("VIDEO-PATH: RAW rx: binary payload"))
    }
}

package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2EaDiagnosticsTest {
    private fun statusLog(body: ByteArray) =
        requireNotNull(Iap2Csm.Frame(Iap2MessageIds.EA_SESSION_STATUS, body).eaReceiveLog("BT"))

    @Test
    fun readsStatusByFieldIdAndPreservesUnknownValues() {
        val session = Iap2Csm.u16(0, 0xabcd)
        for ((code, label) in listOf(0 to "SessionStatusOK", 1 to "SessionClose", 255 to "unknown")) {
            val line = statusLog(Iap2Csm.u8(1, code) + session)
            assertTrue(line.contains("session=43981 status=$code($label)"))
            assertTrue(line.endsWith("00 06 00 00 ab cd]"))
        }
    }

    @Test
    fun exposesTruncatedOrAmbiguousStatusInsteadOfReportingSuccess() {
        val session = Iap2Csm.u16(0, 0)
        val status = Iap2Csm.u8(1, 0)
        assertTrue(statusLog(session + status + byteArrayOf(1)).contains("malformed TLV"))
        assertTrue(statusLog(session + status + status).contains("invalid fields"))
        assertTrue(statusLog(session).contains("invalid fields"))
    }
}

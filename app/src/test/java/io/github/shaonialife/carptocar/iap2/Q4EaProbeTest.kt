package io.github.shaonialife.carptocar.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Q4EaProbeTest {
    @Test
    fun opensThreeDeclaredProtocolsInOrderWithDistinctBigEndianSessionIds() {
        val requests = Q4EaProbe.requests(
            declaration(3, "com.audi.cpp.eadata1") + declaration(2, "com.audi.cpp.eadata0") +
                declaration(1, "de.audi.mmiconnectdatatransfer"),
        )
        assertEquals(listOf(1, 2, 3), requests.map { it.protocolId })
        assertEquals(listOf(257, 258, 259), requests.map { it.sessionId })
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 1, 0, 6, 0, 1, 1, 1), requests[0].body())
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 2, 0, 6, 0, 1, 1, 2), requests[1].body())
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 3, 0, 6, 0, 1, 1, 3), requests[2].body())
    }

    @Test
    fun usesAdvertisedIdAndDoesNotInventMissingOrAmbiguousDeclarations() {
        val eadata1 = declaration(8, "com.audi.cpp.eadata1")
        val identified = eadata1 + eadata1 + declaration(2, "com.audi.cpp.eadata0") + declaration(2, "other.protocol")
        assertEquals(Q4EaProbe.Request(8, "com.audi.cpp.eadata1", 259), Q4EaProbe.requests(identified).single())
        assertTrue(Q4EaProbe.requests(ByteArray(0)).isEmpty())
    }

    @Test
    fun logsStatusAndRetainsMalformedBodyWithoutInferringASession() {
        val status = Iap2Csm.u16(0, 258) + Iap2Csm.u8(1, 1)
        val frame = Iap2Csm.Frame(Iap2MessageIds.EA_SESSION_STATUS, status)
        assertTrue(Q4EaProbe.responseLog("BT", frame)!!.contains("0xea03 session=258 status=1(SessionClose)"))
        val malformed = Q4EaProbe.responseLog("WiFi", frame.copy(body = status + byteArrayOf(0)))!!
        assertTrue(malformed.contains("0xea03 malformed TLV len=12 body="))
        assertTrue(!malformed.contains("session="))
        assertNull(Q4EaProbe.responseLog("BT", frame.copy(messageId = Iap2MessageIds.REQUEST_APP_LAUNCH)))
    }

    private fun declaration(id: Int, name: String): ByteArray =
        Iap2Csm.group(10, Iap2Csm.u8(0, id), Iap2Csm.string(1, name))
}

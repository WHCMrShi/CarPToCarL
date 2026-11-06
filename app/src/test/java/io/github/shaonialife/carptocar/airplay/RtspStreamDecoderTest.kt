package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspStreamDecoderTest {
    private fun message(
        body: ByteArray,
        cseq: Int = 1,
    ): ByteArray = "POST /command RTSP/1.0\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body

    @Test fun everyPossibleTcpSplitReassemblesOwnedBody() {
        val body = byteArrayOf(0, 13, 10, -1, 1)
        val wire = message(body)
        for (split in 0..wire.size) {
            val parser = RtspStreamDecoder()
            val messages = parser.offer(wire, 0, split) + parser.offer(wire, split, wire.size - split)
            assertEquals(1, messages.size)
            assertArrayEquals(body, messages.single().body)
            assertEquals("POST /command RTSP/1.0", messages.single().firstLine)
        }
    }

    @Test fun pipelinedMessagesAndTruncatedBodyRetainCorrectBoundaries() {
        val parser = RtspStreamDecoder()
        val one = message(byteArrayOf(1, 2), 1)
        val two = message(byteArrayOf(3, 4, 5), 2)
        val first = parser.offer(one + two.copyOf(two.size - 1))
        assertEquals(1, first.size)
        assertArrayEquals(byteArrayOf(1, 2), first[0].body)
        val second = parser.offer(byteArrayOf(5))
        assertArrayEquals(byteArrayOf(3, 4, 5), second.single().body)
        assertArrayEquals(byteArrayOf(1, 2), first[0].body)
    }

    @Test fun oneByteFragmentsHandleBinaryLargeBodyAndHeaderDelimiterInsideBody() {
        val body = ByteArray(1000) { (it % 255).toByte() }
        val parser = RtspStreamDecoder()
        val result = mutableListOf<RtspStreamDecoder.Message>()
        val wire = message(body)
        for (index in wire.indices) result.addAll(parser.offer(wire, index, 1))
        assertArrayEquals(body, result.single().body)
    }

    @Test fun headerRulesMatchExistingParserForMissingCaseInsensitiveAndInvalidLengths() {
        for (head in listOf(
            "RTSP/1.0 200 OK\r\n\r\n",
            "HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n",
            "RTSP/1.0 400 Bad\r\nContent-Length: bad\r\n\r\n",
        )) {
            val result = RtspStreamDecoder().offer(head.toByteArray()).single()
            assertTrue(result.body.isEmpty())
            assertEquals(RtspStreamDecoder.parseHead(result.head).first, result.firstLine)
        }
    }
}

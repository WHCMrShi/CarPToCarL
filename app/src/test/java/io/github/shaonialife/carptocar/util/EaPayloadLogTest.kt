package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EaPayloadLogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun recordsCompleteBodyAndKeepsFramesSeparateAcrossStarts() {
        val directory = temporaryFolder.root
        EaPayloadLog.attach(directory, "test-one")
        val bodySize = 8193
        val body = ByteArray(bodySize) { 0xab.toByte() }
        EaPayloadLog.record("first", 3, 42, byteArrayOf(1, 2) + body)
        EaPayloadLog.attach(directory, "test-two")
        EaPayloadLog.record("second", 3, 43, byteArrayOf(0x80.toByte()))
        val lines = directory.resolve("ea_payloads.hex.log").readLines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].endsWith("capture-start revision=test-one"))
        assertTrue(lines[1].contains("connection=first linkSession=3 seq=42 session=258 dataBytes=$bodySize"))
        assertEquals("ab".repeat(bodySize), lines[1].substringAfter("bodyHex="))
        assertTrue(lines[2].endsWith("capture-start revision=test-two"))
        assertTrue(lines[3].endsWith("malformed missing-session-id rawBytes=1 rawHex=80"))
    }
}

package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogFileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun missingQueuedMarkerDoesNotMixPreviousProcessIntoCurrentExport() {
        val file = temp.newFile()
        file.writeText("${DebugFileLog.SESSION_MARKER}\nprevious process\n")
        val log = SessionLogFile(file, DebugFileLog.SESSION_MARKER)
        log.write(listOf("latest\n"), 2000) // 启动标记和早期行已被队列淘汰。
        val current = DebugFileLog.currentSessionSlice(file.readText())
        assertTrue(current.contains("latest"))
        assertTrue(current.contains("2000"))
        assertFalse(current.contains("previous process"))
    }

    @Test
    fun rotationRestoresMarkerAndKeepsCurrentBatch() {
        val file = temp.newFile()
        val log = SessionLogFile(file, DebugFileLog.SESSION_MARKER, maxBytes = 10)
        log.write(listOf("old batch\n"), 0)
        log.write(listOf("new batch\n"), 0)
        val current = DebugFileLog.currentSessionSlice(file.readText())
        assertTrue(current.contains("new batch"))
        assertFalse(current.contains("old batch"))
    }
}

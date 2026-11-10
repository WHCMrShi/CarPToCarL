package io.github.shaonialife.carptocar.util

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashEvidenceTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun evidenceSurvivesNewStoreAndIncludesCauseAndBuild() {
        val error = IllegalStateException("outer", IllegalArgumentException("inner"))
        CrashEvidence(directory.root).record(Thread("fixture"), error, "build-123")
        val saved = CrashEvidence(directory.root).read()!!
        assertTrue(saved.contains("build-123")); assertTrue(saved.contains("fixture"))
        assertTrue(saved.contains("Caused by: java.lang.IllegalArgumentException: inner"))
        CrashEvidence(directory.root).record(Thread.currentThread(), RuntimeException("latest"), "build-456")
        assertTrue(CrashEvidence(directory.root).read()!!.contains("build-456"))
    }
    @Test fun longExceptionIsBounded() {
        CrashEvidence(directory.root).record(Thread.currentThread(), RuntimeException("x".repeat(200_000)), "test")
        assertTrue(CrashEvidence(directory.root).read()!!.length <= 65_536)
    }
    @Test fun failedDiskWriteStillDelegatesOriginalErrorAndThread() {
        val error = RuntimeException("failure")
        val thread = Thread.currentThread()
        var calls = 0
        EvidenceExceptionHandler({ _, _ -> throw java.io.IOException("full") }, { actualThread, actualError ->
            assertSame(thread, actualThread); assertSame(error, actualError); calls++
        }).uncaughtException(thread, error)
        assertEquals(1, calls)
    }
}

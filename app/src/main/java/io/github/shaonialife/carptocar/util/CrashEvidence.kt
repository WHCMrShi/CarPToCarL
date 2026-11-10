package io.github.shaonialife.carptocar.util

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/** Separate synchronous evidence survives process restart and normal log rotation. */
internal class CrashEvidence(private val directory: File) {
    private val target get() = File(directory, "last_uncaught_exception.txt")

    fun record(thread: Thread, error: Throwable, build: String) {
        val bounded = object : Writer() {
            val text = StringBuilder()
            override fun write(chars: CharArray, offset: Int, length: Int) {
                val count = minOf(length, 65_536 - text.length)
                if (count > 0) text.append(chars, offset, count)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        PrintWriter(bounded).use {
            it.println("Uncaught exception: ${Date()} build=$build thread=${thread.name}")
            error.printStackTrace(it)
        }
        val temporary = File(directory, "last_uncaught_exception.tmp")
        FileOutputStream(temporary).use {
            it.write(bounded.text.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun read(): String? = target.takeIf { it.isFile && it.length() <= 262_144 }?.readText(Charsets.UTF_8)
}

internal class EvidenceExceptionHandler(
    private val record: (Thread, Throwable) -> Unit,
    private val terminate: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val first = AtomicBoolean(true)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (first.compareAndSet(true, false)) record(thread, error)
        } catch (_: Throwable) {
            // Disk failure must not suppress Android's original fatal-exception handler.
        } finally {
            terminate(thread, error)
        }
    }
}

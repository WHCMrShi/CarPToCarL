package io.github.shaonialife.carptocar.util

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date

/** Independent of the asynchronous session log. Existing installations keep the same evidence path. */
internal class FatalErrorStore(private val directory: File) {
    private val file get() = File(directory, "last_uncaught_exception.txt")

    fun save(thread: Thread, error: Throwable, revision: String) {
        val text = StringBuilder()
        val sink = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                val remaining = MAX_CHARS - text.length
                if (remaining > 0) text.append(chars, offset, minOf(length, remaining))
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        PrintWriter(sink).use { writer ->
            writer.println("FATAL: time=${Date()} revision=$revision thread=${thread.name}")
            error.printStackTrace(writer)
        }
        if (text.length == MAX_CHARS) text.append("\n[TRUNCATED]\n")
        val temp = File(directory, "last_uncaught_exception.tmp")
        FileOutputStream(temp).use { output ->
            output.write(text.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun read(): String? = file.takeIf { it.isFile && it.length() <= 4L * MAX_CHARS + 64 }?.readText()

    companion object { const val MAX_CHARS = 65_536 }
}

/** Other crashing threads wait for the first writer before any of them invokes process termination. */
internal class FatalErrorHandler(
    private val persist: (Thread, Throwable) -> Unit,
    private val delegate: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val lock = Any()
    private var recorded = false

    override fun uncaughtException(thread: Thread, error: Throwable) {
        synchronized(lock) {
            if (!recorded) {
                recorded = true
                try { persist(thread, error) } catch (_: Throwable) { /* Terminate even on disk failure. */ }
            }
        }
        delegate(thread, error)
    }
}

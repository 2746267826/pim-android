package com.pim.app.forensics

import android.content.Context
import android.util.Log
import com.pim.app.mobile.diagnostics.DiagnosticRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Captures a compact, redacted Java stack before delegating to Android's
 * existing uncaught-exception handler.
 */
@Singleton
class CrashForensics @Inject constructor(
    private val store: CrashTraceStore
) {
    private val installLock = Any()
    private var installed = false

    fun install() {
        synchronized(installLock) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(delegatingHandler(previous))
            installed = true
        }
    }

    internal fun delegatingHandler(
        delegate: Thread.UncaughtExceptionHandler?
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
        capture(thread, throwable)
        if (
            delegate != null &&
            delegate !== Thread.getDefaultUncaughtExceptionHandler()
        ) {
            delegate.uncaughtException(thread, throwable)
        } else {
            // There is no safe fallback that can keep the process alive.
            throw throwable
        }
    }

    internal fun capture(
        thread: Thread,
        throwable: Throwable
    ) {
        try {
            store.record(thread, throwable)
        } catch (captureFailure: Throwable) {
            // Never hide the original crash or replace it with a capture error.
            Log.e(TAG, "Failed to persist crash trace", captureFailure)
        }
    }

    companion object {
        private const val TAG = "PimCrashForensics"
    }
}

@Singleton
class CrashTraceStore internal constructor(
    private val redactor: DiagnosticRedactor,
    private val traceFile: File,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        redactor: DiagnosticRedactor
    ) : this(
        redactor = redactor,
        traceFile = File(context.filesDir, "forensics/crash-traces.jsonl"),
        nowMillis = System::currentTimeMillis
    )

    fun record(thread: Thread, throwable: Throwable): Boolean = synchronized(lock) {
        val file = traceFile
        val now = nowMillis()
        val stack = renderCauses(throwable)
        val record = JSONObject()
            .put("version", 1)
            .put("occurredAtUtc", now)
            .put("threadName", redactor.redactText(thread.name).take(MAX_THREAD_CHARS))
            .put("threadId", thread.id)
            .put(
                "exceptionType",
                redactor.redactText(throwable::class.java.name).take(MAX_TYPE_CHARS)
            )
            .put(
                "message",
                redactor.redactText(throwable.message.orEmpty()).take(MAX_MESSAGE_CHARS)
            )
            .put("stackTrace", stack.value)
            .put("stackTraceTruncated", stack.truncated)
        val line = record.toString()
        require(redactor.findCredentialLeaks(line).isEmpty()) {
            "Credential leak in crash trace"
        }

        file.parentFile?.mkdirs()
        val existing = readTailLines(MAX_RECORDS - 1, MAX_FILE_BYTES)
        val kept = ArrayDeque<String>()
        var keptBytes = 0
        for (candidate in (existing + line).takeLast(MAX_RECORDS).asReversed()) {
            val candidateBytes = candidate.toByteArray(Charsets.UTF_8).size + 1
            if (keptBytes + candidateBytes > MAX_FILE_BYTES && kept.isNotEmpty()) break
            kept.addFirst(candidate)
            keptBytes += candidateBytes
        }
        val finalBytes = kept.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(finalBytes)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: UnsupportedOperationException) {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        true
    }

    fun readForExport(): String = synchronized(lock) {
        readTailLines(MAX_RECORDS, MAX_FILE_BYTES)
            .joinToString("\n", postfix = "\n")
    }

    fun count(): Int = synchronized(lock) {
        readTailLines(MAX_RECORDS, MAX_FILE_BYTES).size
    }

    fun clear(): Boolean = synchronized(lock) {
        val tmp = File(traceFile.parentFile, "${traceFile.name}.tmp")
        val traceRemoved = !traceFile.exists() || traceFile.delete()
        val tmpRemoved = !tmp.exists() || tmp.delete()
        traceRemoved && tmpRemoved
    }

    private fun readTailLines(maxRecords: Int, maxBytes: Int): List<String> {
        val file = traceFile
        if (!file.isFile || maxRecords <= 0 || maxBytes <= 0) return emptyList()
        return RandomAccessFile(file, "r").use { input ->
            val start = (input.length() - maxBytes.toLong()).coerceAtLeast(0L)
            input.seek(start)
            val bytes = ByteArray((input.length() - start).coerceAtMost(maxBytes.toLong()).toInt())
            input.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            val completeText = if (start > 0L) text.substringAfter('\n', "") else text
        completeText.lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(maxRecords)
                .toList()
        }
    }

    private fun renderCauses(root: Throwable): RenderedStack {
        val builder = StringBuilder()
        var current: Throwable? = root
        var causeDepth = 0
        var truncated = false
        while (current != null && causeDepth < MAX_CAUSE_DEPTH) {
            if (causeDepth > 0) builder.append("\nCaused by: ")
            builder.append(current::class.java.name)
            current.message?.takeIf { it.isNotBlank() }?.let {
                builder.append(": ").append(it.take(MAX_MESSAGE_CHARS))
            }
            builder.append('\n')
            val frames = current.stackTrace
            val limit = minOf(frames.size, MAX_STACK_FRAMES)
            for (index in 0 until limit) {
                builder.append("\tat ").append(frames[index]).append('\n')
                if (builder.length >= MAX_STACK_CHARS) break
            }
            if (frames.size > limit) truncated = true
            if (builder.length >= MAX_STACK_CHARS) {
                truncated = true
                break
            }
            current = current.cause
            causeDepth++
        }
        if (current?.cause != null) truncated = true
        val redacted = redactor.redactText(builder.toString())
        return RenderedStack(
            value = redacted.take(MAX_STACK_CHARS),
            truncated = truncated || redacted.length > MAX_STACK_CHARS
        )
    }

    private data class RenderedStack(
        val value: String,
        val truncated: Boolean
    )

    companion object {
        private const val MAX_RECORDS = 12
        private const val MAX_FILE_BYTES = 192 * 1024
        private const val MAX_STACK_CHARS = 24_000
        private const val MAX_STACK_FRAMES = 80
        private const val MAX_CAUSE_DEPTH = 6
        private const val MAX_MESSAGE_CHARS = 500
        private const val MAX_TYPE_CHARS = 160
        private const val MAX_THREAD_CHARS = 160
    }
}

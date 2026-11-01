package com.pim.app.forensics

import com.pim.app.mobile.diagnostics.DiagnosticRedactor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashForensicsTest {
    private val redactor = DiagnosticRedactor()

    @Test
    fun crashTraceStoreBoundsHistoryRedactsSecretsAndKeepsCauseChain() {
        val dir = Files.createTempDirectory("crash-traces-test").toFile()
        try {
            val store = CrashTraceStore(redactor, File(dir, "crash-traces.jsonl")) { 123L }
            repeat(14) { index ->
                val root = IllegalStateException("token=secret-$index")
                root.stackTrace = arrayOf(StackTraceElement("pkg.Root", "run", "Root.kt", index))
                val cause = IllegalArgumentException("password=hidden")
                cause.stackTrace = arrayOf(StackTraceElement("pkg.Cause", "work", "Cause.kt", 7))
                root.initCause(cause)

                assertTrue(store.record(Thread.currentThread(), root))
            }

            val lines = store.readForExport().lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(12, store.count())
            assertEquals(12, lines.size)
            assertTrue(lines.all { redactor.findCredentialLeaks(it).isEmpty() })
            val last = JSONObject(lines.last())
            assertTrue(last.getString("stackTrace").contains("Caused by"))
            assertTrue(last.getString("stackTrace").contains("[REDACTED]"))
            assertEquals(123L, last.getLong("occurredAtUtc"))
            assertTrue(File(dir, "crash-traces.jsonl").length() <= 192L * 1024L)
            assertTrue(store.clear())
            assertEquals(0, store.count())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun syntheticHandlerPersistsTraceThenDelegatesToTheExistingHandler() {
        val dir = Files.createTempDirectory("crash-handler-test").toFile()
        try {
            val store = CrashTraceStore(
                redactor = redactor,
                traceFile = File(dir, "crash-traces.jsonl")
            )
            val invoked = AtomicBoolean(false)
            val existing = Thread.UncaughtExceptionHandler { _, _ -> invoked.set(true) }
            val handler = CrashForensics(store).delegatingHandler(existing)
            val synthetic = IllegalStateException("synthetic-uncaught")

            handler.uncaughtException(Thread.currentThread(), synthetic)

            assertTrue(invoked.get())
            assertEquals(1, store.count())
            assertTrue(store.readForExport().contains("synthetic-uncaught"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun traceReaderBoundsInputRedactsCredentialsAndReportsFailures() {
        val credentialText = "Authorization: Bearer abc.def\n" + "x".repeat(ExitTraceReader.MAX_BYTES)
        val result = ExitTraceReader.read(redactor) {
            ByteArrayInputStream(credentialText.toByteArray())
        }
        assertTrue(result.truncated)
        val text = result.text!!
        assertTrue(text.length <= ExitTraceReader.MAX_CHARS)
        assertTrue(redactor.findCredentialLeaks(text).isEmpty())

        val unavailable = ExitTraceReader.read(redactor) { null }
        assertNull(unavailable.text)
        assertEquals("trace-unavailable", unavailable.failureReason)

        val failed = ExitTraceReader.read(redactor) {
            object : InputStream() {
                override fun read(): Int = throw SecurityException("trace denied")
            }
        }
        assertNull(failed.text)
        assertTrue(failed.failureReason!!.contains("SecurityException"))
    }

    @Test
    fun exitPayloadCarriesOnlyAvailableBoundedTraceAndFailureMetadata() {
        val payload = JSONObject(
            ForensicPayloads.processExit(
                reason = ProcessExitReasons.CRASH,
                occurredAtMillis = 10L,
                importance = 100,
                pssKb = null,
                rssKb = null,
                description = null,
                exitTrace = "java.lang.IllegalStateException",
                exitTraceTruncated = true,
                exitTraceFailureReason = null,
                inference = null,
                context = ForensicContext()
            )
        )
        assertEquals("java.lang.IllegalStateException", payload.getString("exitTrace"))
        assertTrue(payload.getBoolean("exitTraceTruncated"))
        assertFalse(payload.has("description"))
        assertFalse(payload.has("exitTraceFailureReason"))
    }
}

package com.pim.app.mobile.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.data.AppDatabase
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettings
import com.pim.app.settings.TrackingSettingsStore
import com.pim.app.status.ConnectionProbeStore
import com.pim.app.status.PermissionStatusSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticExportStreamingBoundsTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settingsStore: TrackingSettingsStore
    private lateinit var logRepository: StructuredLogRepository
    private lateinit var probeStore: ConnectionProbeStore

    private val logDir: File get() = File(context.filesDir, "logs")
    private val exportDir: File get() = File(context.filesDir, "diagnostics/exports")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = context.getSharedPreferences("streaming_export_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settingsStore = TrackingSettingsStore(prefs)
        settingsStore.write(TrackingSettings.defaults())
        logRepository = StructuredLogRepository(context, settingsStore) { 1_000_000L }
        val probePrefs = context.getSharedPreferences("streaming_export_probe", Context.MODE_PRIVATE)
        probePrefs.edit().clear().commit()
        probeStore = ConnectionProbeStore(probePrefs, Json { ignoreUnknownKeys = true })
        logDir.deleteRecursively()
        exportDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        logDir.deleteRecursively()
        exportDir.deleteRecursively()
    }

    @Test
    fun multiFileBudgetProducesValidPartialArchiveAndExactByteAccounting() = runTest {
        val newest = buildLogLines(tag = "newest", count = 8, width = 40)
        val older = buildLogLines(tag = "older", count = 8, width = 40)
        writeLog("mobile-2026-10-30.jsonl", newest)
        writeLog("mobile-2026-10-29.jsonl", older)

        val repo = createRepo(maxLineBytes = 96, maxFileBytes = 600, maxTotalBytes = 700)
        val result = repo.export(includeRecentLocations = false)

        assertTrue(result.isPartial)
        assertTrue(result.summary.contains("不完整"))
        ZipFile(result.file).use { zip ->
            val manifest = JSONObject(zip.readEntry("manifest.json"))
            val logExport = manifest.getJSONObject("logExport")
            assertEquals("partial", logExport.getString("completeness"))
            assertEquals(96L, logExport.getJSONObject("limits").getLong("maxLineBytes"))
            assertEquals(600L, logExport.getJSONObject("limits").getLong("maxFileBytes"))
            assertEquals(700L, logExport.getJSONObject("limits").getLong("maxTotalBytes"))
            assertEquals(0, logExport.getInt("omittedFileCount"))
            assertFalse(logExport.has("omittedLines"))

            val files = logExport.getJSONArray("files")
            assertEquals(2, files.length())
            assertEquals(
                (newest.toByteArray().size + older.toByteArray().size).toLong(),
                logExport.getLong("includedBytes") + logExport.getLong("suppressedBytes")
            )
            assertTrue(logExport.getLong("suppressedBytes") > 0L)
            assertEquals(2, zip.entries().asSequence().count { it.name.startsWith("logs/") })
        }
    }

    @Test
    fun oversizedAndIncompleteLinesNeverExportRawPrefixOrSuffix() = runTest {
        val secretPrefix = "Bearer " + "S".repeat(200)
        val content = """{"message":"$secretPrefix tail-secret"}""" + "\n" +
            """{"message":"password=visible-secret"}""" + "\n"
        writeLog("mobile-2026-10-30.jsonl", content)

        val repo = createRepo(maxLineBytes = 64, maxFileBytes = 4096, maxTotalBytes = 4096)
        val result = repo.export(includeRecentLocations = false)

        assertTrue(result.isPartial)
        ZipFile(result.file).use { zip ->
            val entry = zip.readEntry("logs/mobile-2026-10-30.jsonl")
            assertFalse(entry.contains("SSSSSSSS"))
            assertFalse(entry.contains("tail-secret"))
            assertFalse(entry.contains("visible-secret"))
            assertTrue(entry.contains("exportOmission"))
            assertTrue(entry.contains("[REDACTED]"))

            val logExport = JSONObject(zip.readEntry("manifest.json")).getJSONObject("logExport")
            assertEquals(1, logExport.getInt("oversizedLineCount"))
            assertEquals("partial", logExport.getString("completeness"))
            assertEquals(
                content.toByteArray(Charsets.UTF_8).size.toLong(),
                logExport.getJSONArray("files").let { files ->
                    val file = files.getJSONObject(0)
                    file.getLong("includedBytes") + file.getLong("suppressedBytes")
                }
            )
        }
    }

    @Test
    fun totalBudgetFairlySamplesEachFileAndReportsHeadTailStrategy() = runTest {
        val newest = """{"message":"${"n".repeat(150)}"}""" + "\n"
        val older = """{"message":"${"o".repeat(100)}"}""" + "\n"
        writeLog("mobile-2026-10-30.jsonl", newest)
        writeLog("mobile-2026-10-29.jsonl", older)

        val repo = createRepo(maxLineBytes = 512, maxFileBytes = 1024, maxTotalBytes = 128)
        val result = repo.export(includeRecentLocations = false)

        assertTrue(result.isPartial)
        ZipFile(result.file).use { zip ->
            val logExport = JSONObject(zip.readEntry("manifest.json")).getJSONObject("logExport")
            assertEquals(0, logExport.getInt("omittedFileCount"))
            val files = logExport.getJSONArray("files")
            assertEquals(2, files.length())
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                assertTrue(item.getLong("allocatedBytes") > 0L)
                assertEquals("head-tail", item.getString("strategy"))
                assertTrue(item.getLong("omittedMiddleBytes") > 0L)
            }
            val logNames = zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("logs/") }
                .toList()
            assertEquals(2, logNames.size)
            assertTrue(logNames.all { zip.readEntry(it).contains("sample-gap") })
            assertTrue(
                logExport.getJSONArray("suppressionReasons").toString().contains("total-limit")
            )
        }
    }

    @Test
    fun fullLogReadStopsAtSnapshotBoundaryAfterActiveFileKeepsGrowing() = runTest {
        val original = buildLogLines(tag = "stable", count = 12, width = 48)
        writeLog("mobile-2026-10-30.jsonl", original)
        val originalBytes = original.toByteArray(Charsets.UTF_8).size.toLong()
        val appended = buildLogLines(tag = "secret-growth", count = 4_000, width = 128)

        val repo = createRepo(
            maxLineBytes = 512,
            maxFileBytes = 2_000_000,
            maxTotalBytes = 2_000_000,
            afterLogSnapshot = {
                File(logDir, "mobile-2026-10-30.jsonl")
                    .appendText(appended, Charsets.UTF_8)
            }
        )
        val result = repo.export(includeRecentLocations = false)

        ZipFile(result.file).use { zip ->
            val entry = zip.readEntry("logs/mobile-2026-10-30.jsonl")
            assertFalse(entry.contains("secret-growth"))
            val logExport = JSONObject(zip.readEntry("manifest.json")).getJSONObject("logExport")
            val file = logExport.getJSONArray("files").getJSONObject(0)
            assertEquals("full", file.getString("strategy"))
            assertEquals(originalBytes, file.getLong("originalBytes"))
            assertTrue(file.getLong("readBytes") <= originalBytes)
            assertTrue(file.getLong("includedBytes") <= originalBytes)
            assertTrue(file.getJSONArray("suppressionReasons").toString().contains("file-changed"))
        }
    }

    @Test
    fun sampledLogReadStopsAtSnapshotBoundaryAfterAppendAndTailRotation() = runTest {
        val original = buildLogLines(tag = "original", count = 200, width = 80)
        writeLog("mobile-2026-10-30.jsonl", original)
        val originalBytes = original.toByteArray(Charsets.UTF_8).size.toLong()
        val replacement = buildLogLines(tag = "replacement-secret", count = 4_000, width = 128)

        val repo = createRepo(
            maxLineBytes = 512,
            maxFileBytes = 8_192,
            maxTotalBytes = 2_048,
            afterLogSnapshot = {
                val file = File(logDir, "mobile-2026-10-30.jsonl")
                val rotated = File(logDir, "mobile-2026-10-30.jsonl.rotated")
                file.renameTo(rotated)
                file.writeText(replacement, Charsets.UTF_8)
            }
        )
        val result = repo.export(includeRecentLocations = false)

        ZipFile(result.file).use { zip ->
            val entry = zip.readEntry("logs/mobile-2026-10-30.jsonl")
            assertTrue(entry.isNotBlank())
            val logExport = JSONObject(zip.readEntry("manifest.json")).getJSONObject("logExport")
            val file = logExport.getJSONArray("files").getJSONObject(0)
            assertEquals("head-tail", file.getString("strategy"))
            assertEquals(originalBytes, file.getLong("originalBytes"))
            assertTrue(file.getLong("readBytes") <= file.getLong("allocatedBytes"))
            assertTrue(file.getLong("readBytes") <= originalBytes)
            assertTrue(file.getJSONArray("suppressionReasons").toString().contains("file-changed"))
        }
    }

    @Test
    fun truncatedTailCannotReadPastSnapshotOrAllocatedBudget() = runTest {
        val original = buildLogLines(tag = "truncate", count = 200, width = 80)
        writeLog("mobile-2026-10-30.jsonl", original)

        val repo = createRepo(
            maxLineBytes = 512,
            maxFileBytes = 8_192,
            maxTotalBytes = 2_048,
            afterLogSnapshot = {
                File(logDir, "mobile-2026-10-30.jsonl").writeText("", Charsets.UTF_8)
            }
        )
        val result = repo.export(includeRecentLocations = false)

        ZipFile(result.file).use { zip ->
            val logExport = JSONObject(zip.readEntry("manifest.json")).getJSONObject("logExport")
            val file = logExport.getJSONArray("files").getJSONObject(0)
            assertTrue(file.getLong("readBytes") <= file.getLong("allocatedBytes"))
            assertTrue(file.getLong("readBytes") <= file.getLong("originalBytes"))
            assertTrue(file.getJSONArray("suppressionReasons").toString().contains("file-changed"))
        }
    }

    @Test
    fun boundedInputStreamReportsActualBytesAndNeverConsumesBeyondLimit() {
        var produced = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                produced++
                return 'x'.code
            }
        }
        val bounded = BoundedInputStream(endless, limit = 37)
        val buffer = ByteArray(128)
        var total = 0
        while (true) {
            val read = bounded.read(buffer)
            if (read < 0) break
            total += read
        }

        assertEquals(37, total)
        assertEquals(37L, bounded.bytesRead)
        assertEquals(37L, produced)
        assertTrue(bounded.reachedLimit)
    }

    @Test
    fun targetWriteFailureKeepsOriginalLogsAndReturnsExplicitFailure() = runTest {
        val content = """{"message":"keep-me"}""" + "\n"
        writeLog("mobile-2026-10-30.jsonl", content)
        val original = File(logDir, "mobile-2026-10-30.jsonl")

        val repo = createRepo(
            maxLineBytes = 128,
            maxFileBytes = 1024,
            maxTotalBytes = 1024,
            publish = { _, _ -> throw IOException("destination unavailable") }
        )
        try {
            repo.export(includeRecentLocations = false)
            throw AssertionError("Expected export failure")
        } catch (error: DiagnosticExportException) {
            assertEquals("EXPORT_FAILED", error.code)
        }

        assertEquals(content, original.readText())
        assertEquals(0, exportDir.listFiles()?.count { it.name.endsWith(".tmp") } ?: 0)
    }

    @Test
    fun cancellationDuringLogReadDoesNotPublishOrDeleteSourceLog() = runBlocking {
        val content = buildLargeLog(targetBytes = 8_000_000)
        writeLog("mobile-2026-10-30.jsonl", content)
        val original = File(logDir, "mobile-2026-10-30.jsonl")
        val repo = createRepo(
            maxLineBytes = 8192,
            maxFileBytes = 12_000_000,
            maxTotalBytes = 12_000_000
        )

        val exportJob = launch(Dispatchers.IO) {
            repo.export(includeRecentLocations = false)
        }
        var observedTmpOutput = false
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (exportJob.isActive && System.nanoTime() < deadline) {
                val tmp = exportDir.listFiles()?.firstOrNull { it.name.endsWith(".tmp") }
                if (tmp != null) {
                    observedTmpOutput = true
                    Thread.sleep(200)
                    break
                }
                Thread.sleep(10)
            }
        }

        assertTrue("Expected cancellation after bounded export output started", observedTmpOutput)
        assertTrue(exportJob.isActive)
        withTimeout(15_000L) {
            exportJob.cancelAndJoin()
        }
        assertTrue(exportJob.isCancelled)

        assertEquals(content, original.readText())
        assertEquals(0, exportDir.listFiles()?.count { it.name.endsWith(".zip") } ?: 0)
        assertEquals(0, exportDir.listFiles()?.count { it.name.endsWith(".tmp") } ?: 0)
    }

    @Test
    fun snapshotRefsCaptureLengthsWithoutContent() = runTest {
        val content = """{"message":"${"x".repeat(80_000)}"}""" + "\n"
        writeLog("mobile-2026-10-30.jsonl", content)

        val refs = logRepository.snapshotFileRefs()

        assertEquals(1, refs.size)
        assertEquals("mobile-2026-10-30.jsonl", refs.single().fileName)
        assertEquals(content.toByteArray().size.toLong(), refs.single().lengthBytes)
    }

    private fun buildLogLines(tag: String, count: Int, width: Int): String =
        buildString {
            repeat(count) { index ->
                append("""{"message":"$tag-$index-${"x".repeat(width)}"}""")
                append('\n')
            }
        }

    private fun buildLargeLog(targetBytes: Int): String = buildString {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        var state = 17L
        while (length < targetBytes) {
            append("""{"message":"""")
            repeat(4096) {
                state = (state * 1_103_515_245L + 12_345L) and 0x7fffffff
                append(alphabet[(state % alphabet.length).toInt()])
            }
            append("\"}\n")
        }
    }

    private fun writeLog(name: String, content: String) {
        logDir.mkdirs()
        File(logDir, name).writeText(content, Charsets.UTF_8)
    }

    private fun createRepo(
        maxLineBytes: Int,
        maxFileBytes: Long,
        maxTotalBytes: Long,
        publish: ((File, File) -> Unit)? = null,
        afterLogSnapshot: () -> Unit = {}
    ): DiagnosticExportRepository = DiagnosticExportRepository(
        context = context,
        db = db,
        structuredLogRepository = logRepository,
        trackingSettingsStore = settingsStore,
        redactor = DiagnosticRedactor(),
        connectionProbeStore = probeStore,
        permissionSnapshot = {
            PermissionStatusSnapshot(
                notificationGranted = true,
                preciseLocationGranted = true,
                backgroundLocationGranted = true,
                usageAccessGranted = true,
                activityRecognitionGranted = true,
                batteryOptimizationGranted = true
            )
        },
        serviceRunning = { true },
        dispatcher = Dispatchers.IO,
        availableBytes = { Long.MAX_VALUE },
        publish = publish ?: { tmp, final ->
            Files.move(tmp.toPath(), final.toPath())
            Unit
        },
        maxLogLineBytes = maxLineBytes,
        maxLogFileBytes = maxFileBytes,
        maxLogTotalBytes = maxTotalBytes,
        afterLogSnapshot = afterLogSnapshot
    )

    private fun ZipFile.readEntry(name: String): String =
        getInputStream(getEntry(name)).reader(Charsets.UTF_8).readText()
}

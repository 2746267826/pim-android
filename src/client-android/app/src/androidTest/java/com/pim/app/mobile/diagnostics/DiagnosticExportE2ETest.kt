package com.pim.app.mobile.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.KeyEvent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.data.AppDatabase
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import com.pim.app.status.ConnectionProbeStore
import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.ui.status.DiagnosticShareLauncher
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real repository/ZIP/FileProvider integration using synthetic logs only.
 *
 * The manifest assertions follow the fixed `scratch/android-export/schema.md`
 * envelope and the `logExport` completeness/limits block.
 *
 * Repository export runs against a ContextWrapper whose `filesDir` is an owned,
 * unique cache directory. The FileProvider phase copies the resulting synthetic
 * ZIP into the real allowed exports directory under a unique QA name; it never
 * exposes or deletes pre-existing exports or logs.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticExportE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun realRepositoryWritesValidZipAndRedactsSyntheticCredentialMarkers() = runBlocking {
        val isolatedRoot = isolatedRoot()
        val isolatedContext = IsolatedFilesContext(context, isolatedRoot)
        val db = openDatabase()
        createSyntheticLogs(isolatedContext)
        val exportNow = 1_900_000_000_000L
        val output = File(isolatedContext.filesDir, "diagnostics/exports/pim-diagnostics-$exportNow.zip")
        output.delete()
        try {
            val repository = newRepository(isolatedContext, db, exportNow, { Long.MAX_VALUE / 4 })
            val result = repository.export(includeRecentLocations = false)

            assertTrue(result.file.isFile)
            assertTrue(result.file.canonicalPath.startsWith(isolatedRoot.canonicalPath))
            assertEquals(output, result.file)
            assertTrue("oversized line must be reported", result.isPartial)
            assertTrue(result.summary.isNotBlank())

            ZipFile(result.file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(names.toSet().size, names.size)
                assertTrue("manifest.json" in names)

                val manifest = JSONObject(
                    zip.getInputStream(zip.getEntry("manifest.json"))
                        .readBytes()
                        .toString(StandardCharsets.UTF_8)
                )
                val entries = manifest.getJSONArray("entries")
                val entryNames = (0 until entries.length()).map { entries.getString(it) }.toSet()
                val expectedLogNames = (1..12).map {
                    "logs/mobile-2099-01-${"%02d".format(it)}.jsonl"
                }.toSet()
                assertEquals(
                    expectedLogNames,
                    entryNames.filter { it.startsWith("logs/") }.toSet()
                )
                for (day in 1..12) {
                    assertTrue(
                        "logs/mobile-2099-01-${"%02d".format(day)}.jsonl" in entryNames
                    )
                }

                val logExport = manifest.getJSONObject("logExport")
                assertEquals("partial", logExport.getString("completeness"))
                assertTrue(logExport.getInt("oversizedLineCount") >= 1)
                val limits = logExport.getJSONObject("limits")
                assertEquals(64 * 1024, limits.getInt("maxLineBytes"))
                assertEquals(8L * 1024 * 1024, limits.getLong("maxFileBytes"))
                assertEquals(24L * 1024 * 1024, limits.getLong("maxTotalBytes"))
                assertTrue(logExport.getLong("includedBytes") > 0)
                assertTrue(logExport.getLong("suppressedBytes") > 0)
                assertEquals(0, logExport.getInt("omittedFileCount"))
                assertEquals(0, logExport.getJSONArray("omittedFiles").length())
                val reasons = logExport.getJSONArray("suppressionReasons")
                assertTrue(
                    (0 until reasons.length()).any { reasons.getString(it) == "line-limit" }
                )
                val files = logExport.getJSONArray("files")
                val firstLog = (0 until files.length())
                    .map { files.getJSONObject(it) }
                    .first { it.getString("name") == "logs/mobile-2099-01-01.jsonl" }
                assertEquals(1, firstLog.getInt("oversizedLineCount"))
                assertTrue(firstLog.getLong("suppressedBytes") > 0)
                assertEquals(
                    firstLog.getLong("originalBytes"),
                    firstLog.getLong("includedBytes") + firstLog.getLong("suppressedBytes")
                )
                val firstReasons = firstLog.getJSONArray("suppressionReasons")
                assertTrue(
                    (0 until firstReasons.length()).any {
                        firstReasons.getString(it) == "line-limit"
                    }
                )

                val firstContent = zip.getInputStream(zip.getEntry("logs/mobile-2099-01-01.jsonl"))
                    .readBytes()
                    .toString(StandardCharsets.UTF_8)
                assertTrue(firstContent.contains("exportOmission"))
                assertFalse(firstContent.contains(OVERSIZED_PREFIX))

                for (entry in zip.entries().asSequence()) {
                    val content = zip.getInputStream(entry)
                        .readBytes()
                        .toString(StandardCharsets.UTF_8)
                    for (marker in SECRET_MARKERS) {
                        assertFalse("credential marker leaked in ${entry.name}", content.contains(marker))
                    }
                }
            }
        } finally {
            isolatedRoot.deleteOwnedTree()
            db.close()
        }
    }

    @Test
    fun fileProviderShareAndStorageFailureAreNonDestructive() = runBlocking {
        val isolatedRoot = isolatedRoot()
        val isolatedContext = IsolatedFilesContext(context, isolatedRoot)
        val db = openDatabase()
        createSyntheticLogs(isolatedContext)
        val exportNow = 1_900_000_100_000L
        val output = File(isolatedContext.filesDir, "diagnostics/exports/pim-diagnostics-$exportNow.zip")
        output.delete()
        var shareCopy: File? = null
        try {
            val repository = newRepository(isolatedContext, db, exportNow, { Long.MAX_VALUE / 4 })
            val result = repository.export(includeRecentLocations = false)
            assertTrue(result.file.isFile)
            assertTrue(result.file.canonicalPath.startsWith(isolatedRoot.canonicalPath))
            val originalLength = result.file.length()
            val originalBytes = result.file.readBytes()

            val actualExportDir = File(context.filesDir, "diagnostics/exports").apply { mkdirs() }
            shareCopy = File(
                actualExportDir,
                "qa-diagnostic-e2e-${System.nanoTime()}.zip"
            )
            result.file.copyTo(shareCopy!!, overwrite = true)
            assertEquals(originalLength, shareCopy!!.length())
            assertArrayEquals(originalBytes, shareCopy!!.readBytes())

            val uri = DiagnosticShareLauncher.resolveContentUri(context, shareCopy!!)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            val intent: Intent = DiagnosticShareLauncher.buildShareIntent(context, shareCopy!!)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/zip", intent.type)
            assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            assertNotNull(intent.clipData)

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertNotNull(bytes)
            val readBack = requireNotNull(bytes)
            assertEquals(originalLength, readBack.size.toLong())
            assertArrayEquals(originalBytes, readBack)
            assertTrue(readBack.size > 2 && readBack[0] == 'P'.code.toByte() && readBack[1] == 'K'.code.toByte())

            // Real chooser launch. Back closes the chooser; it must not delete the export.
            val opened = DiagnosticShareLauncher.open(context, shareCopy!!)
            assertTrue("system share chooser must open", opened)
            Thread.sleep(500)
            runCatching {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            Thread.sleep(300)
            assertTrue("share source must survive chooser cancel", shareCopy!!.isFile)
            assertEquals(originalLength, shareCopy!!.length())

            val failureRepository = newRepository(isolatedContext, db, exportNow, { 0L })
            val failure = runCatching {
                failureRepository.export(includeRecentLocations = false)
            }.exceptionOrNull()
            assertTrue(failure is DiagnosticExportException)
            assertEquals("INSUFFICIENT_STORAGE", (failure as DiagnosticExportException).code)
            assertTrue("prior export must survive IO failure", result.file.isFile)
            assertEquals(originalLength, result.file.length())
            assertFalse(File("${output}.tmp").exists())
        } finally {
            shareCopy?.delete()
            isolatedRoot.deleteOwnedTree()
            db.close()
        }
    }

    private fun openDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun newRepository(
        repositoryContext: Context,
        db: AppDatabase,
        exportNow: Long,
        availableBytes: () -> Long
    ): DiagnosticExportRepository {
        val suffix = "${exportNow}_${System.nanoTime()}"
        val tracking = TrackingSettingsStore(
            repositoryContext.getSharedPreferences(
                "pim_tracking_e2e_$suffix",
                Context.MODE_PRIVATE
            )
        )
        val logs = StructuredLogRepository(repositoryContext, tracking)
        val probe = ConnectionProbeStore(
            repositoryContext.getSharedPreferences(
                "pim_probe_e2e_$suffix",
                Context.MODE_PRIVATE
            ),
            Json { ignoreUnknownKeys = true }
        )
        return DiagnosticExportRepository(
            context = repositoryContext,
            db = db,
            structuredLogRepository = logs,
            trackingSettingsStore = tracking,
            redactor = DiagnosticRedactor(),
            connectionProbeStore = probe,
            permissionSnapshot = {
                PermissionStatusSnapshot(
                    notificationGranted = false,
                    preciseLocationGranted = false,
                    backgroundLocationGranted = false,
                    usageAccessGranted = false,
                    activityRecognitionGranted = false,
                    batteryOptimizationGranted = false
                )
            },
            serviceRunning = { false },
            dispatcher = Dispatchers.IO,
            nowMillis = { exportNow },
            availableBytes = availableBytes
        )
    }

    private fun createSyntheticLogs(logContext: Context): List<File> {
        val logDir = File(logContext.filesDir, "logs").apply { mkdirs() }
        return (1..12).map { day ->
            val file = File(logDir, "mobile-2099-01-${"%02d".format(day)}.jsonl")
            val normalLine = JSONObject()
                .put("level", "info")
                .put("tag", "diagnostic-e2e")
                .put(
                    "message",
                    "authorization: Bearer $BEARER_MARKER password=$PASSWORD_MARKER " +
                        "token=$ACCESS_MARKER"
                )
                .toString()
            val oversizedLine = if (day == 1) {
                JSONObject()
                    .put("level", "info")
                    .put("tag", "diagnostic-e2e")
                    .put("message", "$OVERSIZED_PREFIX-" + "x".repeat(256 * 1024))
                    .toString()
            } else {
                null
            }
            file.writeText(
                buildString {
                    append(normalLine)
                    append('\n')
                    if (oversizedLine != null) {
                        append(oversizedLine)
                        append('\n')
                    }
                },
                Charsets.UTF_8
            )
            file
        }
    }

    private fun isolatedRoot(): File =
        File(context.cacheDir, "qa-diagnostic-e2e-${System.nanoTime()}").apply { mkdirs() }

    private class IsolatedFilesContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    }

    private fun File.deleteOwnedTree() {
        if (!exists()) return
        val canonical = canonicalFile
        val cacheRoot = context.cacheDir.canonicalFile
        check(canonical.path.startsWith(cacheRoot.path + File.separator))
        deleteRecursively()
    }

    companion object {
        private const val BEARER_MARKER = "E2E_BEARER_MARKER_7f3e"
        private const val PASSWORD_MARKER = "E2E_PASSWORD_MARKER_91ac"
        private const val ACCESS_MARKER = "E2E_ACCESS_TOKEN_MARKER_52d1"
        private const val OVERSIZED_PREFIX = "E2E_OVERSIZED_RAW_PREFIX_306b"
        private val SECRET_MARKERS = listOf(BEARER_MARKER, PASSWORD_MARKER, ACCESS_MARKER)
    }
}

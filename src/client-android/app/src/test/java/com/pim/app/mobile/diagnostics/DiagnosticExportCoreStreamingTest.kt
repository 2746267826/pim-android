package com.pim.app.mobile.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.pim.app.data.AppDatabase
import com.pim.app.data.ForensicEventEntity
import com.pim.app.data.MobileLocationDroppedDiagnosticEntity
import com.pim.app.data.MobileLocationPointEntity
import com.pim.app.data.MobileSyncBatchEntity
import com.pim.app.forensics.ForensicRetention
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.mobile.sync.LocationUploadStatusUpdates
import com.pim.app.mobile.sync.applyLocationStatusUpdates
import com.pim.app.settings.TrackingSettings
import com.pim.app.settings.TrackingSettingsStore
import com.pim.app.status.ConnectionProbeStore
import com.pim.app.status.PermissionStatusSnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONArray
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
class DiagnosticExportCoreStreamingTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settingsStore: TrackingSettingsStore
    private lateinit var logRepository: StructuredLogRepository
    private lateinit var probeStore: ConnectionProbeStore
    private lateinit var redactor: DiagnosticRedactor

    private val now = 1_900_000_000_000L
    private val rowsPerSection = 4_000
    private val pageSize = 7
    private val padding = "x".repeat(192)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = context.getSharedPreferences("core-stream-prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settingsStore = TrackingSettingsStore(prefs)
        settingsStore.write(TrackingSettings.defaults())
        logRepository = StructuredLogRepository(context, settingsStore) { now }
        val probePrefs = context.getSharedPreferences("core-stream-probe", Context.MODE_PRIVATE)
        probePrefs.edit().clear().commit()
        probeStore = ConnectionProbeStore(probePrefs, Json { ignoreUnknownKeys = true })
        redactor = DiagnosticRedactor()
        File(context.filesDir, "diagnostics/exports").deleteRecursively()
        File(context.filesDir, "logs").deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        File(context.filesDir, "diagnostics/exports").deleteRecursively()
        File(context.filesDir, "logs").deleteRecursively()
    }

    @Test(timeout = 180_000)
    fun highCardinalityCoreEntriesAreCompleteRedactedAndPageStreamed() = runTest(
        timeout = 150.seconds
    ) {
        insertSyntheticRows()

        val repository = createRepository(pageSize = pageSize)

        val result = repository.export(includeRecentLocations = true)

        ZipFile(result.file).use { zip ->
            val droppedReasons = mutableSetOf<String>()
            val droppedTimes = mutableListOf<Long>()
            val forensicKeys = mutableSetOf<String>()
            val forensicTimes = mutableListOf<Long>()
            val locationLatitudes = mutableSetOf<Double>()
            val locationTimes = mutableListOf<Long>()

            val droppedCount = zip.scanJsonLines("dropped-locations.jsonl") { row ->
                droppedReasons += row.getString("reason")
                droppedTimes += row.getLong("recordedAtUtc")
            }
            val forensicCount = zip.scanJsonLines("forensics.jsonl") { row ->
                forensicKeys += row.getString("clientItemKey")
                forensicTimes += row.getLong("occurredAtUtc")
            }
            val locationCount = zip.scanJsonLines("locations.jsonl") { row ->
                locationLatitudes += row.getDouble("latitude")
                locationTimes += row.getLong("recordedAtUtc")
            }

            assertEquals(rowsPerSection, droppedCount)
            assertEquals(rowsPerSection, forensicCount)
            assertEquals(rowsPerSection, locationCount)
            assertEquals(rowsPerSection, result.coordinateCount)

            assertEquals(
                (0 until rowsPerSection).map { "reason-$it" }.toSet(),
                droppedReasons
            )
            assertEquals(
                (0 until rowsPerSection).map { "forensic-$it" }.toSet(),
                forensicKeys
            )
            assertEquals(
                (0 until rowsPerSection).map { it.toDouble() }.toSet(),
                locationLatitudes
            )

            assertNonDecreasing(droppedTimes)
            assertNonDecreasing(forensicTimes)
            assertNonDecreasing(locationTimes)

            val manifest = JSONObject(zip.readText("manifest.json"))
            assertEquals(rowsPerSection, manifest.getInt("coordinateCount"))
            val coreExport = manifest.getJSONObject("coreExport")
            assertEquals(rowsPerSection, coreExport.getInt("droppedLocationCount"))
            assertEquals(rowsPerSection, coreExport.getInt("forensicEventCount"))
            assertEquals(rowsPerSection, coreExport.getInt("recentLocationCount"))
            assertEquals(pageSize, coreExport.getInt("pageSize"))
            assertEquals("complete", manifest.getJSONObject("logExport").getString("completeness"))
            assertEquals(
                setOf(
                    "manifest.json",
                    "status.json",
                    "settings.json",
                    "database-counts.json",
                    "sync-history.json",
                    "dropped-locations.jsonl",
                    "forensics.jsonl",
                    "locations.jsonl"
                ),
                zip.entryNames()
            )
        }
        assertTrue(result.file.isFile)
        assertNoSpoolOrTmpArtifacts()
    }

    @Test(timeout = 60_000)
    fun keysetPaginationPreservesRowsWithIdenticalTimestamps() = runBlocking {
        val at = now - 90_000L
        val keys = (0 until 20).map { index -> "tie-$index" }
        db.withTransaction {
            keys.forEachIndexed { index, key ->
                db.mobileDataDao().insertDroppedLocationDiagnostic(
                    MobileLocationDroppedDiagnosticEntity(
                        recordedAtUtc = at,
                        provider = "provider token=secret-token-$index",
                        accuracyMeters = index.toFloat(),
                        policyMode = "Active",
                        reason = key
                    )
                )
                db.forensicEventDao().insertIgnore(
                    ForensicEventEntity(
                        eventType = "probe",
                        occurredAtUtc = at,
                        clientItemKey = key,
                        payloadJson = """{"index":$index,"token":"secret-token-$index"}"""
                    )
                )
            }
        }

        val repository = createRepository(pageSize = 1)
        val result = repository.export(includeRecentLocations = false)

        ZipFile(result.file).use { zip ->
            val reasons = mutableListOf<String>()
            val forensicKeys = mutableListOf<String>()
            assertEquals(20, zip.scanJsonLines("dropped-locations.jsonl") { row ->
                reasons += row.getString("reason")
            })
            assertEquals(20, zip.scanJsonLines("forensics.jsonl") { row ->
                forensicKeys += row.getString("clientItemKey")
            })
            assertEquals(keys.toSet(), reasons.toSet())
            assertEquals(keys.toSet(), forensicKeys.toSet())
            assertEquals(20, reasons.size)
            assertEquals(20, forensicKeys.size)
        }
        assertNoSpoolOrTmpArtifacts()
    }

    @Test(timeout = 60_000)
    fun exportIncludesRowsOlderThanRetentionWithoutTruncation() = runBlocking {
        insertSyntheticRows(
            count = 3,
            atMillis = now - 31L * 24L * 3600_000L,
            locationAtMillis = now - 60_000L,
            includeOutOfRange = false,
            forensicPrefix = "stale",
            reasonPrefix = "stale-reason",
            latitudeOffset = 0.0
        )

        val repository = createRepository(pageSize = 2)
        val result = repository.export(includeRecentLocations = false)

        ZipFile(result.file).use { zip ->
            val reasons = mutableSetOf<String>()
            val forensicKeys = mutableSetOf<String>()
            assertEquals(3, zip.scanJsonLines("dropped-locations.jsonl") { row ->
                reasons += row.getString("reason")
            })
            assertEquals(3, zip.scanJsonLines("forensics.jsonl") { row ->
                forensicKeys += row.getString("clientItemKey")
            })
            assertEquals(
                setOf("stale-reason-0", "stale-reason-1", "stale-reason-2"),
                reasons
            )
            assertEquals(setOf("stale-0", "stale-1", "stale-2"), forensicKeys)
        }
        assertNoSpoolOrTmpArtifacts()
    }

    @Test(timeout = 60_000)
    fun syncHistoryEntryIsBoundedToLimit() = runBlocking {
        db.withTransaction {
            repeat(103) { index ->
                db.mobileDataDao().insertSyncBatch(
                    MobileSyncBatchEntity(
                        batchId = "batch-$index",
                        entityType = "location",
                        rowCount = index,
                        createdAtUtc = now - 1000L + index
                    )
                )
            }
        }

        val repository = createRepository(pageSize = 2)
        val result = repository.export(includeRecentLocations = false)

        ZipFile(result.file).use { zip ->
            val history = JSONArray(zip.readText("sync-history.json"))
            assertEquals(100, history.length())
            assertEquals(102, history.getJSONObject(0).getInt("rowCount"))
        }
    }

    @Test(timeout = 60_000)
    fun snapshotContainsUnreadPagesDespiteConcurrentDeleteAndExcludesLateInsert() = runBlocking {
        val old = insertSyntheticRows(
            count = 3,
            atMillis = now - 31L * 24L * 3600_000L,
            locationAtMillis = now - 60_000L,
            includeOutOfRange = false,
            forensicPrefix = "old",
            reasonPrefix = "old-reason",
            latitudeOffset = 0.0
        )
        val firstPageSpooled = CountDownLatch(1)
        val releaseSpool = CountDownLatch(1)
        val pausedOnce = AtomicBoolean()
        val repository = createRepository(
            pageSize = 1,
            onCorePageSpooled = { entryName, _, _ ->
                if (entryName == "dropped-locations.jsonl" &&
                    pausedOnce.compareAndSet(false, true)
                ) {
                    firstPageSpooled.countDown()
                    check(releaseSpool.await(15, TimeUnit.SECONDS)) {
                        "Timed out waiting to release core spool"
                    }
                }
            }
        )

        val exportJob = async(Dispatchers.IO) {
            repository.export(includeRecentLocations = true)
        }
        awaitLatch(firstPageSpooled)

        val mutationJob = async(Dispatchers.IO) {
            ForensicRetention(db.forensicEventDao(), db.mobileDataDao(), logRepository)
                .purgeExpired(now)
            applyLocationStatusUpdates(
                db.mobileDataDao(),
                LocationUploadStatusUpdates(
                    syncedIds = old.locationIds,
                    failedIds = emptyList(),
                    failedReason = null,
                    shouldRetry = false
                )
            )
        }
        val insertJob = async(Dispatchers.IO) {
            insertSyntheticRows(
                count = 1,
                atMillis = now - 60_000L,
                includeOutOfRange = false,
                forensicPrefix = "new",
                reasonPrefix = "new-reason",
                latitudeOffset = 100.0
            )
        }
        delay(250)
        val mutationFinishedBeforeSpoolReleased = mutationJob.isCompleted
        val insertFinishedBeforeSpoolReleased = insertJob.isCompleted
        releaseSpool.countDown()

        val result = withTimeout(30_000L) { exportJob.await() }
        withTimeout(30_000L) {
            mutationJob.await()
            insertJob.await()
        }
        println(
            "core snapshot writer measurement: mutationCompleted=$mutationFinishedBeforeSpoolReleased " +
                "insertCompleted=$insertFinishedBeforeSpoolReleased"
        )

        ZipFile(result.file).use { zip ->
            val forensicKeys = mutableSetOf<String>()
            val reasons = mutableSetOf<String>()
            assertEquals(3, zip.scanJsonLines("dropped-locations.jsonl") { row ->
                reasons += row.getString("reason")
            })
            assertEquals(3, zip.scanJsonLines("forensics.jsonl") { row ->
                forensicKeys += row.getString("clientItemKey")
            })
            assertEquals(3, zip.scanJsonLines("locations.jsonl") { })
            assertEquals(
                setOf("old-reason-0", "old-reason-1", "old-reason-2"),
                reasons
            )
            assertEquals(setOf("old-0", "old-1", "old-2"), forensicKeys)
        }
        val counts = db.mobileDataDao().diagnosticDatabaseCounts()
        assertEquals(1L, counts.mobileLocationDroppedDiagnosticsRowCount)
        assertEquals(1L, counts.mobileForensicEventsRowCount)
        assertEquals(1L, counts.mobileLocationPointsRowCount)
        assertNoSpoolOrTmpArtifacts()
    }

    @Test(timeout = 60_000)
    fun cancellationDuringCoreSpoolDoesNotPublishAndCleansOwnedTempFiles() = runBlocking {
        insertSyntheticRows(count = 3, includeOutOfRange = false)
        val firstPageSpooled = CountDownLatch(1)
        val releaseSpool = CountDownLatch(1)
        val pausedOnce = AtomicBoolean()
        val repository = createRepository(
            pageSize = 1,
            onCorePageSpooled = { entryName, _, _ ->
                if (entryName == "dropped-locations.jsonl" &&
                    pausedOnce.compareAndSet(false, true)
                ) {
                    firstPageSpooled.countDown()
                    check(releaseSpool.await(15, TimeUnit.SECONDS)) {
                        "Timed out waiting to cancel core spool"
                    }
                }
            }
        )

        val exportJob = async(Dispatchers.IO) {
            repository.export(includeRecentLocations = true)
        }
        awaitLatch(firstPageSpooled)
        exportJob.cancel()
        releaseSpool.countDown()
        withTimeout(15_000L) { exportJob.cancelAndJoin() }

        assertTrue(exportJob.isCancelled)
        assertNoExportFiles()
        assertEquals(3L, db.mobileDataDao().diagnosticDatabaseCounts().mobileLocationPointsRowCount)
    }

    @Test(timeout = 60_000)
    fun lowSpaceDuringCoreSpoolReturnsExplicitFailureAndCleansEverything() = runBlocking {
        insertSyntheticRows(count = 3, includeOutOfRange = false)
        val availabilityCalls = AtomicInteger()
        val repository = createRepository(
            pageSize = 1,
            availableBytes = {
                if (availabilityCalls.getAndIncrement() == 0) {
                    Long.MAX_VALUE
                } else {
                    0L
                }
            }
        )

        val error = runCatching {
            repository.export(includeRecentLocations = true)
        }.exceptionOrNull()

        assertTrue(error is DiagnosticExportException)
        assertEquals("INSUFFICIENT_STORAGE", (error as DiagnosticExportException).code)
        assertNoExportFiles()
        assertEquals(3L, db.mobileDataDao().diagnosticDatabaseCounts().mobileLocationPointsRowCount)
    }

    @Test(timeout = 60_000)
    fun postSpoolPreflightCountsActualCoreBytesBeforeWritingZip() = runBlocking {
        insertSyntheticRows(
            count = 1,
            includeOutOfRange = false,
            rowPadding = "z".repeat(20_000)
        )
        val spoolFinished = AtomicBoolean()
        val repository = createRepository(
            pageSize = 1,
            availableBytes = {
                if (spoolFinished.get()) 1_060_000L else Long.MAX_VALUE
            },
            onCorePageSpooled = { entryName, _, _ ->
                if (entryName == "locations.jsonl") {
                    spoolFinished.set(true)
                }
            }
        )

        val error = runCatching {
            repository.export(includeRecentLocations = true)
        }.exceptionOrNull()

        assertTrue(error is DiagnosticExportException)
        assertEquals("INSUFFICIENT_STORAGE", (error as DiagnosticExportException).code)
        assertNoExportFiles()
        assertEquals(1L, db.mobileDataDao().diagnosticDatabaseCounts().mobileLocationPointsRowCount)
    }

    @Test(timeout = 60_000)
    fun publishFailureAfterCoreSpoolDoesNotPublishOrLeaveTempFiles() = runBlocking {
        insertSyntheticRows(count = 2, includeOutOfRange = false)
        val repository = createRepository(
            pageSize = 1,
            publish = { _, _ -> throw IOException("simulated publish failure") }
        )

        val error = runCatching {
            repository.export(includeRecentLocations = true)
        }.exceptionOrNull()

        assertTrue(error is DiagnosticExportException)
        assertEquals("EXPORT_FAILED", (error as DiagnosticExportException).code)
        assertNoExportFiles()
        assertEquals(2L, db.mobileDataDao().diagnosticDatabaseCounts().mobileLocationPointsRowCount)
    }

    @Test(timeout = 60_000)
    fun coreSpoolWriteFailureDoesNotPublishAndCleansEverything() = runBlocking {
        insertSyntheticRows(
            count = 1,
            includeOutOfRange = false,
            rowPadding = "w".repeat(20_000)
        )
        val repository = createRepository(
            pageSize = 1,
            openCoreSpoolOutput = { file ->
                FailingOutputStream(FileOutputStream(file), failAfterBytes = 128)
            }
        )

        val error = runCatching {
            repository.export(includeRecentLocations = true)
        }.exceptionOrNull()

        assertTrue(error is DiagnosticExportException)
        assertEquals("EXPORT_FAILED", (error as DiagnosticExportException).code)
        assertNoExportFiles()
        assertEquals(1L, db.mobileDataDao().diagnosticDatabaseCounts().mobileLocationPointsRowCount)
    }

    @Test
    fun clearDiagnosticsRemovesOnlyOwnedStaleCoreSpools() = runBlocking {
        val exportDir = File(context.filesDir, "diagnostics/exports").apply { mkdirs() }
        val ownedSpool = File(exportDir, "pim-diagnostics-123.zip.tmp.spool")
        val nearMissSpool = File(exportDir, "pim-diagnostics-not-owned.zip.tmp.spool")
        val unrelatedDir = File(exportDir, "user-export-work")
        listOf(ownedSpool, nearMissSpool, unrelatedDir).forEach { directory ->
            directory.mkdirs()
            File(directory, "sentinel.txt").writeText("keep", Charsets.UTF_8)
        }

        createRepository(pageSize = 1).clearDiagnostics()

        assertFalse(ownedSpool.exists())
        assertTrue(File(nearMissSpool, "sentinel.txt").isFile)
        assertTrue(File(unrelatedDir, "sentinel.txt").isFile)
    }

    private data class InsertedCoreIds(
        val droppedIds: List<Long>,
        val forensicIds: List<Long>,
        val locationIds: List<Long>
    )

    private suspend fun insertSyntheticRows(
        count: Int = rowsPerSection,
        atMillis: Long = now - 60_000L,
        locationAtMillis: Long = atMillis,
        includeOutOfRange: Boolean = true,
        forensicPrefix: String = "forensic",
        reasonPrefix: String = "reason",
        latitudeOffset: Double = 0.0,
        rowPadding: String = padding
    ): InsertedCoreIds {
        val droppedIds = mutableListOf<Long>()
        val forensicIds = mutableListOf<Long>()
        val locationIds = mutableListOf<Long>()
        db.withTransaction {
            repeat(count) { index ->
                val at = atMillis + (index % 5)
                droppedIds += db.mobileDataDao().insertDroppedLocationDiagnostic(
                    MobileLocationDroppedDiagnosticEntity(
                        recordedAtUtc = at,
                        provider = "provider token=secret-token-$index-$rowPadding",
                        accuracyMeters = index.toFloat(),
                        policyMode = "Active",
                        reason = "$reasonPrefix-$index"
                    )
                )
                forensicIds += db.forensicEventDao().insertIgnore(
                    ForensicEventEntity(
                        eventType = "probe",
                        occurredAtUtc = at,
                        clientItemKey = "$forensicPrefix-$index",
                        payloadJson =
                            """{"index":$index,"token":"secret-token-$index","padding":"$rowPadding"}"""
                    )
                )
                locationIds += db.mobileDataDao().insertLocationPoint(
                    MobileLocationPointEntity(
                        latitude = latitudeOffset + index.toDouble(),
                        longitude = latitudeOffset + index.toDouble(),
                        provider = "provider token=secret-token-$index-$rowPadding",
                        recordedAtUtc = locationAtMillis + (index % 5),
                        source = "probe",
                        collectedAtUtc = locationAtMillis,
                        rawJson = "{}"
                    )
                )
            }

            if (includeOutOfRange) {
                db.mobileDataDao().insertLocationPoint(
                    MobileLocationPointEntity(
                        latitude = -1.0,
                        longitude = -1.0,
                        recordedAtUtc = now - 24L * 3600_000L - 1L,
                        source = "probe",
                        collectedAtUtc = now,
                        rawJson = "{}"
                    )
                )
                db.mobileDataDao().insertLocationPoint(
                    MobileLocationPointEntity(
                        latitude = -2.0,
                        longitude = -2.0,
                        recordedAtUtc = now + 1L,
                        source = "probe",
                        collectedAtUtc = now,
                        rawJson = "{}"
                    )
                )
            }
        }
        return InsertedCoreIds(droppedIds, forensicIds, locationIds)
    }

    private fun createRepository(
        pageSize: Int,
        availableBytes: () -> Long = { Long.MAX_VALUE },
        onCorePageSpooled: (String, Int, Int) -> Unit = { _, _, _ -> },
        publish: ((File, File) -> Unit)? = null,
        openCoreSpoolOutput: (File) -> OutputStream = File::outputStream
    ): DiagnosticExportRepository = DiagnosticExportRepository(
        context = context,
        db = db,
        structuredLogRepository = logRepository,
        trackingSettingsStore = settingsStore,
        redactor = redactor,
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
        nowMillis = { now },
        availableBytes = availableBytes,
        publish = publish ?: { tmp, final ->
            Files.move(tmp.toPath(), final.toPath())
            Unit
        },
        corePageSize = pageSize,
        onCorePageSpooled = onCorePageSpooled,
        openCoreSpoolOutput = openCoreSpoolOutput
    )

    private class FailingOutputStream(
        private val delegate: OutputStream,
        private val failAfterBytes: Int
    ) : OutputStream() {
        private var written = 0

        override fun write(value: Int) {
            if (written >= failAfterBytes) throw IOException("simulated core spool write failure")
            delegate.write(value)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (written + length > failAfterBytes) {
                throw IOException("simulated core spool write failure")
            }
            delegate.write(buffer, offset, length)
            written += length
        }

        override fun close() {
            delegate.close()
        }
    }

    private suspend fun awaitLatch(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(15, TimeUnit.SECONDS))
    }

    private fun assertNoSpoolOrTmpArtifacts() {
        val files = File(context.filesDir, "diagnostics/exports").listFiles().orEmpty()
        assertEquals(
            0,
            files.count {
                it.name.endsWith(".tmp") || it.name.endsWith(".spool")
            }
        )
    }

    private fun assertNoExportFiles() {
        val files = File(context.filesDir, "diagnostics/exports").listFiles().orEmpty()
        assertEquals(
            0,
            files.count {
                it.isFile && (it.name.endsWith(".zip") || it.name.endsWith(".tmp"))
            } + files.count { it.name.endsWith(".spool") }
        )
    }

    private fun ZipFile.scanJsonLines(name: String, capture: (JSONObject) -> Unit): Int {
        var count = 0
        getInputStream(getEntry(name)).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    assertFalse(line.contains("secret-token-"))
                    assertEquals(emptySet<String>(), redactor.findCredentialLeaks(line))
                    capture(JSONObject(line))
                    count++
                }
        }
        return count
    }

    private fun ZipFile.readText(name: String): String =
        getInputStream(getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun ZipFile.entryNames(): Set<String> =
        entries().asSequence().map { it.name }.toSet()

    private fun assertNonDecreasing(values: List<Long>) {
        assertEquals(values.sorted(), values)
    }
}

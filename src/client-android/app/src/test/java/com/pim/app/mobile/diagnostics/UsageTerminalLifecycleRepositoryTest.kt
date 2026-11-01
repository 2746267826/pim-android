package com.pim.app.mobile.diagnostics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileSyncBatchEntity
import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.MobileUsageSummaryEntity
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageTerminalLifecycleRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerFile: File
    private val now = 2_000_000_000_000L
    private val day = 24L * 60L * 60L * 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledgerFile = File(context.filesDir, "forensics/usage-terminal-lifecycle-test-${System.nanoTime()}.json")
        ledgerFile.parentFile?.mkdirs()
        ledgerFile.delete()
    }

    @After
    fun tearDown() {
        db.close()
        ledgerFile.delete()
    }

    @Test
    fun refreshExplainsReclaimableRowsThenDeletesOnlyExpiredRejectedSummaries() = runTest {
        val dao = db.mobileDataDao()
        dao.insertUsageSummaries(
            listOf(
                summary(MobileSyncStatus.REJECTED, "invalid-window", now - 31L * day),
                summary(MobileSyncStatus.REJECTED, "invalid-window", now - 30L * day - 1L),
                summary(MobileSyncStatus.REJECTED, "package-no-longer-installed", now - 2L * day),
                summary(MobileSyncStatus.PENDING, null, now - 40L * day),
                summary(MobileSyncStatus.FAILED, "timeout", now - 40L * day),
                summary(MobileSyncStatus.SYNCING, null, now - 40L * day)
            )
        )
        val repository = repository(dao, pageSize = 1)

        val refreshed = repository.refresh()
        assertEquals(3L, refreshed.rejectedCount)
        assertEquals(2L, refreshed.reclaimableCount)
        assertEquals(0L, refreshed.archivedCount)
        assertEquals("invalid-window", refreshed.reasons.first().reason)
        assertEquals(2L, refreshed.reasons.first().count)
        assertNotNull(refreshed.nextEligibleAtUtc)

        val reclaimed = repository.refreshAndReclaim()
        assertEquals(1L, reclaimed.rejectedCount)
        assertEquals(0L, reclaimed.reclaimableCount)
        assertEquals(2L, reclaimed.archivedCount)
        assertEquals(2L, reclaimed.lastReclaimedReasonCount("invalid-window"))
        assertNull("lastError", reclaimed.lastError)

        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED))
        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.PENDING))
        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.FAILED))
        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.SYNCING))
        assertEquals(
            listOf("package-no-longer-installed"),
            dao.getUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED, limit = 10)
                .map { it.lastError }
        )
        assertEquals(1, dao.getUsageSummariesBySyncStatus(MobileSyncStatus.PENDING, limit = 10).size)
        assertEquals(1, dao.getUsageSummariesBySyncStatus(MobileSyncStatus.FAILED, limit = 10).size)

        val ledger = JSONObject(ledgerFile.readText())
        assertEquals(2L, ledger.getLong("archivedCount"))
        assertEquals(2L, ledger.getLong("lastReclaimedCount"))
        assertEquals(
            2L,
            ledger.getJSONArray("lastReclaimedReasons").getJSONObject(0).getLong("count")
        )
        assertEquals(1, ledger.getJSONArray("history").length())
        assertEquals(0, dao.pendingSyncBatchCount().first())
    }

    @Test
    fun repeatedReclaimPassesRemainBoundedAndAccumulateOnlyCountsAndReasons() = runTest {
        val dao = db.mobileDataDao()
        dao.insertUsageSummaries(
            (1..3).map {
                summary(MobileSyncStatus.REJECTED, "bad-$it", now - 31L * day)
            }
        )
        val repository = repository(dao, pageSize = 1)

        val first = repository.refreshAndReclaim()
        assertEquals(3L, first.archivedCount)
        assertEquals(0L, first.rejectedCount)

        val second = repository.refreshAndReclaim()
        assertEquals(3L, second.archivedCount)
        assertEquals(3L, second.lastReclaimedCount)
        val ledger = JSONObject(ledgerFile.readText())
        assertEquals(3L, ledger.getLong("archivedCount"))
        assertEquals(1, ledger.getJSONArray("history").length())
    }

    @Test
    fun archiveInsertAndDeleteAreAtomicWhenCleanupIsInterrupted() = runTest {
        val dao = db.mobileDataDao()
        dao.insertUsageSummaries(
            listOf(summary(MobileSyncStatus.REJECTED, "atomic-failure", now - 31L * day))
        )
        val repository = repository(
            dao = dao,
            pageSize = 10,
            afterArchiveInsert = { throw IllegalStateException("synthetic interruption") }
        )

        val snapshot = repository.refreshAndReclaim()

        assertNotNull(snapshot.lastError)
        assertEquals(1L, snapshot.rejectedCount)
        assertEquals(0L, snapshot.archivedCount)
        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED))
        assertTrue(
            dao.usageTerminalArchiveRows(
                entityType = UsageTerminalLifecycleRepository.USAGE_TERMINAL_ARCHIVE_ENTITY,
                limit = 10
            ).isEmpty()
        )
    }

    @Test
    fun archiveInsertAndDeleteAreAtomicWhenCleanupIsCancelled() = runTest {
        val dao = db.mobileDataDao()
        dao.insertUsageSummaries(
            listOf(summary(MobileSyncStatus.REJECTED, "cancel-failure", now - 31L * day))
        )
        val repository = repository(
            dao = dao,
            pageSize = 10,
            afterArchiveInsert = { throw CancellationException("synthetic cancellation") }
        )

        var cancelled = false
        try {
            repository.refreshAndReclaim()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)

        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED))
        assertTrue(
            dao.usageTerminalArchiveRows(
                entityType = UsageTerminalLifecycleRepository.USAGE_TERMINAL_ARCHIVE_ENTITY,
                limit = 10
            ).isEmpty()
        )
    }

    @Test
    fun onePassHonorsTotalBudgetAndReportsRemainingRows() = runTest {
        val dao = db.mobileDataDao()
        dao.insertUsageSummaries(
            (1..4).map {
                summary(MobileSyncStatus.REJECTED, "budget-$it", now - 31L * day)
            }
        )
        val repository = repository(
            dao = dao,
            pageSize = 1,
            maxReclaimRowsPerPass = 2
        )

        val first = repository.refreshAndReclaim()
        assertEquals(2L, first.lastReclaimedCount)
        assertEquals(2L, first.rejectedCount)
        assertEquals(2L, first.reclaimableCount)
        assertTrue(first.statusText.contains("下次启动继续清理"))

        val second = repository.refreshAndReclaim()
        assertEquals(2L, second.lastReclaimedCount)
        assertEquals(0L, second.rejectedCount)
        assertEquals(0L, second.reclaimableCount)
        assertEquals(4L, second.archivedCount)
    }

    @Test
    fun archiveRowsAreExcludedFromOutboxAndPendingQueueButRemainExportable() = runTest {
        val dao = db.mobileDataDao()
        dao.insertSyncBatch(
            MobileSyncBatchEntity(
                batchId = "archive-test",
                entityType = UsageTerminalLifecycleRepository.USAGE_TERMINAL_ARCHIVE_ENTITY,
                rowCount = 3,
                requestJson = "{\"rowCount\":3}",
                syncStatus = "archived",
                createdAtUtc = now,
                updatedAtUtc = now
            )
        )

        assertEquals(0, dao.pendingSyncBatchCount().first())
        assertTrue(dao.getSyncBatchesBySyncStatus(MobileSyncStatus.PENDING).isEmpty())
        assertEquals(
            3,
            dao.usageTerminalArchiveRows(
                entityType = UsageTerminalLifecycleRepository.USAGE_TERMINAL_ARCHIVE_ENTITY,
                limit = 10
            ).single().rowCount
        )
    }

    @Test
    fun databaseArchiveSurvivesLedgerMirrorWriteFailure() = runTest {
        val dao = db.mobileDataDao()
        dao.insertUsageSummaries(
            listOf(summary(MobileSyncStatus.REJECTED, "mirror-failure", now - 31L * day))
        )
        val failingMirror = repository(
            dao = dao,
            pageSize = 10,
            beforeLedgerMirrorWrite = { throw IllegalStateException("disk full") }
        )

        val reclaimed = failingMirror.refreshAndReclaim()
        assertEquals(1L, reclaimed.archivedCount)
        assertEquals(1L, reclaimed.lastReclaimedCount)
        assertEquals(0L, reclaimed.rejectedCount)
        assertNull(reclaimed.lastError)

        val recovered = repository(dao, pageSize = 10).refreshAndReclaim()
        assertEquals(1L, recovered.archivedCount)
        assertEquals("mirror-failure", recovered.lastReclaimedReasons.single().reason)
        assertEquals(1, recovered.archiveHistory.size)
        assertEquals(1L, recovered.archiveHistory.single().count)
    }

    @Test
    fun corruptArchiveLedgerIsVisibleInsteadOfBeingReportedAsNoDeadLetters() = runTest {
        ledgerFile.writeText("{not-json")
        val snapshot = repository(db.mobileDataDao(), pageSize = 10).refresh()

        assertEquals("ledger-corrupt", snapshot.lastError)
        assertTrue(snapshot.statusText.contains("诊断失败"))

        val rewritten = JSONObject(ledgerFile.readText())
        assertEquals("ledger-corrupt", rewritten.getString("lastError"))
    }

    private fun repository(
        dao: com.pim.app.data.MobileDataDao,
        pageSize: Int,
        maxReclaimRowsPerPass: Int =
            UsageTerminalLifecycleRepository.DEFAULT_MAX_RECLAIM_ROWS_PER_PASS,
        afterArchiveInsert: () -> Unit = {},
        beforeLedgerMirrorWrite: () -> Unit = {}
    ): UsageTerminalLifecycleRepository {
        val prefs = context.getSharedPreferences(
            "usage-terminal-lifecycle-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        val logs = StructuredLogRepository(context, TrackingSettingsStore(prefs)) { now }
        return UsageTerminalLifecycleRepository(
            database = db,
            dao = dao,
            logs = logs,
            redactor = DiagnosticRedactor(),
            ledgerFile = ledgerFile,
            nowMillis = { now },
            retentionDays = 30,
            pageSize = pageSize,
            maxReclaimRowsPerPass = maxReclaimRowsPerPass,
            afterArchiveInsert = afterArchiveInsert,
            beforeLedgerMirrorWrite = beforeLedgerMirrorWrite
        )
    }

    private fun summary(
        status: String,
        error: String?,
        updatedAtUtc: Long
    ) = MobileUsageSummaryEntity(
        packageName = "com.example.${System.nanoTime()}",
        windowStartUtc = 1_000L,
        windowEndUtc = 2_000L,
        totalTimeForegroundMs = 500L,
        lastTimeUsedUtc = 1_500L,
        firstTimeStampUtc = 1_000L,
        lastTimeStampUtc = 1_500L,
        source = "test",
        sourceWindowStartUtc = 0L,
        sourceWindowEndUtc = 0L,
        collectedAtUtc = 1_000L,
        rawJson = "{}",
        syncStatus = status,
        lastError = error,
        updatedAtUtc = updatedAtUtc
    )

    private fun UsageTerminalLifecycleSnapshot.lastReclaimedReasonCount(reason: String): Long =
        lastReclaimedReasons.firstOrNull { it.reason == reason }?.count ?: 0L
}

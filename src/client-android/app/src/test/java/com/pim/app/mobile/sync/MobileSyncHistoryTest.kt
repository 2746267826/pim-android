package com.pim.app.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileSyncStatus
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MobileSyncHistoryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var logs: StructuredLogRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = context.getSharedPreferences("sync-history-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        logs = StructuredLogRepository(context, TrackingSettingsStore(prefs)) { 100L }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun historyRowPersistsOutcomeAndNeverBecomesPendingUploadBusiness() = runTest {
        val dao = db.mobileDataDao()
        val id = MobileSyncHistory.begin(dao, logs, nowUtcMillis = 1_000L, pendingCount = 9)
        assertEquals(0, dao.pendingSyncBatchCount().first())

        MobileSyncHistory.finish(
            dao = dao,
            logs = logs,
            historyId = id,
            state = MobileSyncState(
                phase = "failed",
                progressText = "failed",
                outcome = MobileSyncOutcome.RETRY,
                pendingQueueCount = 4,
                lastErrorDetail = "password=super-secret"
            ),
            nowUtcMillis = 2_000L
        )

        val row = dao.getSyncBatchesBySyncStatus("retry", limit = 10).single()
        assertEquals(MobileSyncHistory.ENTITY_TYPE, row.entityType)
        assertEquals(4, row.rowCount)
        assertEquals(2_000L, row.finishedAtUtc)
        assertFalse(row.lastError.orEmpty().contains("super-secret"))
        assertEquals(0, dao.pendingSyncBatchCount().first())
        val exportedProjection = dao.diagnosticSyncHistory(limit = 10).single()
        assertFalse(exportedProjection.failureReason.orEmpty().contains("super-secret"))
    }

    @Test
    fun cancellationClosureMarksOnlyTheCurrentHistoryRunFailed() = runTest {
        val dao = db.mobileDataDao()
        val id = MobileSyncHistory.begin(dao, logs, nowUtcMillis = 1_000L, pendingCount = 1)

        MobileSyncHistory.finishInterrupted(dao, logs, id, nowUtcMillis = 1_500L)

        val row = dao.getSyncBatchesBySyncStatus(MobileSyncStatus.FAILED, limit = 10).single()
        assertEquals("sync-cancelled-or-interrupted", row.lastError)
        assertEquals(1_500L, row.finishedAtUtc)
        assertEquals("sync-run", row.entityType)
    }
}

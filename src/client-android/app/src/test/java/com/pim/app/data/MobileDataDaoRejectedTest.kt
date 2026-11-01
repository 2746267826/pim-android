package com.pim.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MobileDataDaoRejectedTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: MobileDataDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mobileDataDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pendingSyncBatchCountExcludesRejected() = runTest {
        dao.insertSyncBatch(batch(syncStatus = MobileSyncStatus.PENDING))
        dao.insertSyncBatch(batch(syncStatus = MobileSyncStatus.SYNCED))
        dao.insertSyncBatch(batch(syncStatus = MobileSyncStatus.REJECTED))
        dao.insertSyncBatch(batch(syncStatus = MobileSyncStatus.REJECTED))
        dao.insertSyncBatch(batch(syncStatus = MobileSyncStatus.SYNCING, entityType = "sync-run"))

        val count = dao.pendingSyncBatchCount().first()
        assertEquals(1, count)
    }

    @Test
    fun interruptedSyncHistoryRepairAndTrimDoNotTouchUploadBatches() = runTest {
        val business = dao.insertSyncBatch(
            batch(syncStatus = MobileSyncStatus.SYNCING, createdAtUtc = 1L)
        )
        val runOld = dao.insertSyncBatch(
            batch(
                batchId = "run-old",
                syncStatus = MobileSyncStatus.SYNCING,
                entityType = "sync-run",
                createdAtUtc = 2L
            )
        )
        val runNew = dao.insertSyncBatch(
            batch(
                batchId = "run-new",
                syncStatus = MobileSyncStatus.PENDING,
                entityType = "sync-run",
                createdAtUtc = 3L
            )
        )

        assertEquals(
            2,
            dao.failInterruptedSyncHistory(
                nowUtc = 50L,
                entityType = "sync-run",
                reason = "sync-process-interrupted"
            )
        )
        val rows = dao.diagnosticSyncHistory(limit = 10)
        assertEquals(
            MobileSyncStatus.SYNCING,
            rows.single { it.createdAtUtc == 1L }.syncStatus
        )
        assertTrue(rows.filter { it.entityType == "sync-run" }.all {
            it.syncStatus == MobileSyncStatus.FAILED
        })
        assertTrue(
            dao.getSyncBatchesBySyncStatus(MobileSyncStatus.FAILED, limit = 10)
                .all { it.lastError == "sync-process-interrupted" }
        )

        assertEquals(1, dao.trimSyncHistory(keepNewest = 1, entityType = "sync-run"))
        assertTrue(business > 0L && runOld > 0L && runNew > 0L)
        assertEquals(1, dao.diagnosticSyncHistory(limit = 10).count { it.entityType == "sync-run" })
        assertEquals(1, dao.diagnosticSyncHistory(limit = 10).count { it.entityType == "test" })
    }

    @Test
    fun pendingDeviceProfileCountExcludesRejected() = runTest {
        dao.upsertDeviceProfile(profile(profileId = "p1", syncStatus = MobileSyncStatus.PENDING))
        dao.upsertDeviceProfile(profile(profileId = "p2", syncStatus = MobileSyncStatus.SYNCED))
        dao.upsertDeviceProfile(profile(profileId = "p3", syncStatus = MobileSyncStatus.REJECTED))

        val count = dao.pendingDeviceProfileCount().first()
        assertEquals(1, count)
    }

    @Test
    fun aggregateRejectedCountReturnsZeroWhenEmpty() = runTest {
        val count = dao.aggregateRejectedCount().first()
        assertEquals(0, count)
    }

    @Test
    fun aggregateRejectedCountIncludesAllBusinessTables() = runTest {
        dao.insertUsageEvents(listOf(event(syncStatus = MobileSyncStatus.REJECTED)))
        dao.insertUsageSummaries(listOf(summary(syncStatus = MobileSyncStatus.REJECTED)))
        dao.upsertAppMetadata(listOf(meta(syncStatus = MobileSyncStatus.REJECTED)))
        dao.insertLocationPoint(point(syncStatus = MobileSyncStatus.REJECTED))
        dao.insertSyncBatch(batch(syncStatus = MobileSyncStatus.REJECTED))
        dao.upsertDeviceProfile(profile(profileId = "p1", syncStatus = MobileSyncStatus.REJECTED))

        val count = dao.aggregateRejectedCount().first()
        assertEquals(6, count)
    }

    @Test
    fun aggregateRejectedCountExcludesMobileLogs() = runTest {
        dao.insertLogs(listOf(
            log(syncStatus = MobileSyncStatus.REJECTED),
            log(syncStatus = MobileSyncStatus.REJECTED)
        ))
        val count = dao.aggregateRejectedCount().first()
        assertEquals(0, count)
    }

    @Test
    fun aggregateRejectedCountIgnoresNonRejectedStatuses() = runTest {
        dao.insertUsageEvents(listOf(
            event(syncStatus = MobileSyncStatus.PENDING),
            event(syncStatus = MobileSyncStatus.SYNCED),
            event(syncStatus = MobileSyncStatus.FAILED)
        ))
        dao.insertUsageSummaries(listOf(summary(syncStatus = MobileSyncStatus.SYNCED)))

        val count = dao.aggregateRejectedCount().first()
        assertEquals(0, count)
    }

    @Test
    fun rejectedUsageSummaryQueriesAreBoundedAndOnlyReclaimOldRejectedRows() = runTest {
        val old = System.currentTimeMillis() - 40L * 24L * 60L * 60L * 1_000L
        val fresh = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1_000L
        dao.insertUsageSummaries(
            listOf(
                summary(MobileSyncStatus.REJECTED).copy(
                    lastError = "invalid-window",
                    updatedAtUtc = old
                ),
                summary(MobileSyncStatus.REJECTED).copy(
                    lastError = "invalid-window",
                    updatedAtUtc = fresh
                ),
                summary(MobileSyncStatus.PENDING).copy(updatedAtUtc = old),
                summary(MobileSyncStatus.FAILED).copy(updatedAtUtc = old),
                summary(MobileSyncStatus.SYNCING).copy(updatedAtUtc = old)
            )
        )

        val oldIds = dao.rejectedUsageSummaryIdsOlderThan(fresh - 1L, limit = 10)
        assertEquals(1, oldIds.size)
        assertEquals(
            1,
            dao.deleteRejectedUsageSummariesOlderThanIds(
                ids = oldIds,
                cutoffUtc = fresh - 1L
            )
        )
        assertEquals(4, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.PENDING) +
            dao.countUsageSummariesBySyncStatus(MobileSyncStatus.FAILED) +
            dao.countUsageSummariesBySyncStatus(MobileSyncStatus.SYNCING) +
            dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED))
        assertEquals(1, dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED))
        assertTrue(dao.rejectedUsageSummaryStats(limit = 1).single().reason == "invalid-window")
    }

    private fun event(syncStatus: String = MobileSyncStatus.PENDING) = MobileUsageEventEntity(
        packageName = "com.test",
        eventType = 1,
        eventName = "test",
        eventTimeUtc = 1000L,
        source = "test",
        sourceWindowStartUtc = 0L,
        sourceWindowEndUtc = 2000L,
        collectedAtUtc = 1000L,
        rawJson = "{}",
        syncStatus = syncStatus
    )

    private fun summary(syncStatus: String = MobileSyncStatus.PENDING) = MobileUsageSummaryEntity(
        packageName = "com.test",
        windowStartUtc = 1000L,
        windowEndUtc = 2000L,
        totalTimeForegroundMs = 500L,
        lastTimeUsedUtc = 1500L,
        firstTimeStampUtc = 1000L,
        lastTimeStampUtc = 1500L,
        source = "test",
        sourceWindowStartUtc = 0L,
        sourceWindowEndUtc = 0L,
        collectedAtUtc = 1000L,
        rawJson = "{}",
        syncStatus = syncStatus
    )

    private fun meta(syncStatus: String = MobileSyncStatus.PENDING) = MobileAppMetadataEntity(
        packageName = "com.test.${java.util.UUID.randomUUID()}",
        label = "Test",
        versionCode = 1,
        firstInstallTimeUtc = 0L,
        lastUpdateTimeUtc = 0L,
        isSystemApp = false,
        collectedAtUtc = 1000L,
        rawJson = "{}",
        syncStatus = syncStatus
    )

    private fun point(syncStatus: String = MobileSyncStatus.PENDING) = MobileLocationPointEntity(
        latitude = 0.0,
        longitude = 0.0,
        recordedAtUtc = 1000L,
        source = "test",
        collectedAtUtc = 1000L,
        rawJson = "{}",
        syncStatus = syncStatus
    )

    private fun batch(
        syncStatus: String = MobileSyncStatus.PENDING,
        entityType: String = "test",
        batchId: String = java.util.UUID.randomUUID().toString(),
        createdAtUtc: Long = System.currentTimeMillis()
    ) = MobileSyncBatchEntity(
        batchId = batchId,
        entityType = entityType,
        rowCount = 1,
        syncStatus = syncStatus,
        createdAtUtc = createdAtUtc
    )

    private fun profile(
        profileId: String = "default",
        syncStatus: String = MobileSyncStatus.PENDING
    ) = MobileDeviceProfileEntity(
        profileId = profileId,
        deviceId = "test-device",
        manufacturer = "Test",
        brand = "Test",
        model = "Test",
        hardware = "Test",
        androidVersion = "14",
        sdkInt = 34,
        collectedAtUtc = 1000L,
        rawJson = "{}",
        syncStatus = syncStatus
    )

    private fun log(syncStatus: String = MobileSyncStatus.PENDING) = MobileLogEntity(
        level = "INFO",
        message = "test",
        occurredAtUtc = 1000L,
        source = "test",
        collectedAtUtc = 1000L,
        rawJson = "{}",
        syncStatus = syncStatus
    )
}

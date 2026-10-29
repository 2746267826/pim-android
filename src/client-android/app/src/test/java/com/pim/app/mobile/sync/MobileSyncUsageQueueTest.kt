package com.pim.app.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileAppMetadataEntity
import com.pim.app.data.MobileDataDao
import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.MobileUsageEventEntity
import com.pim.app.data.MobileUsageSummaryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MobileSyncUsageQueueTest {
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

    // --- PendingUsageBatch loading ---

    @Test
    fun `loadPendingUsageBatch includes PENDING and FAILED excludes SYNCED and REJECTED`() = runTest {
        val e1 = dao.insertUsageEvents(listOf(event(1000, MobileSyncStatus.PENDING))).first()
        dao.insertUsageEvents(listOf(event(2000, MobileSyncStatus.SYNCED)))
        dao.insertUsageEvents(listOf(event(3000, MobileSyncStatus.REJECTED)))
        val e2 = dao.insertUsageEvents(listOf(event(4000, MobileSyncStatus.FAILED))).first()

        val batch = loadPendingUsageBatch(dao, 500)

        val allIds = (batch.events.map { it.id } + batch.summaries.map { it.id }).toSet()
        assertTrue(allIds.contains(e1))
        assertTrue(allIds.contains(e2))
        assertEquals(2, batch.events.size)
        assertEquals(0, batch.summaries.size)
    }

    // --- 空载缺口窗口跳过上传（EPIC #254 S11） ---

    @Test
    fun `isEmptyGapWindowUpload is true only when events summaries and apps are all empty`() {
        assertTrue(isEmptyGapWindowUpload(emptyList<Any>(), emptyList<Any>(), emptyList<Any>()))
        assertFalse(isEmptyGapWindowUpload(listOf(Any()), emptyList<Any>(), emptyList<Any>()))
        assertFalse(isEmptyGapWindowUpload(emptyList<Any>(), listOf(Any()), emptyList<Any>()))
        assertFalse(isEmptyGapWindowUpload(emptyList<Any>(), emptyList<Any>(), listOf(Any())))
    }

    @Test
    fun `loadPendingUsageBatch PENDING prioritized before FAILED`() = runTest {
        dao.insertUsageEvents(listOf(event(1000, MobileSyncStatus.FAILED)))
        val pendingId = dao.insertUsageEvents(listOf(event(2000, MobileSyncStatus.PENDING))).first()

        val batch = loadPendingUsageBatch(dao, 1)

        assertEquals(1, batch.events.size)
        assertEquals(pendingId, batch.events.single().id)
    }

    @Test
    fun `loadPendingUsageBatch limit 500 is not blocking`() = runTest {
        val entities = (0 until 501).map { i -> event(i * 1000L, MobileSyncStatus.PENDING) }
        dao.insertUsageEvents(entities)

        val batch = loadPendingUsageBatch(dao, 500)

        assertEquals(500, batch.totalCount)
        assertEquals(500, batch.events.size)
        assertEquals(501, pendingUsageRemaining(dao))
    }

    @Test
    fun `loadPendingUsageBatch appMetadataOnly yields valid window start LE end plus 1ms`() = runTest {
        dao.upsertAppMetadata(listOf(appMeta("com.test", collectedAtUtc = 5000, MobileSyncStatus.PENDING)))

        val batch = loadPendingUsageBatch(dao, 500)

        assertTrue(batch.events.isEmpty())
        assertTrue(batch.summaries.isEmpty())
        assertEquals(1, batch.apps.size)
        assertNotNull(batch.windowStartUtc)
        assertNotNull(batch.windowEndUtc)
        assertTrue(batch.windowEndUtc!! > batch.windowStartUtc!!)
        assertTrue(batch.windowStartUtc!! <= 5000)
        assertTrue(batch.windowEndUtc!! >= 5000)
    }

    @Test
    fun `loadPendingUsageBatch mixed types captures window from earliest to latest`() = runTest {
        dao.insertUsageEvents(listOf(event(1000, MobileSyncStatus.PENDING)))
        dao.insertUsageSummaries(listOf(summary(8000, 9000, MobileSyncStatus.PENDING)))
        dao.upsertAppMetadata(listOf(appMeta("com.test", collectedAtUtc = 5000, MobileSyncStatus.PENDING)))

        val batch = loadPendingUsageBatch(dao, 500)

        assertEquals(1000L, batch.windowStartUtc)
        assertEquals(9000L, batch.windowEndUtc)
    }

    @Test
    fun `loadPendingUsageBatch empty returns null window`() = runTest {
        val batch = loadPendingUsageBatch(dao, 500)

        assertEquals(0, batch.totalCount)
        assertEquals(null, batch.windowStartUtc)
        assertEquals(null, batch.windowEndUtc)
    }

    // --- pendingUsageRemaining excludes location ---

    @Test
    fun `pendingUsageRemaining counts usage only not location`() = runTest {
        dao.insertUsageEvents(listOf(event(1000, MobileSyncStatus.PENDING)))
        dao.insertUsageSummaries(listOf(summary(2000, 3000, MobileSyncStatus.PENDING)))
        dao.upsertAppMetadata(listOf(appMeta("com.a", collectedAtUtc = 5000, MobileSyncStatus.PENDING)))

        val locPoint = com.pim.app.data.MobileLocationPointEntity(
            latitude = 0.0, longitude = 0.0,
            recordedAtUtc = 1000, source = "test",
            collectedAtUtc = 1000, rawJson = "{}"
        )
        dao.insertLocationPoint(locPoint)

        val remaining = pendingUsageRemaining(dao)

        assertEquals(3, remaining)
    }

    @Test
    fun `pendingUsageRemaining returns true row count not category count`() = runTest {
        dao.insertUsageEvents(listOf(event(1000, MobileSyncStatus.PENDING), event(2000, MobileSyncStatus.PENDING)))
        dao.insertUsageSummaries(listOf(summary(3000, 4000, MobileSyncStatus.PENDING)))
        dao.upsertAppMetadata(listOf(appMeta("com.a", collectedAtUtc = 5000, MobileSyncStatus.PENDING)))

        val locPoint = com.pim.app.data.MobileLocationPointEntity(
            latitude = 0.0, longitude = 0.0,
            recordedAtUtc = 1000, source = "test",
            collectedAtUtc = 1000, rawJson = "{}"
        )
        dao.insertLocationPoint(locPoint)

        val remaining = pendingUsageRemaining(dao)

        assertEquals(4, remaining)
    }

    @Test
    fun `pendingUsageTimeRange includes all usage outbox types without deleting rows`() = runTest {
        dao.insertUsageEvents(listOf(event(1000, MobileSyncStatus.PENDING)))
        dao.insertUsageSummaries(listOf(summary(8000, 9000, MobileSyncStatus.PENDING)))
        dao.upsertAppMetadata(listOf(appMeta("com.range", collectedAtUtc = 5000, MobileSyncStatus.PENDING)))

        val range = dao.pendingUsageTimeRange()

        assertEquals(1000L, range.earliestUtc)
        assertEquals(9000L, range.latestUtc)
        assertEquals(3, pendingUsageRemaining(dao))
    }

    // --- sortedMergeOutcome ---

    @Test
    fun `sortedMergeOutcome RETRY beats BLOCKED`() {
        assertEquals(MobileSyncOutcome.RETRY, sortedMergeOutcome(MobileSyncOutcome.RETRY, MobileSyncOutcome.BLOCKED))
    }

    @Test
    fun `sortedMergeOutcome BLOCKED beats SUCCESS`() {
        assertEquals(MobileSyncOutcome.BLOCKED, sortedMergeOutcome(MobileSyncOutcome.BLOCKED, MobileSyncOutcome.SUCCESS))
    }

    @Test
    fun `sortedMergeOutcome RETRY beats SUCCESS`() {
        assertEquals(MobileSyncOutcome.RETRY, sortedMergeOutcome(MobileSyncOutcome.RETRY, MobileSyncOutcome.SUCCESS))
    }

    @Test
    fun `multi batch constants match requirements`() {
        assertEquals(10, MAX_USAGE_BATCHES_PER_RUN)
        assertEquals(120_000L, MAX_USAGE_BATCH_DURATION_MS)
        assertEquals(500, USAGE_BATCH_LIMIT)
        assertEquals(500 * 3, 1_500)
        assertEquals(2L * 60L * 60L * 1000L, USAGE_BATCH_TARGET_WINDOW_MS)
        assertEquals(25L * 60L * 60L * 1000L, USAGE_BATCH_MAX_WINDOW_MS)
    }

    @Test
    fun `40 hour mixed backlog partitions into bounded complete deterministic batches`() {
        val start = 1_000_000L
        val backlogMs = 40L * 60L * 60L * 1000L
        val step = backlogMs / 499L
        val events = (0 until 500).map { index ->
            event(start + index * step, MobileSyncStatus.PENDING).copy(id = index + 1L)
        }
        val summaries = (0 until 500).map { index ->
            val summaryStart = start + index * step
            summary(
                summaryStart,
                summaryStart + 60L * 60L * 1000L,
                MobileSyncStatus.PENDING
            ).copy(id = 10_000L + index)
        }
        val apps = (0 until 500).map { index ->
            appMeta(
                pkg = "com.example.${index.toString().padStart(3, '0')}",
                collectedAtUtc = start + index * step,
                status = MobileSyncStatus.PENDING
            )
        }

        val firstRun = partitionPendingUsageBatch(events, summaries, apps)
        val secondRun = partitionPendingUsageBatch(events, summaries, apps)

        assertEquals(1_500, firstRun.sumOf { it.totalCount })
        assertTrue(firstRun.all { it.totalCount <= USAGE_BATCH_LIMIT })
        assertTrue(firstRun.all { batch ->
            batch.windowEndUtc!! - batch.windowStartUtc!! <= USAGE_BATCH_TARGET_WINDOW_MS
        })
        assertEquals(1_500, firstRun.flatMap { it.typedKeys() }.toSet().size)
        assertEquals(firstRun.sumOf { it.totalCount }, firstRun.flatMap { it.typedKeys() }.toSet().size)

        val firstIds = firstRun.map { batch ->
            stableBatchId(
                "device-test",
                batch.windowStartUtc.toString(),
                batch.windowEndUtc.toString(),
                batch.typedKeys()
            )
        }
        val secondIds = secondRun.map { batch ->
            stableBatchId(
                "device-test",
                batch.windowStartUtc.toString(),
                batch.windowEndUtc.toString(),
                batch.typedKeys()
            )
        }
        assertEquals(firstIds, secondIds)
        assertEquals(firstRun.map { it.typedKeys() }, secondRun.map { it.typedKeys() })
    }

    @Test
    fun `valid 25 hour daily summary stays atomic and is not clipped to 2 hours`() {
        val start = 8_000_000L
        val end = start + 25L * 60L * 60L * 1000L
        val daily = summary(start, end, MobileSyncStatus.PENDING).copy(id = 77)
        val events = listOf(
            event(start + 60_000L, MobileSyncStatus.PENDING).copy(id = 91),
            event(end - 60_000L, MobileSyncStatus.PENDING).copy(id = 92)
        )

        val batches = partitionPendingUsageBatch(events, listOf(daily), emptyList())

        assertEquals(1, batches.size)
        val batch = batches.single()
        assertEquals(listOf(daily), batch.summaries)
        assertEquals(setOf("91", "92"), batch.events.map { it.id.toString() }.toSet())
        assertEquals(start, batch.windowStartUtc)
        assertEquals(end, batch.windowEndUtc)
        assertEquals(25L * 60L * 60L * 1000L, batch.windowEndUtc!! - batch.windowStartUtc!!)
    }

    @Test
    fun `app metadata at the far end does not stretch an event batch across 40 hours`() {
        val start = 10_000_000L
        val eventRow = event(start, MobileSyncStatus.PENDING).copy(id = 1)
        val appRow = appMeta(
            pkg = "com.far.away",
            collectedAtUtc = start + 40L * 60L * 60L * 1000L,
            status = MobileSyncStatus.PENDING
        )

        val batches = partitionPendingUsageBatch(
            events = listOf(eventRow),
            summaries = emptyList(),
            apps = listOf(appRow)
        )

        assertEquals(2, batches.size)
        assertTrue(batches.all { it.windowEndUtc!! - it.windowStartUtc!! <= 1L })
        assertEquals(setOf("1"), batches.first().events.map { it.id.toString() }.toSet())
        assertEquals(listOf("com.far.away"), batches.last().apps.map { it.packageName })
    }

    @Test
    fun `summary outside the 25 hour API contract is isolated for explicit local rejection`() {
        val start = 12_000_000L
        val supported = summary(start, start + USAGE_BATCH_MAX_WINDOW_MS, MobileSyncStatus.PENDING)
        val tooLong = summary(
            start,
            start + USAGE_BATCH_MAX_WINDOW_MS + 1,
            MobileSyncStatus.PENDING
        )
        val malformed = summary(start + 1000, start + 1000, MobileSyncStatus.PENDING)

        assertTrue(supported.hasSupportedUsageBatchWindow())
        assertFalse(tooLong.hasSupportedUsageBatchWindow())
        assertFalse(malformed.hasSupportedUsageBatchWindow())
    }

    @Test
    fun `merge combines multiple successful catching-up batches`() {
        val batch1 = MobileSyncState(
            phase = "catching-up",
            progressText = "正在补传（剩 819 条）。",
            outcome = MobileSyncOutcome.SUCCESS,
            acceptedCount = 500,
            lastBatchId = "batch-1"
        )
        val batch2 = MobileSyncState(
            phase = "catching-up",
            progressText = "正在补传（剩 319 条）。",
            outcome = MobileSyncOutcome.SUCCESS,
            acceptedCount = 500,
            lastBatchId = "batch-2"
        )
        val merged = batch1.merge(batch2)
        assertEquals(MobileSyncOutcome.SUCCESS, merged.outcome)
        assertEquals(1000, merged.acceptedCount)
        assertEquals("batch-2", merged.lastBatchId)
    }

    @Test
    fun `merge combines failed usage with successful location sync`() {
        val usageFailedState = MobileSyncState(
            phase = "old-queue-upload-failed",
            progressText = "旧队列上传后有 1 条待同步使用记录，已安排重试。",
            outcome = MobileSyncOutcome.RETRY,
            failedCount = 1,
            lastError = "HTTP 500"
        )
        val locationSuccessState = MobileSyncState(
            phase = "location-uploaded",
            progressText = "已同步 1 个位置点。",
            outcome = MobileSyncOutcome.SUCCESS,
            acceptedCount = 1
        )
        val merged = locationSuccessState.merge(usageFailedState)
        assertEquals(MobileSyncOutcome.RETRY, merged.outcome)
        assertEquals(1, merged.acceptedCount)
        assertEquals(1, merged.failedCount)
        assertEquals("HTTP 500", merged.lastError)
    }

    companion object {
        fun event(timeUtc: Long, status: String) = MobileUsageEventEntity(
            packageName = "com.test",
            className = null,
            eventType = 1,
            eventName = "test",
            eventTimeUtc = timeUtc,
            source = "test",
            sourceWindowStartUtc = timeUtc,
            sourceWindowEndUtc = timeUtc + 1000,
            collectedAtUtc = timeUtc,
            rawJson = "{}",
            syncStatus = status
        )

        fun summary(startUtc: Long, endUtc: Long, status: String) = MobileUsageSummaryEntity(
            packageName = "com.test",
            windowStartUtc = startUtc,
            windowEndUtc = endUtc,
            totalTimeForegroundMs = 1000,
            lastTimeUsedUtc = endUtc,
            firstTimeStampUtc = startUtc,
            lastTimeStampUtc = endUtc,
            source = "UsageStatsManager",
            sourceWindowStartUtc = startUtc,
            sourceWindowEndUtc = endUtc,
            collectedAtUtc = endUtc,
            rawJson = "{}",
            syncStatus = status
        )

        fun appMeta(pkg: String, collectedAtUtc: Long, status: String) = MobileAppMetadataEntity(
            packageName = pkg,
            label = "Test",
            versionCode = 1,
            firstInstallTimeUtc = 0,
            lastUpdateTimeUtc = 0,
            isSystemApp = false,
            collectedAtUtc = collectedAtUtc,
            rawJson = "{}",
            syncStatus = status
        )
    }
}

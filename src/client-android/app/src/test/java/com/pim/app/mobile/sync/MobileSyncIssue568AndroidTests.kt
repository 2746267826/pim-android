package com.pim.app.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.data.AppDatabase
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #568 客户端回归：
 * 1. 服务端 409 的自动恢复边界（重试耗尽/租约丢失自动换批次标识，内容冲突不自动）；
 * 2. 「上一次同步没结束、本次唤醒被跳过」必须在 sync-history 里留下可读的一行，
 *    而不是像事故里那样 596 次唤醒一条痕迹都没有。
 */
@RunWith(RobolectricTestRunner::class)
class MobileSyncIssue568AndroidTests {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var logs: StructuredLogRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = context.getSharedPreferences("issue-568-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        logs = StructuredLogRepository(context, TrackingSettingsStore(prefs)) { 100L }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 409 自动恢复 ----------

    @Test
    fun retryExhaustedRotatesTheBatchIdAutomatically() {
        val decision = decideUsageBatchConflictRecovery(
            state = UsageBatchRecoveryState(recoveryEpoch = 0),
            reason = UsageBatchBlockReason.RETRY_EXHAUSTED,
            message = "同步批次处理次数已耗尽"
        )

        assertTrue(decision.batchIdRotated)
        assertEquals(1, decision.state.recoveryEpoch)
        assertFalse(decision.state.blockedBy409)
        assertEquals(1, decision.state.autoRotations)
        // 换标识要重置无进展计数，否则新 generation 一上来就背着旧账。
        assertEquals(0, decision.state.noProgressAttempts)
    }

    @Test
    fun leaseLostKeepsTheSameBatchIdWhilePayloadConflictBlocks() {
        val leaseLost = decideUsageBatchConflictRecovery(
            state = UsageBatchRecoveryState(),
            reason = UsageBatchBlockReason.LEASE_LOST,
            message = "租约冲突"
        )
        // 租约丢失是瞬态的：同一批次标识再试一次就能拿到回放，换标识只会多一条批次行。
        assertFalse(leaseLost.batchIdRotated)
        assertFalse(leaseLost.state.blockedBy409)

        val payloadConflict = decideUsageBatchConflictRecovery(
            state = UsageBatchRecoveryState(),
            reason = UsageBatchBlockReason.PAYLOAD_CONFLICT,
            message = "同一批次内容冲突"
        )
        // 自动换标识会绕过服务端对"同一批次内容不一致"的保护，必须留人工确认。
        assertFalse(payloadConflict.batchIdRotated)
        assertTrue(payloadConflict.state.blockedBy409)
        assertEquals(UsageBatchBlockReason.PAYLOAD_CONFLICT, payloadConflict.state.blockReason)
    }

    @Test
    fun autoRotationIsBoundedAndThenFallsBackToManualRecovery() {
        var state = UsageBatchRecoveryState()
        repeat(MAX_USAGE_AUTO_ROTATIONS) {
            val decision = decideUsageBatchConflictRecovery(
                state = state,
                reason = UsageBatchBlockReason.RETRY_EXHAUSTED,
                message = "耗尽"
            )
            assertTrue("自动轮换应在额度内继续", decision.batchIdRotated)
            state = decision.state
        }

        val exhausted = decideUsageBatchConflictRecovery(
            state = state,
            reason = UsageBatchBlockReason.RETRY_EXHAUSTED,
            message = "仍然耗尽"
        )
        assertFalse("超过上限必须停下来等人工处理，不能无界换标识", exhausted.batchIdRotated)
        assertTrue(exhausted.state.blockedBy409)
        assertEquals(MAX_USAGE_AUTO_ROTATIONS, exhausted.state.autoRotations)
    }

    // ---------- 被跳过的唤醒必须留痕 ----------

    @Test
    fun skippedBusyWakeIsRecordedAsItsOwnHistoryRow() = runTest {
        val dao = db.mobileDataDao()

        MobileSyncHistory.recordSkippedBusy(
            dao = dao,
            logs = logs,
            nowUtcMillis = 1_000L,
            pendingCount = 391,
            busyForMillis = 5_400_000L
        )

        val row = dao.getSyncBatchesBySyncStatus(MobileSyncHistory.SKIPPED_BUSY_STATUS, limit = 10).single()
        assertEquals(MobileSyncHistory.ENTITY_TYPE, row.entityType)
        assertEquals(0, row.rowCount)
        assertEquals(1_000L, row.finishedAtUtc)
        assertTrue(row.lastError.orEmpty().contains("busyForMillis=5400000"))
        // 历史行不是待上传业务数据，不能把积压计数搞脏。
        assertEquals(0, dao.pendingSyncBatchCount().first())
    }

    @Test
    fun skippedBusyWakeWithoutBusySinceStillRecordsARow() = runTest {
        val dao = db.mobileDataDao()

        MobileSyncHistory.recordSkippedBusy(
            dao = dao,
            logs = logs,
            nowUtcMillis = 2_000L,
            pendingCount = 0,
            busyForMillis = null
        )

        val row = dao.getSyncBatchesBySyncStatus(MobileSyncHistory.SKIPPED_BUSY_STATUS, limit = 10).single()
        assertEquals("sync-busy-skip", row.lastError)
        assertEquals(2_000L, row.finishedAtUtc)
    }
}

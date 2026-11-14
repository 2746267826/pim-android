package com.pim.app.mobile.sync

import com.pim.app.data.MobileDataDao
import com.pim.app.data.MobileSyncBatchEntity
import com.pim.app.mobile.diagnostics.DiagnosticRedactor
import com.pim.app.mobile.logs.StructuredLogRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Persists one bounded sync-history row per real sync run.
 *
 * The row is diagnostic history, not an upload outbox. `pendingSyncBatchCount`
 * deliberately excludes `entity_type = sync-run`; failures here never change
 * the sync result.
 */
internal object MobileSyncHistory {
    const val ENTITY_TYPE = "sync-run"
    private const val MAX_ROWS = 2_000
    private const val MAX_ERROR_CHARS = 240
    private val redactor = DiagnosticRedactor()

    /** 因上一次同步未结束而被跳过的唤醒（#568）：只占一行，不进入任何成功率分母。 */
    const val SKIPPED_BUSY_STATUS = "skipped-busy"

    /**
     * 记录一次"被跳过的唤醒"。
     *
     * 事故（#568）里这一段是纯黑：5 小时 31 分内 596 次唤醒全部在 `tryLock` 处静默返回，
     * 日志与 sync-history 都没有痕迹，只能靠 forensics 心搏反推。这里把它变成可读的一行。
     */
    suspend fun recordSkippedBusy(
        dao: MobileDataDao,
        logs: StructuredLogRepository,
        nowUtcMillis: Long,
        pendingCount: Int,
        busyForMillis: Long?
    ) {
        val id = begin(dao, logs, nowUtcMillis, pendingCount) ?: return
        try {
            dao.finishSyncHistory(
                id = id,
                rowCount = 0,
                finishedAtUtc = nowUtcMillis,
                syncStatus = SKIPPED_BUSY_STATUS,
                lastError = if (busyForMillis == null) {
                    "sync-busy-skip"
                } else {
                    "sync-busy-skip busyForMillis=$busyForMillis"
                }
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logs.warn(
                "diagnostics-history",
                "收口被跳过的同步历史失败：${ex.message ?: ex::class.java.simpleName}"
            )
        }
    }

    suspend fun begin(
        dao: MobileDataDao,
        logs: StructuredLogRepository,
        nowUtcMillis: Long,
        pendingCount: Int
    ): Long? = try {
        val id = dao.insertSyncBatch(
            MobileSyncBatchEntity(
                batchId = "android-sync-run-${UUID.randomUUID()}",
                entityType = ENTITY_TYPE,
                rowCount = pendingCount.coerceAtLeast(0),
                startedAtUtc = nowUtcMillis,
                syncStatus = "syncing",
                createdAtUtc = nowUtcMillis,
                updatedAtUtc = nowUtcMillis
            )
        )
        if (id <= 0L) {
            logs.warn("diagnostics-history", "写入同步历史未返回有效行号。")
            null
        } else {
            try {
                dao.trimSyncHistory(keepNewest = MAX_ROWS, entityType = ENTITY_TYPE)
            } catch (trimFailure: CancellationException) {
                throw trimFailure
            } catch (trimFailure: Exception) {
                logs.warn(
                    "diagnostics-history",
                    "裁剪同步历史失败：${trimFailure.message ?: trimFailure::class.java.simpleName}"
                )
            }
            id
        }
    } catch (ex: CancellationException) {
        throw ex
    } catch (ex: Exception) {
        logs.warn(
            "diagnostics-history",
            "写入同步历史失败：${ex.message ?: ex::class.java.simpleName}"
        )
        null
    }

    suspend fun finish(
        dao: MobileDataDao,
        logs: StructuredLogRepository,
        historyId: Long?,
        state: MobileSyncState,
        nowUtcMillis: Long
    ) {
        if (historyId == null) return
        try {
            dao.finishSyncHistory(
                id = historyId,
                rowCount = state.pendingQueueCount.coerceAtLeast(0),
                finishedAtUtc = nowUtcMillis,
                syncStatus = historyStatus(state.outcome),
                lastError = safeError(state)
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logs.warn(
                "diagnostics-history",
                "收口同步历史失败：${ex.message ?: ex::class.java.simpleName}"
            )
        }
    }

    suspend fun finishInterrupted(
        dao: MobileDataDao,
        logs: StructuredLogRepository,
        historyId: Long?,
        nowUtcMillis: Long
    ) {
        if (historyId == null) return
        try {
            dao.finishSyncHistory(
                id = historyId,
                rowCount = 0,
                finishedAtUtc = nowUtcMillis,
                syncStatus = "failed",
                lastError = "sync-cancelled-or-interrupted"
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logs.warn("diagnostics-history", "收口中断同步历史失败：${ex.message ?: ex::class.java.simpleName}")
        }
    }

    suspend fun failInterrupted(
        dao: MobileDataDao,
        logs: StructuredLogRepository,
        nowUtcMillis: Long
    ) {
        try {
            val count = dao.failInterruptedSyncHistory(
                nowUtc = nowUtcMillis,
                entityType = ENTITY_TYPE,
                reason = "sync-process-interrupted"
            )
            if (count > 0) {
                logs.warn(
                    "diagnostics-history",
                    "已把 $count 条中断的同步历史标记为 failed。"
                )
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logs.warn(
                "diagnostics-history",
                "修复中断同步历史失败：${ex.message ?: ex::class.java.simpleName}"
            )
        }
    }

    fun historyStatus(outcome: MobileSyncOutcome): String = when (outcome) {
        MobileSyncOutcome.SUCCESS -> "completed"
        MobileSyncOutcome.DEFERRED -> "deferred"
        MobileSyncOutcome.RETRY -> "retry"
        MobileSyncOutcome.BLOCKED -> "blocked"
    }

    private fun safeError(state: MobileSyncState): String? {
        val raw = state.lastErrorDetail ?: state.lastError ?: return null
        return redactor.redactText(raw)
            .replace(Regex("\\s+"), " ")
            .take(MAX_ERROR_CHARS)
            .ifBlank { null }
    }
}

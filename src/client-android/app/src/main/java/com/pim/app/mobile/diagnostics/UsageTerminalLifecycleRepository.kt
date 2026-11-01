package com.pim.app.mobile.diagnostics

import android.content.Context
import androidx.room.withTransaction
import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileDataDao
import com.pim.app.data.MobileSyncBatchEntity
import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.RejectedUsageSummaryStat
import com.pim.app.mobile.logs.StructuredLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class TerminalReasonCount(
    val reason: String,
    val count: Long
)

data class TerminalArchiveEntry(
    val archivedAtUtc: Long,
    val cutoffUtc: Long,
    val count: Long,
    val reasons: List<TerminalReasonCount>
)

/**
 * Local status for server-rejected usage summaries.
 *
 * Rejected rows are dead letters: they are never selected by the pending outbox
 * query and are never changed back to pending. This ledger explains how many
 * remain, why, and when the bounded retention pass will reclaim them.
 */
data class UsageTerminalLifecycleSnapshot(
    val rejectedCount: Long = 0L,
    val reasons: List<TerminalReasonCount> = emptyList(),
    val oldestRejectedUpdatedAtUtc: Long? = null,
    val reclaimableCount: Long = 0L,
    val retentionDays: Int = UsageTerminalLifecycleRepository.DEFAULT_RETENTION_DAYS,
    val nextEligibleAtUtc: Long? = null,
    val archivedCount: Long = 0L,
    val lastReclaimedAtUtc: Long? = null,
    val lastReclaimedCount: Long = 0L,
    val lastReclaimedReasons: List<TerminalReasonCount> = emptyList(),
    val archiveHistory: List<TerminalArchiveEntry> = emptyList(),
    val lastError: String? = null
) {
    val statusText: String
        get() {
            if (rejectedCount == 0L && archivedCount == 0L && lastError == null) {
                return "无死信；pending 队列不受终态清理影响"
            }
            val current = if (rejectedCount > 0L) {
                "$rejectedCount 条死信不再上传，保留 $retentionDays 天后归档清理"
            } else {
                "当前无死信"
            }
            val archived = if (archivedCount > 0L) {
                "；已归档 $archivedCount 条"
            } else {
                ""
            }
            val reclaimable = if (reclaimableCount > 0L) {
                "；其中 $reclaimableCount 条已超过保留期，受单次预算限制，下次启动继续清理"
            } else {
                ""
            }
            val error = lastError?.let { "；诊断失败：$it" }.orEmpty()
            return current + archived + reclaimable + error
        }
}

@Singleton
class UsageTerminalLifecycleRepository internal constructor(
    private val database: AppDatabase,
    private val dao: MobileDataDao,
    private val logs: StructuredLogRepository,
    private val redactor: DiagnosticRedactor,
    private val ledgerFile: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxReclaimRowsPerPass: Int = DEFAULT_MAX_RECLAIM_ROWS_PER_PASS,
    private val afterArchiveInsert: () -> Unit = {},
    private val beforeLedgerMirrorWrite: () -> Unit = {}
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: AppDatabase,
        logs: StructuredLogRepository,
        redactor: DiagnosticRedactor
    ) : this(
        database = database,
        dao = database.mobileDataDao(),
        logs = logs,
        redactor = redactor,
        ledgerFile = File(context.filesDir, "forensics/usage-terminal-lifecycle.json"),
        nowMillis = System::currentTimeMillis,
        retentionDays = DEFAULT_RETENTION_DAYS,
        pageSize = DEFAULT_PAGE_SIZE,
        maxReclaimRowsPerPass = DEFAULT_MAX_RECLAIM_ROWS_PER_PASS
    )

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(UsageTerminalLifecycleSnapshot())
    val snapshot: StateFlow<UsageTerminalLifecycleSnapshot> = _snapshot.asStateFlow()

    init {
        require(retentionDays > 0) { "retentionDays must be positive" }
        require(pageSize > 0) { "pageSize must be positive" }
        require(maxReclaimRowsPerPass > 0) { "maxReclaimRowsPerPass must be positive" }
    }

    suspend fun refresh(): UsageTerminalLifecycleSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            refreshLocked(reclaimExpired = false)
        }
    }

    /**
     * Reclaims only rows that are both `rejected` and older than the retention
     * boundary. Pending/failed/syncing rows are intentionally outside the query.
     */
    suspend fun refreshAndReclaim(): UsageTerminalLifecycleSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            refreshLocked(reclaimExpired = true)
        }
    }

    private suspend fun refreshLocked(reclaimExpired: Boolean): UsageTerminalLifecycleSnapshot {
        val now = nowMillis()
        val cutoff = now - retentionDays.toLong() * DAY_MILLIS
        val ledger = readLedger()
        var lastError: String? = null

        return try {
            var removed = 0L
            var reclaimedReasons = emptyList<TerminalReasonCount>()
            if (reclaimExpired) {
                var remainingBudget = maxReclaimRowsPerPass
                while (remainingBudget > 0) {
                    val ids = dao.rejectedUsageSummaryIdsOlderThan(
                        cutoffUtc = cutoff,
                        limit = minOf(pageSize, remainingBudget)
                    )
                    if (ids.isEmpty()) break
                    val archivedPage = database.withTransaction {
                        val pageReasons = sanitizeStats(
                            dao.rejectedUsageSummaryStatsForIds(
                                ids = ids,
                                cutoffUtc = cutoff,
                                limit = MAX_REASONS
                            )
                        )
                        val archiveJson = archivePageJson(
                            archivedAtUtc = now,
                            cutoffUtc = cutoff,
                            ids = ids,
                            reasons = pageReasons
                        ).toString()
                        val archiveId = dao.insertSyncBatch(
                            MobileSyncBatchEntity(
                                batchId = "usage-terminal-archive-${UUID.randomUUID()}",
                                entityType = USAGE_TERMINAL_ARCHIVE_ENTITY,
                                rowCount = ids.size,
                                requestJson = archiveJson,
                                syncStatus = ARCHIVED_STATUS,
                                createdAtUtc = now,
                                updatedAtUtc = now
                            )
                        )
                        check(archiveId > 0L) { "Failed to persist usage archive summary" }
                        val archivedBefore = dao.usageTerminalArchiveTotal(
                            batchId = USAGE_TERMINAL_ARCHIVE_TOTAL_BATCH_ID,
                            entityType = USAGE_TERMINAL_ARCHIVE_TOTAL_ENTITY
                        )?.archivedCount ?: 0L
                        val totalId = dao.insertSyncBatch(
                            MobileSyncBatchEntity(
                                batchId = USAGE_TERMINAL_ARCHIVE_TOTAL_BATCH_ID,
                                entityType = USAGE_TERMINAL_ARCHIVE_TOTAL_ENTITY,
                                rowCount = (archivedBefore + ids.size)
                                    .coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt(),
                                requestJson = archiveJson,
                                syncStatus = ARCHIVED_STATUS,
                                createdAtUtc = now,
                                updatedAtUtc = now
                            )
                        )
                        check(totalId > 0L) { "Failed to persist cumulative usage archive count" }
                        afterArchiveInsert()
                        val deleted = dao.deleteRejectedUsageSummariesOlderThanIds(
                            ids = ids,
                            cutoffUtc = cutoff,
                            rejectedStatus = MobileSyncStatus.REJECTED
                        )
                        check(deleted == ids.size) {
                            "Usage archive page changed before atomic deletion"
                        }
                        deleted to pageReasons
                    }
                    val count = archivedPage.first
                    val pageReasons = archivedPage.second
                    if (count <= 0) break
                    removed += count
                    remainingBudget -= count
                    reclaimedReasons = mergeReasons(reclaimedReasons, pageReasons)
                }

                val purgedSyncHistory = dao.deleteSyncHistoryOlderThan(
                    cutoffUtc = cutoff,
                    entityType = "sync-run"
                )
                val trimmedSyncHistory = dao.trimSyncHistory(
                    keepNewest = MAX_SYNC_HISTORY_ROWS,
                    entityType = "sync-run"
                )
                val trimmedTerminalArchives = dao.trimSyncHistory(
                    keepNewest = MAX_ARCHIVE_HISTORY,
                    entityType = USAGE_TERMINAL_ARCHIVE_ENTITY
                )
                if (
                    removed > 0L ||
                    purgedSyncHistory > 0 ||
                    trimmedSyncHistory > 0 ||
                    trimmedTerminalArchives > 0
                ) {
                    logs.info(
                        "diagnostics-lifecycle",
                        "终态诊断清理完成。",
                        mapOf(
                            "reclaimedRejectedUsageSummaries" to removed,
                            "reclaimedReasons" to reclaimedReasons.joinToString { reason ->
                                "${reason.reason}:${reason.count}"
                            },
                            "purgedSyncHistory" to purgedSyncHistory,
                            "trimmedSyncHistory" to trimmedSyncHistory,
                            "trimmedTerminalArchives" to trimmedTerminalArchives
                        )
                    )
                }
            }

            val rawStats = dao.rejectedUsageSummaryStats()
            val stats = sanitizeStats(rawStats)
            val rejectedCount = dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED).toLong()
            val reclaimableCount = dao.countRejectedUsageSummariesOlderThan(cutoff).toLong()
            val oldest = rawStats.minOfOrNull { it.oldestUpdatedAtUtc }
            val nextEligible = oldest?.let { it + retentionDays.toLong() * DAY_MILLIS }
            val archiveRows = dao.usageTerminalArchiveRows(
                entityType = USAGE_TERMINAL_ARCHIVE_ENTITY,
                limit = MAX_ARCHIVE_HISTORY
            )
            val durableHistory = archiveRows.mapNotNull(::parseArchiveRow).reversed()
            val archiveHistory = if (durableHistory.isNotEmpty()) {
                durableHistory
            } else {
                ledger.history
            }
            val durableArchivedCount = maxOf(
                ledger.archivedCount,
                dao.usageTerminalArchiveTotal(
                    batchId = USAGE_TERMINAL_ARCHIVE_TOTAL_BATCH_ID,
                    entityType = USAGE_TERMINAL_ARCHIVE_TOTAL_ENTITY
                )?.archivedCount ?: 0L
            )
            val latestArchive = archiveHistory.lastOrNull()
            val updatedLedger = ledger.copy(
                archivedCount = durableArchivedCount,
                lastReclaimedAtUtc = latestArchive?.archivedAtUtc ?: ledger.lastReclaimedAtUtc,
                lastReclaimedCount = latestArchive?.count ?: ledger.lastReclaimedCount,
                lastReclaimedReasons = latestArchive?.reasons ?: ledger.lastReclaimedReasons,
                history = archiveHistory
            )
            if (removed > 0L || ledger.lastError != null || !ledgerFile.isFile) {
                try {
                    beforeLedgerMirrorWrite()
                    writeLedger(updatedLedger)
                } catch (_: Exception) {
                    // The Room archive is authoritative and committed with deletion.
                }
            }

            UsageTerminalLifecycleSnapshot(
                rejectedCount = rejectedCount,
                reasons = stats.map { TerminalReasonCount(it.reason, it.count.toLong()) },
                oldestRejectedUpdatedAtUtc = oldest,
                reclaimableCount = reclaimableCount,
                retentionDays = retentionDays,
                nextEligibleAtUtc = nextEligible,
                archivedCount = durableArchivedCount,
                lastReclaimedAtUtc = updatedLedger.lastReclaimedAtUtc,
                lastReclaimedCount = updatedLedger.lastReclaimedCount,
                lastReclaimedReasons = updatedLedger.lastReclaimedReasons,
                archiveHistory = archiveHistory,
                lastError = updatedLedger.lastError
            ).also { _snapshot.value = it }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            lastError = redactor.redactText(ex.message ?: ex::class.java.simpleName).take(160)
            logs.warn(
                "diagnostics-lifecycle",
                "读取或清理 usage 死信失败：$lastError"
            )
            val fallbackLedger = readLedger()
            val fallbackStats = runCatching {
                sanitizeStats(dao.rejectedUsageSummaryStats(limit = MAX_REASONS))
            }.getOrDefault(emptyList())
            val fallbackRejected = runCatching {
                dao.countUsageSummariesBySyncStatus(MobileSyncStatus.REJECTED).toLong()
            }.getOrDefault(_snapshot.value.rejectedCount)
            val fallbackReclaimable = runCatching {
                dao.countRejectedUsageSummariesOlderThan(cutoff).toLong()
            }.getOrDefault(_snapshot.value.reclaimableCount)
            val fallbackArchiveRows = runCatching {
                dao.usageTerminalArchiveRows(
                    entityType = USAGE_TERMINAL_ARCHIVE_ENTITY,
                    limit = MAX_ARCHIVE_HISTORY
                )
            }.getOrDefault(emptyList())
            val fallbackDurableHistory = fallbackArchiveRows
                .mapNotNull(::parseArchiveRow)
                .reversed()
            val fallbackHistory = if (fallbackDurableHistory.isNotEmpty()) {
                fallbackDurableHistory
            } else {
                fallbackLedger.history
            }
            val fallbackArchivedCount = maxOf(
                fallbackLedger.archivedCount,
                runCatching {
                    dao.usageTerminalArchiveTotal(
                        batchId = USAGE_TERMINAL_ARCHIVE_TOTAL_BATCH_ID,
                        entityType = USAGE_TERMINAL_ARCHIVE_TOTAL_ENTITY
                    )?.archivedCount ?: 0L
                }.getOrDefault(0L)
            )
            val fallbackLatest = fallbackHistory.lastOrNull()
            UsageTerminalLifecycleSnapshot(
                rejectedCount = fallbackRejected,
                reasons = fallbackStats,
                oldestRejectedUpdatedAtUtc = _snapshot.value.oldestRejectedUpdatedAtUtc,
                reclaimableCount = fallbackReclaimable,
                retentionDays = retentionDays,
                nextEligibleAtUtc = _snapshot.value.nextEligibleAtUtc,
                archivedCount = fallbackArchivedCount,
                lastReclaimedAtUtc = fallbackLatest?.archivedAtUtc
                    ?: fallbackLedger.lastReclaimedAtUtc,
                lastReclaimedCount = fallbackLatest?.count ?: fallbackLedger.lastReclaimedCount,
                lastReclaimedReasons = fallbackLatest?.reasons
                    ?: fallbackLedger.lastReclaimedReasons,
                archiveHistory = fallbackHistory,
                lastError = lastError
            ).also { _snapshot.value = it }
        }
    }

    private fun archivePageJson(
        archivedAtUtc: Long,
        cutoffUtc: Long,
        ids: List<Long>,
        reasons: List<TerminalReasonCount>
    ): JSONObject = JSONObject()
        .put("version", 1)
        .put("archivedAtUtc", archivedAtUtc)
        .put("cutoffUtc", cutoffUtc)
        .put("rowCount", ids.size)
        .put("reasons", reasonsJson(reasons))

    private fun parseArchiveRow(
        row: com.pim.app.data.UsageTerminalArchiveRow
    ): TerminalArchiveEntry? {
        val json = row.requestJson?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        val count = maxOf(row.rowCount.toLong(), json?.optLong("rowCount", 0L) ?: 0L)
            .coerceAtLeast(0L)
        if (count <= 0L) return null
        return TerminalArchiveEntry(
            archivedAtUtc = row.createdAtUtc,
            cutoffUtc = json?.optLong("cutoffUtc", 0L) ?: 0L,
            count = count,
            reasons = parseReasons(json?.optJSONArray("reasons"))
        )
    }

    private fun mergeReasons(
        current: List<TerminalReasonCount>,
        added: List<TerminalReasonCount>
    ): List<TerminalReasonCount> {
        val counts = linkedMapOf<String, Long>()
        (current + added).forEach { item ->
            counts[item.reason] = (counts[item.reason] ?: 0L) + item.count
        }
        return counts
            .map { (reason, count) -> TerminalReasonCount(reason, count) }
            .sortedWith(compareByDescending<TerminalReasonCount> { it.count }.thenBy { it.reason })
            .take(MAX_REASONS)
    }

    private fun sanitizeStats(stats: List<RejectedUsageSummaryStat>): List<TerminalReasonCount> =
        stats
            .sortedWith(compareByDescending<RejectedUsageSummaryStat> { it.count }
                .thenBy { it.reason })
            .take(MAX_REASONS)
            .map { stat ->
                TerminalReasonCount(
                    reason = redactor.redactText(stat.reason).take(MAX_REASON_CHARS),
                    count = stat.count
                )
            }

    private fun readLedger(): Ledger {
        val file = ledgerFile
        if (!file.isFile) return Ledger()
        if (file.length() > MAX_LEDGER_BYTES) {
            return Ledger(lastError = "ledger-too-large")
        }
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            Ledger(
                archivedCount = json.optLong("archivedCount", 0L).coerceAtLeast(0L),
                lastReclaimedAtUtc = json.optLongOrNull("lastReclaimedAtUtc"),
                lastReclaimedCount = json.optLong("lastReclaimedCount", 0L)
                    .coerceAtLeast(0L),
                lastReclaimedReasons = parseReasons(json.optJSONArray("lastReclaimedReasons")),
                lastError = if (json.isNull("lastError")) {
                    null
                } else {
                    json.optString("lastError")
                        .takeIf { it.isNotBlank() }
                        ?.let { redactor.redactText(it).take(160) }
                },
                history = parseHistory(json.optJSONArray("history"))
            )
        } catch (ex: Exception) {
            Ledger(lastError = "ledger-corrupt")
        }
    }

    private fun writeLedger(ledger: Ledger) {
        val file = ledgerFile
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val json = JSONObject()
            .put("version", 1)
            .put("archivedCount", ledger.archivedCount)
            .put("lastReclaimedAtUtc", ledger.lastReclaimedAtUtc ?: JSONObject.NULL)
            .put("lastReclaimedCount", ledger.lastReclaimedCount)
            .put("lastReclaimedReasons", reasonsJson(ledger.lastReclaimedReasons))
            .put("lastError", ledger.lastError ?: JSONObject.NULL)
            .put(
                "history",
                JSONArray().apply {
                    ledger.history.forEach { entry ->
                        put(
                            JSONObject()
                                .put("archivedAtUtc", entry.archivedAtUtc)
                                .put("cutoffUtc", entry.cutoffUtc)
                                .put("count", entry.count)
                                .put("reasons", reasonsJson(entry.reasons))
                        )
                    }
                }
            )
        tmp.writeText(json.toString(), Charsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun parseReasons(array: JSONArray?): List<TerminalReasonCount> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val reason = item.optString("reason").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            TerminalReasonCount(
                reason = redactor.redactText(reason).take(MAX_REASON_CHARS),
                count = item.optLong("count", 0L).coerceAtLeast(0L)
            )
        }
    }

    private fun parseHistory(array: JSONArray?): List<TerminalArchiveEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val count = item.optLong("count", 0L).coerceAtLeast(0L)
            if (count <= 0L) return@mapNotNull null
            TerminalArchiveEntry(
                archivedAtUtc = item.optLong("archivedAtUtc", 0L),
                cutoffUtc = item.optLong("cutoffUtc", 0L),
                count = count,
                reasons = parseReasons(item.optJSONArray("reasons"))
            )
        }.takeLast(MAX_ARCHIVE_HISTORY)
    }

    private fun reasonsJson(reasons: List<TerminalReasonCount>): JSONArray =
        JSONArray().apply {
            reasons.forEach { reason ->
                put(
                    JSONObject()
                        .put("reason", reason.reason)
                        .put("count", reason.count)
                )
            }
        }

    private data class Ledger(
        val archivedCount: Long = 0L,
        val lastReclaimedAtUtc: Long? = null,
        val lastReclaimedCount: Long = 0L,
        val lastReclaimedReasons: List<TerminalReasonCount> = emptyList(),
        val lastError: String? = null,
        val history: List<TerminalArchiveEntry> = emptyList()
    )

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_PAGE_SIZE = 500
        const val DEFAULT_MAX_RECLAIM_ROWS_PER_PASS = 5_000
        const val MAX_ARCHIVE_HISTORY = 32
        const val MAX_REASONS = 20
        const val MAX_SYNC_HISTORY_ROWS = 2_000
        const val MAX_LEDGER_BYTES = 256 * 1024L
        const val USAGE_TERMINAL_ARCHIVE_ENTITY = "usage-terminal-archive"
        private const val USAGE_TERMINAL_ARCHIVE_TOTAL_ENTITY = "usage-terminal-archive-total"
        private const val USAGE_TERMINAL_ARCHIVE_TOTAL_BATCH_ID = "usage-terminal-archive-total-v1"
        private const val ARCHIVED_STATUS = "archived"
        private const val MAX_REASON_CHARS = 160
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name, 0L).takeIf { it > 0L }
}

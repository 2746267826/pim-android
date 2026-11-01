package com.pim.app.data

data class DiagnosticDatabaseCounts(
    val appUsageRowCount: Long,
    val mobileUsageEventsRowCount: Long,
    val mobileUsageSummariesRowCount: Long,
    val mobileAppMetadataRowCount: Long,
    val mobileLocationPointsRowCount: Long,
    /** Raw ledger count; it includes pre-v5 unkeyed rows not safe to retroactively collapse. */
    val mobileLocationDroppedDiagnosticsRowCount: Long,
    val mobileLocationPolicyTransitionsRowCount: Long,
    val mobileSyncBatchesRowCount: Long,
    val mobileLogsRowCount: Long,
    val mobileDeviceProfileRowCount: Long,
    val mobileForensicEventsRowCount: Long
)

/**
 * #60 storage boundary: v4 rows lack provider timestamp/coordinates in storage,
 * so v5 preserves their raw count with null keys and cannot backfill duplicates.
 * Keyed rows represent idempotent writes from v5 onward; legacy rows are not
 * represented as retroactively deduplicated.
 */
data class DroppedDiagnosticStorageStats(
    val totalRowCount: Long,
    val keyedRowCount: Long,
    val legacyUnkeyedRowCount: Long,
    val distinctKeyedFixCount: Long
)

data class DiagnosticSyncHistoryRow(
    val entityType: String,
    val rowCount: Int,
    val startedAtUtc: Long?,
    val finishedAtUtc: Long?,
    val syncStatus: String,
    val createdAtUtc: Long,
    val failureReason: String? = null
)

data class RejectedUsageSummaryStat(
    val reason: String,
    val count: Long,
    val oldestUpdatedAtUtc: Long,
    val newestUpdatedAtUtc: Long
)

data class DiagnosticLocationRow(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val provider: String?,
    val recordedAtUtc: Long,
    val source: String,
    val policyMode: String,
    val scheduleLowFrequency: Boolean,
    val motionState: String?,
    val syncStatus: String
)

/** 丢弃原因明细的导出行（REQ-9 / AC-9.1）：时刻 / 原因 / 准确度 / provider / 策略档。 */
data class DiagnosticDroppedDetailRow(
    val id: Long,
    val recordedAtUtc: Long,
    val reason: String,
    val accuracyMeters: Float?,
    val provider: String?,
    val policyMode: String
)

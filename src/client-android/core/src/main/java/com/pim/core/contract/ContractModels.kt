// ---------------------------------------------------------------------------
// 本文件由工具生成，请勿手工修改 / GENERATED FILE — DO NOT EDIT BY HAND.
//
//   契约快照 : contract/openapi.json (sha256 3dba28c1efb132d96806fff0b6f6919d4bc8e8054b97ab9be2497635a1ea19c6)
//   生成器   : scripts/contract/generate-kotlin.py
//   重新生成 : bash scripts/ci/generate-contract-kotlin.sh
//   一致性   : bash scripts/ci/check-contract-kotlin.sh（CI 逐字节比对，改了就红）
//
// 只覆盖 scripts/contract/client-operations.json 声明的 operation 的 schema 闭包；
// 需要新接口时先让后端在 pim-api 实现并刷新契约，再往清单里加，不要在客户端造类型。
// ---------------------------------------------------------------------------
package com.pim.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 契约 `components.schemas.AuthResponse`（response）。 */
@Serializable
data class AuthResponse(
    val accessToken: String,
    val expiresAt: String,
    val refreshToken: String,
    val user: UserInfo,
)

/** 契约 `components.schemas.AuthResponseApiResponse`（response）。 */
@Serializable
data class AuthResponseApiResponse(
    val code: Int,
    val data: AuthResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.CalendarOperationResult`（response）。 */
@Serializable
data class CalendarOperationResult(
    val affectedCount: Int,
    val affectedIds: List<String>,
    val message: String,
    val operation: String,
    val operationId: String,
    val samples: List<CalendarOperationSample>,
)

/** 契约 `components.schemas.CalendarOperationResultApiResponse`（response）。 */
@Serializable
data class CalendarOperationResultApiResponse(
    val code: Int,
    val data: CalendarOperationResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.CalendarOperationSample`（response）。 */
@Serializable
data class CalendarOperationSample(
    val bookName: String? = null,
    val end: String? = null,
    val id: String,
    val start: String? = null,
    val title: String,
    val type: String,
)

/** 契约 `components.schemas.CalendarResponse`（response）。 */
@Serializable
data class CalendarResponse(
    val canEdit: Boolean? = null,
    val color: String,
    val defaultMuted: Boolean? = null,
    val eventCount: Int,
    val id: String,
    val isDefault: Boolean,
    val kind: String,
    val name: String,
    val outlookCalendarBindingId: String? = null,
    val source: String? = null,
)

/** 契约 `components.schemas.CalendarResponseApiResponse`（response）。 */
@Serializable
data class CalendarResponseApiResponse(
    val code: Int,
    val data: CalendarResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.CalendarResponseListApiResponse`（response）。 */
@Serializable
data class CalendarResponseListApiResponse(
    val code: Int,
    val data: List<CalendarResponse>? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.ClientShellLatestResponse`（response）。 */
@Serializable
data class ClientShellLatestResponse(
    val androidUrl: String? = null,
    val androidVersion: String? = null,
    val checkedAt: String? = null,
    val error: String? = null,
    val shellAndroidUrl: String? = null,
    val shellAndroidVersion: String? = null,
    val shellWindowsUrl: String? = null,
    val shellWindowsVersion: String? = null,
    val windowsUrl: String? = null,
    val windowsVersion: String? = null,
)

/** 契约 `components.schemas.CreateCalendarRequest`（request）。 */
@Serializable
data class CreateCalendarRequest(
    val color: String? = null,
    val kind: String? = null,
    val name: String,
)

/** 契约 `components.schemas.CreateEventRequest`（request）。 */
@Serializable
data class CreateEventRequest(
    val attachmentReferences: List<EventAttachmentReferenceDto>? = null,
    val attendees: List<EventAttendeeDto>? = null,
    val calendarId: String,
    val categories: List<String>? = null,
    val description: String? = null,
    val descriptionFormat: String? = null,
    val dtEnd: String,
    val dtStart: String,
    val externalLink: String? = null,
    val importance: String? = null,
    val isAllDay: Boolean? = null,
    val isException: Boolean? = null,
    val isMuted: Boolean? = null,
    val isOnlineMeeting: Boolean? = null,
    val isReminderOn: Boolean? = null,
    val isSeriesMaster: Boolean? = null,
    val location: String? = null,
    val onlineMeetingProvider: String? = null,
    val onlineMeetingUrl: String? = null,
    val organizer: EventPersonDto? = null,
    val rRule: String? = null,
    val recurrenceId: String? = null,
    val reminderMinutesBeforeStart: Int? = null,
    val sensitivity: String? = null,
    val seriesMasterId: String? = null,
    val showAs: String? = null,
    val timeZoneId: String? = null,
    val title: String,
    val uid: String? = null,
)

/** 契约 `components.schemas.CreateTaskRequest`（request）。 */
@Serializable
data class CreateTaskRequest(
    val calendarId: String? = null,
    val description: String? = null,
    val dtStart: String? = null,
    val due: String? = null,
    val estimatedDuration: String? = null,
    val minimumSegment: String? = null,
    val percentComplete: Int? = null,
    val plannedEnd: String? = null,
    val priority: Int,
    val status: String? = null,
    val taskBookId: String? = null,
    val title: String,
)

/** 契约 `components.schemas.DaemonHeartbeatDto`（response）。 */
@Serializable
data class DaemonHeartbeatDto(
    val activityWatchState: DaemonSourceState,
    val collectionPaused: Boolean,
    val daemonKind: String,
    val deviceId: String,
    val keyStatsState: DaemonSourceState,
    val lastAttemptedUploadAt: String? = null,
    val lastError: String? = null,
    val lastSuccessfulUploadAt: String? = null,
    val offlineReason: String? = null,
    val plannedOfflineAt: String? = null,
    val receivedAt: String,
    val serverUrl: String,
    val statusJson: String,
    val uploadQueueCount: Int? = null,
    val version: String,
)

/** 契约 `components.schemas.DaemonHeartbeatDtoApiResponse`（response）。 */
@Serializable
data class DaemonHeartbeatDtoApiResponse(
    val code: Int,
    val data: DaemonHeartbeatDto? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.DaemonHeartbeatRequest`（request）。 */
@Serializable
data class DaemonHeartbeatRequest(
    val activityWatchState: DaemonSourceState,
    val collectionPaused: Boolean,
    val daemonKind: String,
    val deviceId: String,
    val keyStatsState: DaemonSourceState,
    val lastAttemptedUploadAt: String? = null,
    val lastError: String? = null,
    val lastSuccessfulUploadAt: String? = null,
    val serverUrl: String,
    val statusJson: String,
    val uploadQueueCount: Int? = null,
    val version: String,
)

/** 契约 `components.schemas.DaemonSourceState`（request+response）。 */
/**
 * 契约枚举 `DaemonSourceState`：wire 值由 `@SerialName` 固定，写错取值编译期就不通过。
 */
@Serializable
enum class DaemonSourceState {
    @SerialName("Unknown")
    UNKNOWN,
    @SerialName("Available")
    AVAILABLE,
    @SerialName("Unavailable")
    UNAVAILABLE,
    @SerialName("Paused")
    PAUSED,
}

/** 契约 `components.schemas.EndpointNotificationActionRequest`（request）。 */
@Serializable
data class EndpointNotificationActionRequest(
    val action: String,
    val confirmationId: String? = null,
    val relatedObjectId: String? = null,
    val relatedObjectType: String? = null,
    val riskLevel: String,
)

/** 契约 `components.schemas.EndpointNotificationActionResponse`（response）。 */
@Serializable
data class EndpointNotificationActionResponse(
    val detailUrl: String? = null,
    val message: String? = null,
    val result: String,
)

/** 契约 `components.schemas.EndpointNotificationActionResponseApiResponse`（response）。 */
@Serializable
data class EndpointNotificationActionResponseApiResponse(
    val code: Int,
    val data: EndpointNotificationActionResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.EventAttachmentReferenceDto`（request+response）。 */
@Serializable
data class EventAttachmentReferenceDto(
    val canDownload: Boolean? = null,
    val contentType: String? = null,
    val id: String,
    val kind: String,
    val name: String,
    val size: Long? = null,
)

/** 契约 `components.schemas.EventAttendeeDto`（request+response）。 */
@Serializable
data class EventAttendeeDto(
    val email: String,
    val name: String? = null,
    val type: String? = null,
)

/** 契约 `components.schemas.EventPersonDto`（request+response）。 */
@Serializable
data class EventPersonDto(
    val email: String? = null,
    val name: String? = null,
)

/** 契约 `components.schemas.EventResponse`（response）。 */
@Serializable
data class EventResponse(
    val attachmentReferences: List<EventAttachmentReferenceDto>? = null,
    val attendees: List<EventAttendeeDto>? = null,
    val calendarId: String,
    val categories: List<String>? = null,
    val description: String? = null,
    val descriptionFormat: String? = null,
    val dtEnd: String,
    val dtStart: String,
    val exDatesJson: String? = null,
    val externalLink: String? = null,
    val id: String,
    val importance: String? = null,
    val isAllDay: Boolean? = null,
    val isCancelled: Boolean? = null,
    val isException: Boolean? = null,
    val isMuted: Boolean? = null,
    val isOnlineMeeting: Boolean? = null,
    val isReminderOn: Boolean? = null,
    val isSeriesMaster: Boolean? = null,
    val location: String? = null,
    val onlineMeetingProvider: String? = null,
    val onlineMeetingUrl: String? = null,
    val organizer: EventPersonDto? = null,
    val originalEventId: String? = null,
    val outlookAdditionalInfo: OutlookAdditionalInfoDto? = null,
    val outlookCalendarBindingId: String? = null,
    val outlookEtag: String? = null,
    val outlookEventId: String? = null,
    val outlookEventType: String? = null,
    val rRule: String? = null,
    val recurrenceId: String? = null,
    val recurrenceMetadataJson: String? = null,
    val reminderMinutesBeforeStart: Int? = null,
    val sensitivity: String? = null,
    val seriesMasterId: String? = null,
    val showAs: String? = null,
    val source: String,
    val sourceTimeZoneId: String? = null,
    val sourceUid: String? = null,
    val status: String,
    val timeZoneId: String? = null,
    val title: String,
    val uid: String,
)

/** 契约 `components.schemas.EventResponseApiResponse`（response）。 */
@Serializable
data class EventResponseApiResponse(
    val code: Int,
    val data: EventResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.EventResponsePagedResult`（response）。 */
@Serializable
data class EventResponsePagedResult(
    val items: List<EventResponse>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val totalPages: Int,
)

/** 契约 `components.schemas.EventResponsePagedResultApiResponse`（response）。 */
@Serializable
data class EventResponsePagedResultApiResponse(
    val code: Int,
    val data: EventResponsePagedResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.IcsImportForm`（request）。 */
@Serializable
data class IcsImportForm(
    val calendarId: String? = null,
    val file: String,
)

/** 契约 `components.schemas.ImportReport`（response）。 */
@Serializable
data class ImportReport(
    val imported: Int,
    val samples: List<ImportSkippedItem>,
    val skipped: Int,
    val skippedReasons: Map<String, Int>,
)

/** 契约 `components.schemas.ImportReportApiResponse`（response）。 */
@Serializable
data class ImportReportApiResponse(
    val code: Int,
    val data: ImportReport? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.ImportSkippedItem`（response）。 */
@Serializable
data class ImportSkippedItem(
    val reason: String,
    val start: String? = null,
    val title: String,
    val uid: String? = null,
)

/** 契约 `components.schemas.LoginRequest`（request）。 */
@Serializable
data class LoginRequest(
    val password: String,
    val username: String,
)

/** 契约 `components.schemas.MobileAnalyticsRangeDto`（response）。 */
@Serializable
data class MobileAnalyticsRangeDto(
    val localEndDate: String,
    val localStartDate: String,
    val rangeEndUtc: String,
    val rangeStartUtc: String,
    val timezone: String,
)

/** 契约 `components.schemas.MobileAppMetadataDto`（request）。 */
@Serializable
data class MobileAppMetadataDto(
    val category: String? = null,
    val categoryName: String? = null,
    val clientItemKey: String? = null,
    val displayName: String,
    val firstInstallTimeUtc: String? = null,
    val installerPackage: String? = null,
    val installerPackageName: String? = null,
    val isSystemApp: Boolean,
    val lastUpdateTimeUtc: String? = null,
    val packageName: String,
    val rawJson: String,
    val versionCode: Long,
    val versionName: String? = null,
)

/** 契约 `components.schemas.MobileAppUsageSummaryDto`（response）。 */
@Serializable
data class MobileAppUsageSummaryDto(
    val categoryName: String? = null,
    val displayName: String,
    val foregroundSeconds: Long,
    val lastUsedAt: String? = null,
    val launchCount: Int,
    val packageName: String,
    val sessionCount: Int,
    val share: Double,
    val source: String,
)

/** 契约 `components.schemas.MobileDeviceDto`（response）。 */
@Serializable
data class MobileDeviceDto(
    val androidIdHash: String? = null,
    val androidVersion: String,
    val apiLevel: Int? = null,
    val appVersion: String,
    val brand: String,
    val deviceHash: String? = null,
    val deviceId: String,
    val displayName: String,
    val firstSeenAt: String,
    val id: String,
    val isActive: Boolean,
    val lastHeartbeatAt: String? = null,
    val lastSeenAt: String,
    val lastSyncAt: String? = null,
    val manufacturer: String,
    val metadataJson: String,
    val model: String,
    val osVersion: String? = null,
    val sdkInt: Int,
)

/** 契约 `components.schemas.MobileDeviceDtoApiResponse`（response）。 */
@Serializable
data class MobileDeviceDtoApiResponse(
    val code: Int,
    val data: MobileDeviceDto? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileDeviceRegisterRequest`（request）。 */
@Serializable
data class MobileDeviceRegisterRequest(
    val androidIdHash: String? = null,
    val androidVersion: String,
    val apiLevel: Int? = null,
    val appVersion: String,
    val brand: String,
    val deviceHash: String? = null,
    val deviceId: String,
    val displayName: String,
    val manufacturer: String,
    val metadataJson: String,
    val model: String,
    val osVersion: String? = null,
    val sdkInt: Int,
)

/** 契约 `components.schemas.MobileDroppedReasonSummaryItem`（request）。 */
@Serializable
data class MobileDroppedReasonSummaryItem(
    val count: Int,
    val localDate: String,
    val reason: String,
)

/** 契约 `components.schemas.MobileForensicEventUploadItem`（request）。 */
@Serializable
data class MobileForensicEventUploadItem(
    val clientItemKey: String,
    val eventType: String,
    val occurredAtUtc: String,
    val payloadJson: String? = null,
)

/** 契约 `components.schemas.MobileForensicsIngestResult`（response）。 */
@Serializable
data class MobileForensicsIngestResult(
    val acceptedCount: Int,
    val acceptedDroppedReasonCount: Int,
    val acceptedKeys: List<String>,
    val failedCount: Int,
    val rejectedCount: Int,
    val rejectedKeys: List<String>,
    val skippedCount: Int,
    val skippedKeys: List<String>,
)

/** 契约 `components.schemas.MobileForensicsIngestResultApiResponse`（response）。 */
@Serializable
data class MobileForensicsIngestResultApiResponse(
    val code: Int,
    val data: MobileForensicsIngestResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileForensicsUploadRequest`（request）。 */
@Serializable
data class MobileForensicsUploadRequest(
    val batchId: String? = null,
    val deviceId: String,
    val droppedReasonSummaries: List<MobileDroppedReasonSummaryItem>? = null,
    val events: List<MobileForensicEventUploadItem>? = null,
)

/** 契约 `components.schemas.MobileGapRequest`（request）。 */
@Serializable
data class MobileGapRequest(
    val capabilitiesJson: String? = null,
    val capabilityJson: String,
    val deviceId: String,
    val rangeEndUtc: String,
    val rangeStartUtc: String,
)

/** 契约 `components.schemas.MobileGapResponse`（response）。 */
@Serializable
data class MobileGapResponse(
    val maxBackfillStartUtc: String,
    val windows: List<MobileGapWindowDto>,
)

/** 契约 `components.schemas.MobileGapResponseApiResponse`（response）。 */
@Serializable
data class MobileGapResponseApiResponse(
    val code: Int,
    val data: MobileGapResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileGapWindowDto`（response）。 */
@Serializable
data class MobileGapWindowDto(
    val reason: String,
    val signalsJson: String? = null,
    val sourcePreference: String,
    val windowEndUtc: String,
    val windowStartUtc: String,
)

/** 契约 `components.schemas.MobileGeoBoundsDto`（response）。 */
@Serializable
data class MobileGeoBoundsDto(
    val maxLatitude: Double,
    val maxLongitude: Double,
    val minLatitude: Double,
    val minLongitude: Double,
)

/** 契约 `components.schemas.MobileIngestItemResult`（response）。 */
@Serializable
data class MobileIngestItemResult(
    val clientItemKey: String,
    val code: String,
    val entityType: String,
    val message: String,
    val outcome: String,
)

/** 契约 `components.schemas.MobileLocationAnalyticsOverviewResponse`（response）。 */
@Serializable
data class MobileLocationAnalyticsOverviewResponse(
    val activeSpanSeconds: Long,
    val averageAccuracyMeters: Double,
    val distanceMeters: Double,
    val generatedAt: String,
    val longestStaySeconds: Long,
    val pointCount: Int,
    val qualityFlags: List<String>,
    val qualityIssueCount: Int,
    val range: MobileAnalyticsRangeDto,
    val rejectedPointCount: Int,
    val stayCount: Int,
    val usablePointCount: Int,
)

/** 契约 `components.schemas.MobileLocationAnalyticsOverviewResponseApiResponse`（response）。 */
@Serializable
data class MobileLocationAnalyticsOverviewResponseApiResponse(
    val code: Int,
    val data: MobileLocationAnalyticsOverviewResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileLocationHistoryResponse`（response）。 */
@Serializable
data class MobileLocationHistoryResponse(
    val deviceId: String? = null,
    val end: String? = null,
    val maxAccuracyMeters: Double,
    val points: List<MobileLocationPointDto>,
    val start: String? = null,
)

/** 契约 `components.schemas.MobileLocationHistoryResponseApiResponse`（response）。 */
@Serializable
data class MobileLocationHistoryResponseApiResponse(
    val code: Int,
    val data: MobileLocationHistoryResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileLocationPathPointDto`（response）。 */
@Serializable
data class MobileLocationPathPointDto(
    val horizontalAccuracyMeters: Double,
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val quality: String,
    val qualityFlags: List<String>,
    val recordedAtUtc: String,
)

/** 契约 `components.schemas.MobileLocationPointDto`（response）。 */
@Serializable
data class MobileLocationPointDto(
    val altitudeMeters: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val bearingDegrees: Double? = null,
    val deviceId: String,
    val horizontalAccuracyMeters: Double,
    val id: String,
    val isAutoSubmitted: Boolean,
    val latitude: Double,
    val longitude: Double,
    val provider: String,
    val quality: String,
    val rawJson: String,
    val recordedAtUtc: String,
    val source: String? = null,
    val sourceKind: String,
    val speedAccuracyMetersPerSecond: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val submittedAtUtc: String,
    val verticalAccuracyMeters: Double? = null,
)

/** 契约 `components.schemas.MobileLocationPointDtoApiResponse`（response）。 */
@Serializable
data class MobileLocationPointDtoApiResponse(
    val code: Int,
    val data: MobileLocationPointDto? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileLocationPointRequest`（request）。 */
@Serializable
data class MobileLocationPointRequest(
    val altitudeMeters: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val bearingDegrees: Double? = null,
    val deviceId: String,
    val horizontalAccuracyMeters: Double,
    val isAutoSubmitted: Boolean,
    val isMock: Boolean? = null,
    val latitude: Double,
    val longitude: Double,
    val provider: String,
    val rawJson: String,
    val recordedAtUtc: String,
    val source: String? = null,
    val sourceKind: String,
    val speedAccuracyMetersPerSecond: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val verticalAccuracyMeters: Double? = null,
)

/** 契约 `components.schemas.MobileLocationPointsUploadRequest`（request）。 */
@Serializable
data class MobileLocationPointsUploadRequest(
    val points: List<MobileLocationPointRequest>,
)

/** 契约 `components.schemas.MobileLocationPointsUploadResult`（response）。 */
@Serializable
data class MobileLocationPointsUploadResult(
    val acceptedCount: Int,
    val itemResults: List<MobileIngestItemResult>,
    val rejectedCount: Int,
    val skippedCount: Int,
)

/** 契约 `components.schemas.MobileLocationPointsUploadResultApiResponse`（response）。 */
@Serializable
data class MobileLocationPointsUploadResultApiResponse(
    val code: Int,
    val data: MobileLocationPointsUploadResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileLocationSegmentDto`（response）。 */
@Serializable
data class MobileLocationSegmentDto(
    val averageAccuracyMeters: Double,
    val averageSpeedMetersPerSecond: Double,
    val bounds: MobileGeoBoundsDto? = null,
    val deviceId: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val endUtc: String,
    val id: String,
    val kind: String,
    val localEnd: String,
    val localStart: String,
    val maxAccuracyMeters: Double,
    val path: List<MobileLocationPathPointDto>,
    val pointCount: Int,
    val quality: String,
    val qualityFlags: List<String>,
    val startUtc: String,
    val trackId: String,
)

/** 契约 `components.schemas.MobileLocationSegmentPointPageDto`（response）。 */
@Serializable
data class MobileLocationSegmentPointPageDto(
    val hasMore: Boolean,
    val items: List<MobileLocationPointDto>,
    val nextCursor: String? = null,
)

/** 契约 `components.schemas.MobileLocationSegmentPointPageDtoApiResponse`（response）。 */
@Serializable
data class MobileLocationSegmentPointPageDtoApiResponse(
    val code: Int,
    val data: MobileLocationSegmentPointPageDto? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileLocationTrackDto`（response）。 */
@Serializable
data class MobileLocationTrackDto(
    val bounds: MobileGeoBoundsDto? = null,
    val deviceId: String,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val endUtc: String,
    val id: String,
    val pointCount: Int,
    val qualityFlags: List<String>,
    val segmentCount: Int,
    val segments: List<MobileLocationSegmentDto>,
    val startUtc: String,
)

/** 契约 `components.schemas.MobileLocationTrackDtoIReadOnlyListApiResponse`（response）。 */
@Serializable
data class MobileLocationTrackDtoIReadOnlyListApiResponse(
    val code: Int,
    val data: List<MobileLocationTrackDto>? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileQualityComponentDto`（response）。 */
@Serializable
data class MobileQualityComponentDto(
    val checkedAt: String,
    val details: Map<String, String>,
    val key: String,
    val message: String,
    val name: String,
    val status: PimHealthStatus,
)

/** 契约 `components.schemas.MobileQualityIssueDto`（response）。 */
@Serializable
data class MobileQualityIssueDto(
    val code: String,
    val componentKey: String,
    val message: String,
    val nextStep: String? = null,
    val severity: PimHealthStatus,
)

/** 契约 `components.schemas.MobileQualityResponse`（response）。 */
@Serializable
data class MobileQualityResponse(
    val checkedAt: String,
    val components: List<MobileQualityComponentDto>,
    val issues: List<MobileQualityIssueDto>,
    val label: String,
    val message: String,
    val nextSteps: List<String>,
    val overallStatus: PimHealthStatus,
)

/** 契约 `components.schemas.MobileQualityResponseApiResponse`（response）。 */
@Serializable
data class MobileQualityResponseApiResponse(
    val code: Int,
    val data: MobileQualityResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileSyncBatchSummaryDto`（response）。 */
@Serializable
data class MobileSyncBatchSummaryDto(
    val acceptedEventCount: Int,
    val acceptedLocationCount: Int,
    val clientBatchId: String,
    val deviceId: String,
    val errorMessage: String? = null,
    val id: String,
    val rejectedItemCount: Int,
    val rejectedLocationCount: Int,
    val skippedEventCount: Int,
    val sourceWindowEndUtc: String,
    val sourceWindowStartUtc: String,
    val status: String,
    val submittedAtUtc: String,
)

/** 契约 `components.schemas.MobileTimelineItemDto`（response）。 */
@Serializable
data class MobileTimelineItemDto(
    val confidence: Double,
    val deviceId: String,
    val displayName: String,
    val durationSeconds: Long,
    val end: String? = null,
    val id: String,
    val kind: String,
    val packageName: String,
    val reason: String,
    val source: String,
    val start: String,
)

/** 契约 `components.schemas.MobileTimelineResponse`（response）。 */
@Serializable
data class MobileTimelineResponse(
    val date: String,
    val deviceId: String? = null,
    val fallbackSummaries: List<MobileTimelineItemDto>,
    val fallbackTotalCount: Int? = null,
    val generatedAt: String,
    val hasMore: Boolean? = null,
    val items: List<MobileTimelineItemDto>,
    val page: Int? = null,
    val pageSize: Int? = null,
    val sessionTotalCount: Int? = null,
    val sessions: List<MobileTimelineItemDto>,
    val totalCount: Int? = null,
    val truncated: Boolean? = null,
)

/** 契约 `components.schemas.MobileTimelineResponseApiResponse`（response）。 */
@Serializable
data class MobileTimelineResponseApiResponse(
    val code: Int,
    val data: MobileTimelineResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileUsageEventDto`（request）。 */
@Serializable
data class MobileUsageEventDto(
    val className: String? = null,
    val clientItemKey: String? = null,
    val collectedAtUtc: String,
    val eventTimestampUtc: String,
    val eventType: String,
    val packageName: String,
    val rawJson: String,
)

/** 契约 `components.schemas.MobileUsageEventsUploadRequest`（request）。 */
@Serializable
data class MobileUsageEventsUploadRequest(
    val apps: List<MobileAppMetadataDto>,
    val batchId: String? = null,
    val clientBatchId: String,
    val deviceId: String,
    val events: List<MobileUsageEventDto>,
    val fallbackSummaries: List<MobileUsageSummaryDto>,
    val sourceWindowEndUtc: String,
    val sourceWindowStartUtc: String,
    val summaries: List<MobileUsageSummaryDto>? = null,
    val windowEndUtc: String? = null,
    val windowStartUtc: String? = null,
)

/** 契约 `components.schemas.MobileUsageIngestResult`（response）。 */
@Serializable
data class MobileUsageIngestResult(
    val acceptedCount: Int,
    val batchId: String,
    val failedCount: Int,
    val itemResults: List<MobileIngestItemResult>,
    val rejectedCount: Int,
    val skippedCount: Int,
)

/** 契约 `components.schemas.MobileUsageIngestResultApiResponse`（response）。 */
@Serializable
data class MobileUsageIngestResultApiResponse(
    val code: Int,
    val data: MobileUsageIngestResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.MobileUsageSummaryDto`（request）。 */
@Serializable
data class MobileUsageSummaryDto(
    val clientItemKey: String? = null,
    val lastTimeUsedUtc: String? = null,
    val packageName: String,
    val rawJson: String,
    val sourceKind: String,
    val totalTimeForegroundMs: Long,
    val totalTimeVisibleMs: Long? = null,
    val windowEndUtc: String,
    val windowStartUtc: String,
)

/** 契约 `components.schemas.MobileUsageSummaryResponse`（response）。 */
@Serializable
data class MobileUsageSummaryResponse(
    val appRanking: List<MobileAppUsageSummaryDto>,
    val appSwitchCount: Int,
    val appsUsed: Int,
    val completeness: Double,
    val date: String,
    val deviceId: String? = null,
    val fallbackForegroundSeconds: Long,
    val generatedAt: String,
    val lastSyncAt: String? = null,
    val qualityIssueCount: Int,
    val syncBatches: List<MobileSyncBatchSummaryDto>,
    val totalForegroundSeconds: Long,
)

/** 契约 `components.schemas.MobileUsageSummaryResponseApiResponse`（response）。 */
@Serializable
data class MobileUsageSummaryResponseApiResponse(
    val code: Int,
    val data: MobileUsageSummaryResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.OutlookAdditionalInfoDto`（response）。 */
@Serializable
data class OutlookAdditionalInfoDto(
    val groups: List<OutlookAdditionalInfoGroupDto>,
    val hiddenFieldCount: Int,
)

/** 契约 `components.schemas.OutlookAdditionalInfoGroupDto`（response）。 */
@Serializable
data class OutlookAdditionalInfoGroupDto(
    val items: List<OutlookAdditionalInfoItemDto>,
    val key: String,
    val label: String,
)

/** 契约 `components.schemas.OutlookAdditionalInfoItemDto`（response）。 */
@Serializable
data class OutlookAdditionalInfoItemDto(
    val key: String,
    val label: String,
    val value: String,
)

/** 契约 `components.schemas.OutlookSyncBatchResponse`（response）。 */
@Serializable
data class OutlookSyncBatchResponse(
    val cancelRequested: Boolean? = null,
    val confirmationCount: Int,
    val conflictCount: Int,
    val createdCount: Int,
    val errorSummary: String? = null,
    val failureCount: Int,
    val finishedAt: String? = null,
    val id: String,
    val mode: String? = null,
    val perCalendarJson: String? = null,
    val provider: String,
    val readCount: Int,
    val requestedWindowEnd: String? = null,
    val requestedWindowStart: String? = null,
    val startedAt: String,
    val status: String,
    val steps: List<OutlookSyncStep>,
    val updatedCount: Int,
)

/** 契约 `components.schemas.OutlookSyncBatchResponseApiResponse`（response）。 */
@Serializable
data class OutlookSyncBatchResponseApiResponse(
    val code: Int,
    val data: OutlookSyncBatchResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.OutlookSyncRequest`（request）。 */
@Serializable
data class OutlookSyncRequest(
    val calendarBindingIds: List<String>? = null,
    val mode: String,
    val rangeEnd: String? = null,
    val rangeStart: String? = null,
    val retryOfBatchId: String? = null,
)

/** 契约 `components.schemas.OutlookSyncStep`（response）。 */
@Serializable
data class OutlookSyncStep(
    val at: String,
    val detail: String,
    val name: String,
    val status: String,
)

/** 契约 `components.schemas.PimHealthStatus`（response）。 */
/**
 * 契约枚举 `PimHealthStatus`（整数，取值：0, 1, 2, 3）。
 * 契约没有给出成员名，臆造名字等于编造接口，因此这里保留契约类型名但按 Int 处理。
 */
typealias PimHealthStatus = Int

/** 契约 `components.schemas.RefreshRequest`（request）。 */
@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

/** 契约 `components.schemas.RegisterRequest`（request）。 */
@Serializable
data class RegisterRequest(
    val displayName: String? = null,
    val email: String,
    val password: String,
    val username: String,
)

/** 契约 `components.schemas.SearchResult`（response）。 */
@Serializable
data class SearchResult(
    val id: String,
    val moduleName: String,
    val snippet: String,
    val title: String,
    val type: String,
    val url: String,
)

/** 契约 `components.schemas.SearchResultPagedResult`（response）。 */
@Serializable
data class SearchResultPagedResult(
    val items: List<SearchResult>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val totalPages: Int,
)

/** 契约 `components.schemas.SearchResultPagedResultApiResponse`（response）。 */
@Serializable
data class SearchResultPagedResultApiResponse(
    val code: Int,
    val data: SearchResultPagedResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.StringApiResponse`（response）。 */
@Serializable
data class StringApiResponse(
    val code: Int,
    val data: String? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.TaskResponse`（response）。 */
@Serializable
data class TaskResponse(
    val calendarId: String? = null,
    val description: String? = null,
    val dtStart: String? = null,
    val due: String? = null,
    val estimatedDuration: String? = null,
    val id: String,
    val isInbox: Boolean,
    val minimumSegment: String? = null,
    val percentComplete: Int? = null,
    val plannedEnd: String? = null,
    val priority: Int,
    val sortOrder: Int,
    val status: String,
    val subTasks: List<TaskResponse>,
    val taskBookId: String? = null,
    val title: String,
    val uid: String,
)

/** 契约 `components.schemas.TaskResponseApiResponse`（response）。 */
@Serializable
data class TaskResponseApiResponse(
    val code: Int,
    val data: TaskResponse? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.TaskResponsePagedResult`（response）。 */
@Serializable
data class TaskResponsePagedResult(
    val items: List<TaskResponse>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val totalPages: Int,
)

/** 契约 `components.schemas.TaskResponsePagedResultApiResponse`（response）。 */
@Serializable
data class TaskResponsePagedResultApiResponse(
    val code: Int,
    val data: TaskResponsePagedResult? = null,
    val message: String,
    val timestamp: String,
)

/** 契约 `components.schemas.UpdateEventRequest`（request）。 */
@Serializable
data class UpdateEventRequest(
    val attachmentReferences: List<EventAttachmentReferenceDto>? = null,
    val attendees: List<EventAttendeeDto>? = null,
    val calendarId: String,
    val categories: List<String>? = null,
    val description: String? = null,
    val descriptionFormat: String? = null,
    val dtEnd: String,
    val dtStart: String,
    val externalLink: String? = null,
    val importance: String? = null,
    val isAllDay: Boolean? = null,
    val isException: Boolean? = null,
    val isMuted: Boolean? = null,
    val isOnlineMeeting: Boolean? = null,
    val isReminderOn: Boolean? = null,
    val isSeriesMaster: Boolean? = null,
    val location: String? = null,
    val onlineMeetingProvider: String? = null,
    val onlineMeetingUrl: String? = null,
    val organizer: EventPersonDto? = null,
    val rRule: String? = null,
    val recurrenceId: String? = null,
    val reminderMinutesBeforeStart: Int? = null,
    val sensitivity: String? = null,
    val seriesMasterId: String? = null,
    val showAs: String? = null,
    val timeZoneId: String? = null,
    val title: String,
    val uid: String? = null,
)

/** 契约 `components.schemas.UpdateTaskRequest`（request）。 */
@Serializable
data class UpdateTaskRequest(
    val calendarId: String? = null,
    val description: String? = null,
    val dtStart: String? = null,
    val due: String? = null,
    val estimatedDuration: String? = null,
    val minimumSegment: String? = null,
    val percentComplete: Int? = null,
    val plannedEnd: String? = null,
    val priority: Int,
    val status: String? = null,
    val taskBookId: String? = null,
    val title: String,
)

/** 契约 `components.schemas.UserInfo`（response）。 */
@Serializable
data class UserInfo(
    val displayName: String? = null,
    val id: String,
    val role: String,
    val username: String,
)

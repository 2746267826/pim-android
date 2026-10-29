package com.pim.core.network

import com.pim.core.contract.BeginNoteMaterialUploadRequest
import com.pim.core.contract.NoteMaterialUploadDtoApiResponse
import com.pim.core.contract.NoteMaterialVersionDtoApiResponse

import com.pim.core.contract.AuthResponseApiResponse
import com.pim.core.contract.CalendarOperationResultApiResponse
import com.pim.core.contract.CalendarResponseApiResponse
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.ClientShellLatestResponse
import com.pim.core.contract.CreateCalendarRequest
import com.pim.core.contract.CreateEventRequest
import com.pim.core.contract.CreateTaskRequest
import com.pim.core.contract.DaemonHeartbeatDtoApiResponse
import com.pim.core.contract.DaemonHeartbeatRequest
import com.pim.core.contract.EndpointHeartbeatRequest
import com.pim.core.contract.EndpointNotificationActionRequest
import com.pim.core.contract.EndpointNotificationActionResponseApiResponse
import com.pim.core.contract.EndpointStatusDtoApiResponse
import com.pim.core.contract.EventResponseApiResponse
import com.pim.core.contract.EventResponsePagedResultApiResponse
import com.pim.core.contract.ImportReportApiResponse
import com.pim.core.contract.LoginRequest
import com.pim.core.contract.MobileDeviceDtoApiResponse
import com.pim.core.contract.MobileDeviceRegisterRequest
import com.pim.core.contract.MobileForensicsIngestResultApiResponse
import com.pim.core.contract.MobileForensicsUploadRequest
import com.pim.core.contract.MobileGapRequest
import com.pim.core.contract.MobileGapResponseApiResponse
import com.pim.core.contract.MobileLocationAnalyticsOverviewResponseApiResponse
import com.pim.core.contract.MobileLocationHistoryResponseApiResponse
import com.pim.core.contract.MobileLocationPointDtoApiResponse
import com.pim.core.contract.MobileLocationPointRequest
import com.pim.core.contract.MobileLocationPointsUploadRequest
import com.pim.core.contract.MobileLocationPointsUploadResultApiResponse
import com.pim.core.contract.MobileLocationSegmentPointPageDtoApiResponse
import com.pim.core.contract.MobileLocationTrackDtoIReadOnlyListApiResponse
import com.pim.core.contract.MobileQualityResponseApiResponse
import com.pim.core.contract.MobileTimelineResponseApiResponse
import com.pim.core.contract.MobileUsageEventsUploadRequest
import com.pim.core.contract.MobileUsageIngestResultApiResponse
import com.pim.core.contract.MobileUsageSummaryResponseApiResponse
import com.pim.core.contract.OutlookSyncBatchResponseApiResponse
import com.pim.core.contract.OutlookSyncRequest
import com.pim.core.contract.RefreshRequest
import com.pim.core.contract.RegisterRequest
import com.pim.core.contract.SearchResultPagedResultApiResponse
import com.pim.core.contract.StringApiResponse
import com.pim.core.contract.TaskExecutionSegmentResponseApiResponse
import com.pim.core.contract.TaskExecutionSegmentResponseIReadOnlyListApiResponse
import com.pim.core.contract.TaskResponseApiResponse
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.contract.TaskTimerStateResponseApiResponse
import com.pim.core.contract.UpdateEventRequest
import com.pim.core.contract.UpdateTaskRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.RequestBody

/**
 * 与 `pim-api` 的接口边界。
 *
 * 请求/响应类型全部来自 `com.pim.core.contract`（由 `contract/openapi.json` 生成，
 * 见 `scripts/contract/generate-kotlin.py`）—— 字段名/类型写错会在编译期报错。
 * 路径与查询参数由 `verifyContractEndpoints` 在编译期对照契约校验（不存在的端点/参数直接编译失败），
 * 生成物本身另有一致性门禁 `scripts/ci/check-contract-kotlin.sh`。
 *
 * 需要契约里没有的端点时：不要在这里加方法，按 `AGENTS.md` 的「接口契约与门禁」把需求交给后端。
 */
interface ApiService {
    // Fixed native-note versions. These signatures are checked against the generated contract before compilation.
    @POST("native-notes/versions")
    suspend fun beginNoteMaterialUpload(@Body request: BeginNoteMaterialUploadRequest): NoteMaterialUploadDtoApiResponse

    @GET("native-notes/versions/{versionId}")
    suspend fun getNoteMaterialVersion(@Path("versionId") versionId: String): NoteMaterialVersionDtoApiResponse

    @PUT("native-notes/versions/{versionId}/files/{fileIndex}/parts/{partIndex}")
    suspend fun uploadNoteMaterialPart(@Path("versionId") versionId: String, @Path("fileIndex") fileIndex: Int,
        @Path("partIndex") partIndex: Int, @Query("sha256") sha256: String, @Body body: RequestBody): StringApiResponse

    @POST("native-notes/versions/{versionId}/complete")
    suspend fun completeNoteMaterialUpload(@Path("versionId") versionId: String): NoteMaterialUploadDtoApiResponse

    // Auth
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponseApiResponse

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponseApiResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<AuthResponseApiResponse>

    // Calendars
    @GET("calendar/calendars")
    suspend fun getCalendars(): CalendarResponseListApiResponse

    @POST("calendar/calendars")
    suspend fun createCalendar(@Body request: CreateCalendarRequest): CalendarResponseApiResponse

    // Events — PIM-024: server now always returns PagedResult; keep legacy list fallback via wrapper handling in repository.
    // New clients should request page/pageSize. Absolute path not needed here as calendar is under /api/v1.
    @GET("calendar/events")
    suspend fun getEvents(
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null
    ): EventResponsePagedResultApiResponse

    @POST("calendar/events")
    suspend fun createEvent(@Body request: CreateEventRequest): EventResponseApiResponse

    @PUT("calendar/events/{id}")
    suspend fun updateEvent(
        @Path("id") id: String,
        @Body request: UpdateEventRequest
    ): EventResponseApiResponse

    @DELETE("calendar/events/{id}")
    suspend fun deleteEvent(@Path("id") id: String): StringApiResponse

    // Tasks — 契约 2xx payload 是 TaskResponsePagedResult（对象），不是裸数组（F-B-04）。
    @GET("calendar/tasks")
    suspend fun getTasks(
        @Query("inbox") inbox: Boolean? = null
    ): TaskResponsePagedResultApiResponse

    @POST("calendar/tasks")
    suspend fun createTask(@Body request: CreateTaskRequest): TaskResponseApiResponse

    @PUT("calendar/tasks/{id}")
    suspend fun updateTask(
        @Path("id") id: String,
        @Body request: UpdateTaskRequest
    ): TaskResponseApiResponse

    // 契约 2xx payload 是 CalendarOperationResult（对象），不是标量（F-B-04）。
    @DELETE("calendar/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String): CalendarOperationResultApiResponse

    // 任务计时（工单 03）：执行时间段与「开始 / 暂停 / 结束」。
    // 契约 2xx payload 是 TaskExecutionSegmentResponse / TaskTimerStateResponse（对象）。
    // 触点在嵌入的 web 工作台（WebView /tasks），这里保证原生侧到契约的调用面就绪。
    @GET("calendar/tasks/{id}/segments")
    suspend fun getTaskSegments(@Path("id") id: String): TaskExecutionSegmentResponseIReadOnlyListApiResponse

    @GET("calendar/tasks/{id}/timer")
    suspend fun getTaskTimer(@Path("id") id: String): TaskTimerStateResponseApiResponse

    @POST("calendar/tasks/{id}/timer/start")
    suspend fun startTaskTimer(@Path("id") id: String): TaskExecutionSegmentResponseApiResponse

    @POST("calendar/tasks/{id}/timer/pause")
    suspend fun pauseTaskTimer(@Path("id") id: String): TaskExecutionSegmentResponseApiResponse

    @POST("calendar/tasks/{id}/timer/stop")
    suspend fun stopTaskTimer(@Path("id") id: String): TaskExecutionSegmentResponseApiResponse

    // Search — 契约 2xx payload 是 SearchResultPagedResult（对象），不是裸数组（F-B-04）。
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String? = null
    ): SearchResultPagedResultApiResponse

    // ICS 导入 —— 契约该 operation 是 multipart/form-data（`IcsImportForm`：`file` binary 必填、
    // `calendarId` 可空），2xx payload 是 ImportReport（F-B-04）。非 JSON 请求体按本仓门禁口径
    // 用 `@Body RequestBody` 承载调用方组好的 multipart（`@Multipart`/`@Part` 被门禁拒绝）。
    @POST("calendar/import-ics")
    suspend fun importIcs(@Body body: RequestBody): ImportReportApiResponse

    // ICS 导出 —— 契约 2xx 是 `text/calendar`，不是 JSON 信封，因此用 ResponseBody 接（F-B-04）。
    @GET("calendar/export-ics")
    suspend fun exportIcs(
        @Query("start") start: String,
        @Query("end") end: String
    ): ResponseBody

    // Outlook —— 契约 requestBody required=true，`OutlookSyncRequest.mode` 必填（F-B-04）。
    @POST("calendar/outlook/sync")
    suspend fun syncOutlook(@Body request: OutlookSyncRequest): OutlookSyncBatchResponseApiResponse

    // Mobile
    @POST("mobile/devices/register")
    suspend fun registerMobileDevice(@Body request: MobileDeviceRegisterRequest): MobileDeviceDtoApiResponse

    @POST("mobile/sync/gaps")
    suspend fun getMobileGaps(@Body request: MobileGapRequest): MobileGapResponseApiResponse

    @POST("mobile/usage/events")
    suspend fun uploadMobileUsage(@Body request: MobileUsageEventsUploadRequest): MobileUsageIngestResultApiResponse

    /** 阶段一取证事件上报（REQ-5）：与使用记录走独立通道。 */
    @POST("mobile/forensics/events")
    suspend fun uploadMobileForensics(
        @Body request: MobileForensicsUploadRequest
    ): MobileForensicsIngestResultApiResponse

    @POST("mobile/location/points")
    suspend fun uploadMobileLocation(@Body request: MobileLocationPointRequest): MobileLocationPointDtoApiResponse

    /**
     * 批量补传通道（客户端积压时用）：一次请求上传多个定位点，逐条返回结果。
     *
     * 与单点端点共用同一套幂等语义（同一 设备+时刻+经纬度 只保留一行）。
     */
    @POST("mobile/location/points/batch")
    suspend fun uploadMobileLocationsBatch(
        @Body request: MobileLocationPointsUploadRequest
    ): MobileLocationPointsUploadResultApiResponse

    @GET("mobile/summary")
    suspend fun getMobileSummary(
        @Query("date") date: String? = null,
        @Query("deviceId") deviceId: String? = null
    ): MobileUsageSummaryResponseApiResponse

    @GET("mobile/timeline")
    suspend fun getMobileTimeline(
        @Query("date") date: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null
    ): MobileTimelineResponseApiResponse

    @GET("mobile/quality")
    suspend fun getMobileQuality(
        @Query("date") date: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("rangeStartUtc") rangeStartUtc: String? = null,
        @Query("rangeEndUtc") rangeEndUtc: String? = null
    ): MobileQualityResponseApiResponse

    @GET("mobile/location/history")
    suspend fun getMobileLocationHistory(
        @Query("rangeStartUtc") rangeStartUtc: String? = null,
        @Query("rangeEndUtc") rangeEndUtc: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("maxAccuracyMeters") maxAccuracyMeters: Double = 50.0
    ): MobileLocationHistoryResponseApiResponse

    @GET("mobile/location/analytics/overview")
    suspend fun getMobileLocationOverview(
        @Query("rangeStartUtc") rangeStartUtc: String,
        @Query("rangeEndUtc") rangeEndUtc: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("maxAccuracyMeters") maxAccuracyMeters: Double = 50.0
    ): MobileLocationAnalyticsOverviewResponseApiResponse

    @GET("mobile/location/analytics/tracks")
    suspend fun getMobileLocationTracks(
        @Query("rangeStartUtc") rangeStartUtc: String,
        @Query("rangeEndUtc") rangeEndUtc: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("maxAccuracyMeters") maxAccuracyMeters: Double = 50.0
    ): MobileLocationTrackDtoIReadOnlyListApiResponse

    @GET("mobile/location/analytics/segments/{segmentId}/points")
    suspend fun getMobileLocationSegmentPoints(
        @Path("segmentId") segmentId: String,
        @Query("rangeStartUtc") rangeStartUtc: String? = null,
        @Query("rangeEndUtc") rangeEndUtc: String? = null,
        @Query("timezone") timezone: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("maxAccuracyMeters") maxAccuracyMeters: Double = 50.0,
        @Query("includeRejected") includeRejected: Boolean = false,
        @Query("cursor") cursor: String? = null,
        @Query("pageSize") pageSize: Int? = null
    ): MobileLocationSegmentPointPageDtoApiResponse

    // Daemon
    @POST("daemon/heartbeat")
    suspend fun sendHeartbeat(@Body request: DaemonHeartbeatRequest): DaemonHeartbeatDtoApiResponse

    // Endpoint shell
    /**
     * 端点心跳（工单 06）：端点页 `GET /api/v1/endpoints` 的唯一数据来源。
     *
     * `last_heartbeat_at` 由服务端按**收到请求的时刻**写入 —— 请求体里没有时间字段，
     * 客户端无法（也不应该）伪造"设备最后一次活着的时间"。
     */
    @POST("endpoints/{deviceId}/heartbeat")
    suspend fun sendEndpointHeartbeat(
        @Path("deviceId") deviceId: String,
        @Body request: EndpointHeartbeatRequest
    ): EndpointStatusDtoApiResponse

    @POST("endpoints/{deviceId}/notification-actions")
    suspend fun sendEndpointNotificationAction(
        @Path("deviceId") deviceId: String,
        @Body request: EndpointNotificationActionRequest
    ): EndpointNotificationActionResponseApiResponse

    @GET("/api/client/shell/latest")
    suspend fun getClientLatest(): ClientShellLatestResponse
}

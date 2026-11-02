package com.pim.core.network

import com.pim.core.contract.BeginNoteMaterialUploadRequest
import com.pim.core.contract.NoteAsrDestinationsDtoApiResponse
import com.pim.core.contract.NoteMaterialUploadDtoApiResponse
import com.pim.core.contract.NoteMaterialVersionDtoApiResponse
import com.pim.core.contract.NoteAsrSegmentsDtoApiResponse
import com.pim.core.contract.NoteAsrSourcesDtoApiResponse
import com.pim.core.contract.NoteAsrTaskDtoApiResponse
import com.pim.core.contract.NoteAsrTaskDtoIReadOnlyListApiResponse
import com.pim.core.contract.NoteDerivedArtifactDtoApiResponse
import com.pim.core.contract.NoteDerivedArtifactDtoIReadOnlyListApiResponse
import com.pim.core.contract.NoteDerivedDestinationsDtoApiResponse
import com.pim.core.contract.NoteDerivedLimitsDtoApiResponse
import com.pim.core.contract.NoteDerivedMutationDtoApiResponse
import com.pim.core.contract.NoteDerivedStepsDtoApiResponse
import com.pim.core.contract.NoteDerivedTaskDtoApiResponse
import com.pim.core.contract.NoteDerivedTaskDtoIReadOnlyListApiResponse
import com.pim.core.contract.NoteDerivedVersionDtoApiResponse
import com.pim.core.contract.NoteTaskSuggestionDtoApiResponse
import com.pim.core.contract.NoteTaskSuggestionExtractionDtoApiResponse
import com.pim.core.contract.NoteTaskSuggestionListDtoApiResponse
import com.pim.core.contract.ConfirmNoteTaskSuggestionRequest
import com.pim.core.contract.DismissNoteTaskSuggestionRequest
import com.pim.core.contract.StartNoteTaskSuggestionExtractionRequest
import com.pim.core.contract.UpdateNoteTaskSuggestionRequest
import com.pim.core.contract.OperationConfirmationDtoApiResponse
import com.pim.core.contract.OperationConfirmationDtoIReadOnlyListApiResponse
import com.pim.core.contract.RetryNoteAsrRequest
import com.pim.core.contract.RetryNoteDerivedTaskRequest
import com.pim.core.contract.SaveNoteDerivedManualRevisionRequest
import com.pim.core.contract.SelectNoteDerivedVersionRequest
import com.pim.core.contract.StartNoteAsrRequest
import com.pim.core.contract.StartNoteDerivedTaskRequest

import com.pim.core.contract.AuthResponseApiResponse
import com.pim.core.contract.AppKnowledgeAppDtoListApiResponse
import com.pim.core.contract.AppKnowledgeContextDtoApiResponse
import com.pim.core.contract.AppKnowledgeContextDtoListApiResponse
import com.pim.core.contract.HabitRoutineDtoApiResponse
import com.pim.core.contract.HabitRoutineDtoIReadOnlyListApiResponse
import com.pim.core.contract.CalendarOperationResultApiResponse
import com.pim.core.contract.CalendarResponseApiResponse
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.CategorySaveRequest
import com.pim.core.contract.CategoryTreeNodeApiResponse
import com.pim.core.contract.CategoryTreeNodeListApiResponse
import com.pim.core.contract.ClientShellLatestResponse
import com.pim.core.contract.CreateCalendarRequest
import com.pim.core.contract.CreateEventRequest
import com.pim.core.contract.CreateQuickNoteRequest
import com.pim.core.contract.CreateTaskBookRequest
import com.pim.core.contract.CreateTaskRequest
import com.pim.core.contract.DaemonHeartbeatDtoApiResponse
import com.pim.core.contract.DaemonHeartbeatRequest
import com.pim.core.contract.DeviceDeletePreviewDtoApiResponse
import com.pim.core.contract.DeviceDeleteResultDtoApiResponse
import com.pim.core.contract.DeviceDtoApiResponse
import com.pim.core.contract.DeviceListDtoIReadOnlyListApiResponse
import com.pim.core.contract.TypedDetailQueryResponseApiResponse
import com.pim.core.contract.SiteSummaryDtoApiResponse
import com.pim.core.contract.SiteDailyRowDtoListApiResponse
import com.pim.core.contract.SiteTimelineRowDtoListApiResponse
import com.pim.core.contract.DeviceRenameRequest
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
import com.pim.core.contract.QuickNoteDetailDtoApiResponse
import com.pim.core.contract.QuickNoteListItemDtoPagedResultApiResponse
import com.pim.core.contract.RefreshRequest
import com.pim.core.contract.RegisterRequest
import com.pim.core.contract.ReminderResponseApiResponse
import com.pim.core.contract.ReminderResponseIReadOnlyListApiResponse
import com.pim.core.contract.RestoreQuickNoteRequest
import com.pim.core.contract.SaveAppKnowledgeContextRequest
import com.pim.core.contract.SearchResultPagedResultApiResponse
import com.pim.core.contract.StringApiResponse
import com.pim.core.contract.SystemStatusDetailDtoApiResponse
import com.pim.core.contract.TaskBookDtoApiResponse
import com.pim.core.contract.TaskBookDtoIReadOnlyListApiResponse
import com.pim.core.contract.TaskExecutionSegmentResponseApiResponse
import com.pim.core.contract.TaskExecutionSegmentResponseIReadOnlyListApiResponse
import com.pim.core.contract.TaskResponseApiResponse
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.contract.TaskTimerStateResponseApiResponse
import com.pim.core.contract.UpdateEventRequest
import com.pim.core.contract.UpdateQuickNoteRequest
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

    // Fixed native-note segmented ASR. The bound client checks owner/server for every physical attempt.
    @GET("native-notes/asr-tasks/sources")
    suspend fun getNoteAsrSources(
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 20
    ): NoteAsrSourcesDtoApiResponse

    @GET("native-notes/asr-tasks/destinations")
    suspend fun getNoteAsrDestinations(): NoteAsrDestinationsDtoApiResponse

    @GET("native-notes/asr-tasks")
    suspend fun getNoteAsrTasks(): NoteAsrTaskDtoIReadOnlyListApiResponse

    @POST("native-notes/asr-tasks")
    suspend fun startNoteAsrTask(@Body request: StartNoteAsrRequest): NoteAsrTaskDtoApiResponse

    @GET("native-notes/asr-tasks/{taskId}")
    suspend fun getNoteAsrTask(@Path("taskId") taskId: String): NoteAsrTaskDtoApiResponse

    @GET("native-notes/asr-tasks/{taskId}/segments")
    suspend fun getNoteAsrSegments(
        @Path("taskId") taskId: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 10
    ): NoteAsrSegmentsDtoApiResponse

    @POST("native-notes/asr-tasks/{taskId}/stop")
    suspend fun stopNoteAsrTask(@Path("taskId") taskId: String): NoteAsrTaskDtoApiResponse

    @POST("native-notes/asr-tasks/{taskId}/retry")
    suspend fun retryNoteAsrTask(
        @Path("taskId") taskId: String,
        @Body request: RetryNoteAsrRequest
    ): NoteAsrTaskDtoApiResponse

    // Fixed native-note derived artifacts. The same bound owner/server route family covers these calls.
    @GET("native-notes/derived-tasks/limits")
    suspend fun getNoteDerivedLimits(): NoteDerivedLimitsDtoApiResponse

    @GET("native-notes/derived-tasks/destinations")
    suspend fun getNoteDerivedDestinations(): NoteDerivedDestinationsDtoApiResponse

    @GET("native-notes/derived-tasks/sources")
    suspend fun getNoteDerivedSources(
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 20
    ): NoteAsrSourcesDtoApiResponse

    @GET("native-notes/derived-tasks")
    suspend fun getNoteDerivedTasks(@Query("noteId") noteId: String? = null): NoteDerivedTaskDtoIReadOnlyListApiResponse

    @POST("native-notes/derived-tasks")
    suspend fun startNoteDerivedTask(@Body request: StartNoteDerivedTaskRequest): NoteDerivedTaskDtoApiResponse

    @GET("native-notes/derived-tasks/{taskId}")
    suspend fun getNoteDerivedTask(@Path("taskId") taskId: String): NoteDerivedTaskDtoApiResponse

    @GET("native-notes/derived-tasks/{taskId}/steps")
    suspend fun getNoteDerivedSteps(
        @Path("taskId") taskId: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 10
    ): NoteDerivedStepsDtoApiResponse

    @POST("native-notes/derived-tasks/{taskId}/stop")
    suspend fun stopNoteDerivedTask(@Path("taskId") taskId: String): NoteDerivedTaskDtoApiResponse

    @POST("native-notes/derived-tasks/{taskId}/retry")
    suspend fun retryNoteDerivedTask(
        @Path("taskId") taskId: String,
        @Body request: RetryNoteDerivedTaskRequest
    ): NoteDerivedTaskDtoApiResponse

    @GET("native-notes/derived-artifacts")
    suspend fun getNoteDerivedArtifacts(@Query("noteId") noteId: String): NoteDerivedArtifactDtoIReadOnlyListApiResponse

    @GET("native-notes/derived-artifacts/{artifactId}")
    suspend fun getNoteDerivedArtifact(@Path("artifactId") artifactId: String): NoteDerivedArtifactDtoApiResponse

    @GET("native-notes/derived-artifacts/{artifactId}/versions/{versionId}")
    suspend fun getNoteDerivedVersion(
        @Path("artifactId") artifactId: String,
        @Path("versionId") versionId: String
    ): NoteDerivedVersionDtoApiResponse

    @POST("native-notes/derived-artifacts/{artifactId}/manual-revisions")
    suspend fun saveNoteDerivedManualRevision(
        @Path("artifactId") artifactId: String,
        @Body request: SaveNoteDerivedManualRevisionRequest
    ): NoteDerivedMutationDtoApiResponse

    @POST("native-notes/derived-artifacts/{artifactId}/select")
    suspend fun selectNoteDerivedVersion(
        @Path("artifactId") artifactId: String,
        @Body request: SelectNoteDerivedVersionRequest
    ): NoteDerivedArtifactDtoApiResponse

    // Fixed-source task suggestions. These calls use the same bound native-note session.
    @GET("native-notes/task-suggestions")
    suspend fun listNoteTaskSuggestions(@Query("noteId") noteId: String): NoteTaskSuggestionListDtoApiResponse

    @POST("native-notes/task-suggestions/extractions")
    suspend fun startNoteTaskSuggestionExtraction(
        @Body request: StartNoteTaskSuggestionExtractionRequest
    ): NoteTaskSuggestionExtractionDtoApiResponse

    @GET("native-notes/task-suggestions/extractions/{id}")
    suspend fun getNoteTaskSuggestionExtraction(@Path("id") id: String): NoteTaskSuggestionExtractionDtoApiResponse

    @GET("native-notes/task-suggestions/{id}")
    suspend fun getNoteTaskSuggestion(@Path("id") id: String): NoteTaskSuggestionDtoApiResponse

    @PUT("native-notes/task-suggestions/{id}")
    suspend fun updateNoteTaskSuggestion(
        @Path("id") id: String,
        @Body request: UpdateNoteTaskSuggestionRequest
    ): NoteTaskSuggestionDtoApiResponse

    @POST("native-notes/task-suggestions/{id}/confirm")
    suspend fun confirmNoteTaskSuggestion(
        @Path("id") id: String,
        @Body request: ConfirmNoteTaskSuggestionRequest
    ): NoteTaskSuggestionDtoApiResponse

    @POST("native-notes/task-suggestions/{id}/dismiss")
    suspend fun dismissNoteTaskSuggestion(
        @Path("id") id: String,
        @Body request: DismissNoteTaskSuggestionRequest
    ): NoteTaskSuggestionDtoApiResponse

    @GET("operations/confirmations/pending")
    suspend fun getPendingOperationConfirmations(): OperationConfirmationDtoIReadOnlyListApiResponse

    @GET("operations/confirmations/{id}")
    suspend fun getOperationConfirmation(
        @Path("id") id: String
    ): OperationConfirmationDtoApiResponse

    @POST("operations/confirmations/{id}/reject")
    suspend fun rejectOperationConfirmation(
        @Path("id") id: String
    ): OperationConfirmationDtoApiResponse

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

    @GET("calendar/tasks")
    suspend fun getTasksPaged(
        @Query("inbox") inbox: Boolean? = null,
        @Query("search") search: String? = null,
        @Query("calendarId") calendarId: String? = null,
        @Query("status") status: String? = null,
        @Query("priority") priority: Int? = null,
        @Query("plannedFrom") plannedFrom: String? = null,
        @Query("plannedTo") plannedTo: String? = null,
        @Query("dueFrom") dueFrom: String? = null,
        @Query("dueTo") dueTo: String? = null,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null
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

    @GET("calendar/task-books")
    suspend fun getTaskBooks(): TaskBookDtoIReadOnlyListApiResponse

    @POST("calendar/task-books")
    suspend fun createTaskBook(@Body request: CreateTaskBookRequest): TaskBookDtoApiResponse

    // 任务计时（工单 03）：执行时间段与「开始 / 暂停 / 结束」。
    // 契约 2xx payload 是 TaskExecutionSegmentResponse / TaskTimerStateResponse（对象）。
    // 触点在原生工作台，这里保证原生侧到契约的调用面就绪。
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

    @GET("mobile/devices/manage")
    suspend fun getManagedDevices(@Query("sortBy") sortBy: String? = null): DeviceListDtoIReadOnlyListApiResponse

    @GET("pc/detail")
    suspend fun getPcDetail(
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("deviceId") deviceId: String? = null,
        @Query("eventType") eventType: String? = null,
        @Query("domain") domain: String? = null,
        @Query("title") title: String? = null,
        @Query("url") url: String? = null,
        @Query("view") view: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): TypedDetailQueryResponseApiResponse

    @GET("pc/browser-tt/summary")
    suspend fun getBrowserUsageSummary(
        @Query("date") date: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): SiteSummaryDtoApiResponse

    @GET("pc/browser-tt/daily")
    suspend fun getBrowserUsageDaily(
        @Query("date") date: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): SiteDailyRowDtoListApiResponse

    @GET("pc/browser-tt/timeline")
    suspend fun getBrowserUsageTimeline(@Query("date") date: String): SiteTimelineRowDtoListApiResponse

    @POST("mobile/devices/{deviceId}/rename")
    suspend fun renameManagedDevice(
        @Path("deviceId") deviceId: String,
        @Body request: DeviceRenameRequest
    ): DeviceDtoApiResponse

    @GET("mobile/devices/{deviceId}/delete-preview")
    suspend fun getManagedDeviceDeletePreview(@Path("deviceId") deviceId: String): DeviceDeletePreviewDtoApiResponse

    @DELETE("mobile/devices/{deviceId}")
    suspend fun deleteManagedDevice(@Path("deviceId") deviceId: String): DeviceDeleteResultDtoApiResponse

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

    // Workbench knowledge categories and app-context rules.
    @GET("pc/categories/tree")
    suspend fun getKnowledgeCategoryTree(): CategoryTreeNodeListApiResponse

    @POST("pc/categories")
    suspend fun saveKnowledgeCategory(@Body request: CategorySaveRequest): CategoryTreeNodeApiResponse

    @DELETE("pc/categories/{id}")
    suspend fun deleteKnowledgeCategory(@Path("id") id: String): StringApiResponse

    @GET("pc/app-knowledge/apps")
    suspend fun getAppKnowledgeApps(@Query("search") search: String? = null): AppKnowledgeAppDtoListApiResponse

    @GET("pc/app-knowledge/apps/{appId}/contexts")
    suspend fun getAppKnowledgeContexts(@Path("appId") appId: String): AppKnowledgeContextDtoListApiResponse

    @POST("pc/app-knowledge/contexts")
    suspend fun saveAppKnowledgeContext(
        @Body request: SaveAppKnowledgeContextRequest
    ): AppKnowledgeContextDtoApiResponse

    @DELETE("pc/app-knowledge/contexts/{id}")
    suspend fun deleteAppKnowledgeContext(@Path("id") id: String): StringApiResponse

    // Legacy quick-note compatibility. Attachments are referenced by existing IDs only.
    @GET("quick-notes")
    suspend fun getQuickNotes(
        @Query("status") status: String? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null
    ): QuickNoteListItemDtoPagedResultApiResponse

    @POST("quick-notes")
    suspend fun createQuickNote(@Body request: CreateQuickNoteRequest): QuickNoteDetailDtoApiResponse

    @GET("quick-notes/{id}")
    suspend fun getQuickNote(@Path("id") id: String): QuickNoteDetailDtoApiResponse

    @PUT("quick-notes/{id}")
    suspend fun updateQuickNote(
        @Path("id") id: String,
        @Body request: UpdateQuickNoteRequest
    ): QuickNoteDetailDtoApiResponse

    @DELETE("quick-notes/{id}")
    suspend fun deleteQuickNote(@Path("id") id: String): StringApiResponse

    @POST("quick-notes/{id}/archive")
    suspend fun archiveQuickNote(@Path("id") id: String): QuickNoteDetailDtoApiResponse

    @POST("quick-notes/{id}/process")
    suspend fun processQuickNote(@Path("id") id: String): QuickNoteDetailDtoApiResponse

    @POST("quick-notes/{id}/restore")
    suspend fun restoreQuickNote(
        @Path("id") id: String,
        @Body request: RestoreQuickNoteRequest
    ): QuickNoteDetailDtoApiResponse

    @GET("calendar/reminders")
    suspend fun getReminders(): ReminderResponseIReadOnlyListApiResponse

    @POST("calendar/reminders/{id}/dismiss")
    suspend fun dismissReminder(@Path("id") id: String): ReminderResponseApiResponse

    @POST("calendar/reminders/{id}/snooze")
    suspend fun snoozeReminder(
        @Path("id") id: String,
        @Query("scheduledAt") scheduledAt: String
    ): ReminderResponseApiResponse

    @GET("calendar/habits")
    suspend fun getHabits(): HabitRoutineDtoIReadOnlyListApiResponse

    @POST("calendar/habits/{id}/archive")
    suspend fun archiveHabit(@Path("id") id: String): HabitRoutineDtoApiResponse

    @GET("status")
    suspend fun getSystemStatus(): SystemStatusDetailDtoApiResponse

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

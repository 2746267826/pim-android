package com.pim.app.nativework

import com.pim.core.contract.AppKnowledgeAppDto
import com.pim.core.contract.AppKnowledgeContextDto
import com.pim.core.contract.CalendarResponse
import com.pim.core.contract.CategorySaveRequest
import com.pim.core.contract.CategoryTreeNode
import com.pim.core.contract.CreateCalendarRequest
import com.pim.core.contract.CreateEventRequest
import com.pim.core.contract.CreateTaskBookRequest
import com.pim.core.contract.CreateTaskRequest
import com.pim.core.contract.DeviceDeletePreviewDto
import com.pim.core.contract.DeviceDeleteResultDto
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.EventResponse
import com.pim.core.contract.MobileUsageSummaryResponse
import com.pim.core.contract.ReminderResponse
import com.pim.core.contract.SaveAppKnowledgeContextRequest
import com.pim.core.contract.SystemStatusDetailDto
import com.pim.core.contract.TaskBookDto
import com.pim.core.contract.TaskExecutionSegmentResponse
import com.pim.core.contract.TaskResponse
import com.pim.core.contract.TaskTimerStateResponse
import com.pim.core.contract.UpdateEventRequest
import com.pim.core.contract.UpdateTaskRequest
import com.pim.core.network.ApiService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class NativeTaskDraft(
    val title: String,
    val description: String = "",
    val priority: Int = 3,
    val due: String? = null,
    val dtStart: String? = null,
    val taskBookId: String? = null,
)

data class NativeEventDraft(
    val calendarId: String,
    val title: String,
    val description: String = "",
    val dtStart: String,
    val dtEnd: String,
    val location: String = "",
)

data class NativeWorkbenchRange(
    val start: String,
    val endExclusive: String,
)

data class NativeReminderAction(
    val id: String,
    val title: String,
    val body: String,
    val scheduledAt: String,
    val status: String,
    val riskLevel: String,
    val relatedObjectType: String,
    val relatedObjectId: String,
)

data class NativeTaskPage(
    val items: List<TaskResponse>,
    val totalCount: Int,
    val totalPages: Int,
)

data class NativeEventPage(
    val items: List<EventResponse>,
    val totalCount: Int,
    val totalPages: Int,
)

class NativeWorkbenchRequestException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Build a replacement request from the complete task. PUT is not a partial update:
 * omitting a field would clear it on the server.
 */
fun taskUpdateRequest(
    task: TaskResponse,
    draft: NativeTaskDraft = NativeTaskDraft(
        title = task.title,
        description = task.description.orEmpty(),
        priority = task.priority,
        due = task.due,
        dtStart = task.dtStart,
        taskBookId = task.taskBookId,
    ),
    status: String = task.status,
    percentComplete: Int? = task.percentComplete,
): UpdateTaskRequest = UpdateTaskRequest(
    calendarId = task.calendarId,
    description = draft.description.ifBlank { null },
    dtStart = draft.dtStart,
    due = draft.due,
    estimatedDuration = task.estimatedDuration,
    minimumSegment = task.minimumSegment,
    percentComplete = percentComplete,
    plannedEnd = task.plannedEnd,
    priority = draft.priority,
    status = status,
    taskBookId = draft.taskBookId ?: task.taskBookId,
    title = draft.title.trim(),
)

/**
 * Preserve every event field that the editor does not expose. This matters for
 * recurrence metadata, Outlook identity and attendee/reminder state.
 */
fun eventUpdateRequest(event: EventResponse, draft: NativeEventDraft): UpdateEventRequest =
    UpdateEventRequest(
        attachmentReferences = event.attachmentReferences,
        attendees = event.attendees,
        calendarId = draft.calendarId,
        categories = event.categories,
        description = draft.description.ifBlank { null },
        descriptionFormat = event.descriptionFormat,
        dtEnd = draft.dtEnd,
        dtStart = draft.dtStart,
        externalLink = event.externalLink,
        importance = event.importance,
        isAllDay = event.isAllDay,
        isException = event.isException,
        isMuted = event.isMuted,
        isOnlineMeeting = event.isOnlineMeeting,
        isReminderOn = event.isReminderOn,
        isSeriesMaster = event.isSeriesMaster,
        location = draft.location.ifBlank { null },
        onlineMeetingProvider = event.onlineMeetingProvider,
        onlineMeetingUrl = event.onlineMeetingUrl,
        organizer = event.organizer,
        rRule = event.rRule,
        recurrenceId = event.recurrenceId,
        reminderMinutesBeforeStart = event.reminderMinutesBeforeStart,
        sensitivity = event.sensitivity,
        seriesMasterId = event.seriesMasterId,
        showAs = event.showAs,
        timeZoneId = event.timeZoneId,
        title = draft.title.trim(),
        uid = event.uid,
    )

fun nativeWorkbenchRange(
    date: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): NativeWorkbenchRange = NativeWorkbenchRange(
    start = date.atStartOfDay(zoneId).toInstant().toString(),
    endExclusive = date.plusDays(7).atStartOfDay(zoneId).toInstant().toString(),
)

internal fun EventResponse.isOutlookMirror(): Boolean =
    source.startsWith("outlook", ignoreCase = true) ||
        outlookEventId != null ||
        outlookCalendarBindingId != null ||
        outlookEtag != null ||
        outlookEventType != null ||
        outlookAdditionalInfo != null

internal fun EventResponse.hasRecurrenceMetadata(): Boolean =
    isSeriesMaster == true ||
        rRule != null ||
        recurrenceId != null ||
        seriesMasterId != null ||
        originalEventId != null ||
        exDatesJson != null ||
        recurrenceMetadataJson != null

@Singleton
class NativeWorkbenchRepository @Inject constructor(
    private val api: ApiService,
) {
    /**
     * A workbench load/action must use the API client bound to its owner/server
     * epoch. The injected instance is only a stateless template for tests and
     * graph construction; production calls go through this bound copy.
     */
    fun boundTo(boundApi: ApiService): NativeWorkbenchRepository =
        NativeWorkbenchRepository(boundApi)

    suspend fun loadTasks(search: String? = null): NativeTaskPage {
        val result = api.getTasksPaged(
            search = search?.trim()?.takeIf { it.isNotEmpty() },
            page = 1,
            pageSize = 100,
        )
        val page = requireData(result.data, result.message, "读取任务")
        return NativeTaskPage(page.items, page.totalCount, page.totalPages)
    }

    suspend fun loadTaskBooks(): List<TaskBookDto> {
        val result = api.getTaskBooks()
        return requireData(result.data, result.message, "读取任务本")
    }

    suspend fun createTaskBook(name: String): TaskBookDto {
        require(name.isNotBlank()) { "任务本名称不能为空" }
        val result = api.createTaskBook(CreateTaskBookRequest(name = name.trim()))
        return requireData(result.data, result.message, "新建任务本")
    }

    suspend fun loadCalendars(): List<CalendarResponse> {
        val result = api.getCalendars()
        return requireData(result.data, result.message, "读取日历")
            .filter { it.canEdit != false }
    }

    suspend fun createCalendar(name: String, color: String): CalendarResponse {
        require(name.isNotBlank()) { "日历名称不能为空" }
        require(Regex("^#[0-9A-Fa-f]{6}$").matches(color)) { "日历颜色需为 #RRGGBB" }
        val result = api.createCalendar(CreateCalendarRequest(name = name.trim(), color = color))
        return requireData(result.data, result.message, "新建日历")
    }

    suspend fun loadEvents(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): NativeEventPage {
        val range = nativeWorkbenchRange(date, zoneId)
        val result = api.getEvents(
            start = range.start,
            end = range.endExclusive,
            page = 1,
            pageSize = 100,
        )
        val page = requireData(result.data, result.message, "读取日程")
        return NativeEventPage(page.items, page.totalCount, page.totalPages)
    }

    suspend fun loadTodayUsage(date: LocalDate): MobileUsageSummaryResponse {
        val result = api.getMobileSummary(date = date.toString())
        return requireData(result.data, result.message, "读取今日使用摘要")
    }

    suspend fun createTask(draft: NativeTaskDraft): TaskResponse {
        require(draft.title.isNotBlank()) { "任务标题不能为空" }
        val result = api.createTask(
            CreateTaskRequest(
                description = draft.description.ifBlank { null },
                dtStart = draft.dtStart,
                due = draft.due,
                priority = draft.priority,
                status = "NEEDS-ACTION",
                taskBookId = draft.taskBookId,
                title = draft.title.trim(),
            )
        )
        return requireData(result.data, result.message, "新建任务")
    }

    suspend fun updateTask(task: TaskResponse, draft: NativeTaskDraft): TaskResponse {
        require(draft.title.isNotBlank()) { "任务标题不能为空" }
        val result = api.updateTask(task.id, taskUpdateRequest(task, draft))
        return requireData(result.data, result.message, "修改任务")
    }

    suspend fun setTaskCompleted(task: TaskResponse, completed: Boolean): TaskResponse {
        val request = taskUpdateRequest(
            task = task,
            status = if (completed) "COMPLETED" else "NEEDS-ACTION",
            percentComplete = if (completed) 100 else 0,
        )
        val result = api.updateTask(task.id, request)
        return requireData(result.data, result.message, if (completed) "完成任务" else "重新打开任务")
    }

    suspend fun deleteTask(task: TaskResponse) {
        val result = api.deleteTask(task.id)
        requireData(result.data, result.message, "删除任务")
    }

    suspend fun startTaskTimer(task: TaskResponse): TaskExecutionSegmentResponse =
        requireData(api.startTaskTimer(task.id).data, "开始计时失败", "启动任务计时")

    suspend fun pauseTaskTimer(task: TaskResponse): TaskExecutionSegmentResponse =
        requireData(api.pauseTaskTimer(task.id).data, "暂停计时失败", "暂停任务计时")

    suspend fun stopTaskTimer(task: TaskResponse): TaskExecutionSegmentResponse =
        requireData(api.stopTaskTimer(task.id).data, "结束计时失败", "结束任务计时")

    suspend fun getTaskTimer(task: TaskResponse): TaskTimerStateResponse =
        requireData(api.getTaskTimer(task.id).data, "计时状态为空", "读取任务计时")

    suspend fun createEvent(draft: NativeEventDraft): EventResponse {
        require(draft.title.isNotBlank()) { "日程标题不能为空" }
        requireEventTimeOrder(draft)
        val result = api.createEvent(
            CreateEventRequest(
                calendarId = draft.calendarId,
                description = draft.description.ifBlank { null },
                dtEnd = draft.dtEnd,
                dtStart = draft.dtStart,
                location = draft.location.ifBlank { null },
                title = draft.title.trim(),
            )
        )
        return requireData(result.data, result.message, "新建日程")
    }

    suspend fun updateEvent(event: EventResponse, draft: NativeEventDraft): EventResponse {
        require(draft.title.isNotBlank()) { "日程标题不能为空" }
        requireEventTimeOrder(draft)
        if (event.isOutlookMirror()) {
            throw NativeWorkbenchRequestException("Outlook 镜像日程需通过同步写回，当前原生页不直接修改。")
        }
        if (event.hasRecurrenceMetadata()) {
            throw NativeWorkbenchRequestException("重复日程的本次/整个系列范围尚未在原生编辑器确认，请暂用日历网页编辑。")
        }
        val result = api.updateEvent(event.id, eventUpdateRequest(event, draft))
        return requireData(result.data, result.message, "修改日程")
    }

    suspend fun deleteEvent(event: EventResponse) {
        if (event.isOutlookMirror()) {
            throw NativeWorkbenchRequestException("Outlook 镜像日程需通过同步写回，当前原生页不直接删除。")
        }
        if (event.hasRecurrenceMetadata()) {
            throw NativeWorkbenchRequestException("重复日程的本次/整个系列范围尚未在原生页确认，请暂用日历网页删除。")
        }
        val result = api.deleteEvent(event.id)
        requireData(result.data, result.message, "删除日程")
    }

    suspend fun loadDevices(sortBy: String = "active"): List<DeviceListDto> {
        val result = api.getManagedDevices(sortBy)
        return requireData(result.data, result.message, "读取设备")
    }

    suspend fun renameDevice(deviceId: String, displayName: String) {
        require(displayName.isNotBlank()) { "设备显示名称不能为空" }
        val result = api.renameManagedDevice(
            deviceId,
            com.pim.core.contract.DeviceRenameRequest(displayName.trim())
        )
        requireData(result.data, result.message, "重命名设备")
    }

    suspend fun deviceDeletePreview(deviceId: String): DeviceDeletePreviewDto {
        val result = api.getManagedDeviceDeletePreview(deviceId)
        return requireData(result.data, result.message, "读取设备删除影响")
    }

    suspend fun deleteDevice(deviceId: String): DeviceDeleteResultDto {
        val result = api.deleteManagedDevice(deviceId)
        return requireData(result.data, result.message, "删除设备")
    }

    suspend fun loadCategoryTree(): List<CategoryTreeNode> {
        val result = api.getKnowledgeCategoryTree()
        return requireData(result.data, result.message, "读取分类树")
    }

    suspend fun saveCategory(
        id: String?,
        name: String,
        parentId: String?,
        color: String,
        icon: String?,
        productivity: String,
        sortOrder: Int,
    ): CategoryTreeNode {
        require(name.isNotBlank()) { "分类名称不能为空" }
        require(Regex("^#[0-9A-Fa-f]{6}$").matches(color)) { "分类颜色需为 #RRGGBB" }
        require(productivity.isNotBlank()) { "生产力属性不能为空" }
        val result = api.saveKnowledgeCategory(
            CategorySaveRequest(
                color = color,
                icon = icon?.trim()?.takeIf { it.isNotEmpty() },
                id = id,
                name = name.trim(),
                parentId = parentId,
                productivity = productivity,
                sortOrder = sortOrder,
            )
        )
        return requireData(result.data, result.message, "保存分类")
    }

    suspend fun deleteCategory(category: CategoryTreeNode) {
        if (category.isBuiltin) throw NativeWorkbenchRequestException("内置分类不可删除。")
        val result = api.deleteKnowledgeCategory(category.id)
        requireData(result.data, result.message, "删除分类")
    }

    suspend fun loadKnowledgeApps(search: String? = null): List<AppKnowledgeAppDto> {
        val result = api.getAppKnowledgeApps(search?.trim()?.takeIf { it.isNotEmpty() })
        return requireData(result.data, result.message, "读取应用知识")
    }

    suspend fun loadAppContexts(appId: String): List<AppKnowledgeContextDto> {
        val result = api.getAppKnowledgeContexts(appId)
        return requireData(result.data, result.message, "读取应用上下文")
    }

    suspend fun saveAppContext(request: SaveAppKnowledgeContextRequest): AppKnowledgeContextDto {
        require(request.patternType.isNotBlank()) { "规则类型不能为空" }
        require(request.patternValue.isNotBlank()) { "规则值不能为空" }
        require(request.processName.isNotBlank()) { "进程名不能为空" }
        val confidence = request.confidence
        require(confidence == null || confidence in 0.0..1.0) {
            "置信度需在 0-1 之间"
        }
        val result = api.saveAppKnowledgeContext(request)
        return requireData(result.data, result.message, "保存应用上下文")
    }

    suspend fun deleteAppContext(context: AppKnowledgeContextDto) {
        val result = api.deleteAppKnowledgeContext(context.id)
        requireData(result.data, result.message, "删除应用上下文")
    }

    suspend fun loadSystemStatus(): SystemStatusDetailDto =
        requireData(api.getSystemStatus().data, "服务端状态为空", "读取系统诊断")

    suspend fun loadReminders(): List<NativeReminderAction> =
        requireData(api.getReminders().data, "服务端未返回提醒", "读取提醒")
            .map(::nativeReminderAction)

    suspend fun dismissReminder(id: String): ReminderResponse =
        requireData(api.dismissReminder(id).data, "提醒不存在", "忽略提醒")

    suspend fun snoozeReminder(id: String, scheduledAt: String): ReminderResponse {
        val scheduled = runCatching { Instant.parse(scheduledAt) }.getOrNull()
            ?: throw NativeWorkbenchRequestException("提醒延后时间格式无效。")
        if (!scheduled.isAfter(Instant.now())) {
            throw NativeWorkbenchRequestException("提醒延后时间必须晚于当前时间。")
        }
        return requireData(
            api.snoozeReminder(id, scheduled.toString()).data,
            "提醒不存在",
            "延后提醒",
        )
    }

    suspend fun loadPendingConfirmations(): List<NativeOperationConfirmation> =
        requireData(
            api.getPendingOperationConfirmations().data,
            "服务端未返回待确认列表",
            "读取待确认操作",
        ).map(::nativeOperationConfirmation)

    suspend fun loadConfirmation(id: String): NativeOperationConfirmation =
        requireData(
            api.getOperationConfirmation(id).data,
            "待确认操作不存在",
            "读取操作摘要",
        ).let(::nativeOperationConfirmation)

    suspend fun rejectConfirmation(id: String) {
        requireData(
            api.rejectOperationConfirmation(id).data,
            "待确认操作不存在或已失效",
            "拒绝操作",
        )
    }

    suspend fun loadHabits(): List<NativeHabit> =
        requireData(api.getHabits().data, "服务端未返回习惯列表", "读取习惯")
            .map(::nativeHabit)

    suspend fun archiveHabit(id: String): NativeHabit =
        requireData(api.archiveHabit(id).data, "习惯不存在或已失效", "归档习惯")
            .let(::nativeHabit)

    private fun <T> requireData(value: T?, message: String, operation: String): T =
        value ?: throw NativeWorkbenchRequestException(
            message.trim().takeIf { it.isNotEmpty() }
                ?.let { "$operation：$it" }
                ?: "$operation：服务端未返回数据"
        )

    private fun requireEventTimeOrder(draft: NativeEventDraft) {
        val start = runCatching { Instant.parse(draft.dtStart) }.getOrNull()
            ?: throw NativeWorkbenchRequestException("日程开始时间格式无效。")
        val end = runCatching { Instant.parse(draft.dtEnd) }.getOrNull()
            ?: throw NativeWorkbenchRequestException("日程结束时间格式无效。")
        if (!end.isAfter(start)) {
            throw NativeWorkbenchRequestException("日程结束时间必须晚于开始时间。")
        }
    }
}

fun todayTasks(tasks: List<TaskResponse>, today: LocalDate, zoneId: ZoneId): List<TaskResponse> =
    tasks.asSequence()
        .filter { it.status != "COMPLETED" }
        .filter { task ->
            listOfNotNull(task.due, task.dtStart).any { raw ->
                runCatching {
                    Instant.parse(raw).atZone(zoneId).toLocalDate() == today
                }.getOrDefault(false)
            }
        }
        .take(8)
        .toList()

fun nativeReminderAction(reminder: ReminderResponse): NativeReminderAction =
    NativeReminderAction(
        id = reminder.id,
        title = reminder.title,
        body = reminder.body,
        scheduledAt = reminder.scheduledAt,
        status = reminder.status,
        riskLevel = reminder.riskLevel,
        relatedObjectType = reminder.relatedObjectType,
        relatedObjectId = reminder.relatedObjectId,
    )

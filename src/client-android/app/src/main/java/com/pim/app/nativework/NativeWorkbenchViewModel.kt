package com.pim.app.nativework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pim.core.contract.AppKnowledgeAppDto
import com.pim.core.contract.AppKnowledgeContextDto
import com.pim.core.contract.CalendarResponse
import com.pim.core.contract.CategoryTreeNode
import com.pim.core.contract.DeviceDeletePreviewDto
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.EventResponse
import com.pim.core.contract.MobileUsageSummaryResponse
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.QuickNoteListItemDto
import com.pim.core.contract.SystemStatusDetailDto
import com.pim.core.contract.TaskBookDto
import com.pim.core.contract.TaskResponse
import com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NativeWorkbenchSection(val label: String) {
    Today("今日"),
    Tasks("任务"),
    Calendar("日历"),
    Reminders("提醒"),
    Devices("设备"),
    Knowledge("知识分类"),
    Diagnostics("诊断"),
    QuickNotes("快速记录"),
}

data class NativeWorkbenchUiState(
    val section: NativeWorkbenchSection = NativeWorkbenchSection.Today,
    val loading: Boolean = false,
    val busyAction: String? = null,
    val error: String? = null,
    val feedback: String? = null,
    val tasks: List<TaskResponse> = emptyList(),
    val taskTotal: Int = 0,
    val taskBooks: List<TaskBookDto> = emptyList(),
    val calendars: List<CalendarResponse> = emptyList(),
    val events: List<EventResponse> = emptyList(),
    val eventTotal: Int = 0,
    val mobileSummary: MobileUsageSummaryResponse? = null,
    val mobileSummaryError: String? = null,
    val devices: List<DeviceListDto> = emptyList(),
    val deviceSort: String = "active",
    val deviceDeletePreview: DeviceDeletePreviewDto? = null,
    val categories: List<CategoryTreeNode> = emptyList(),
    val apps: List<AppKnowledgeAppDto> = emptyList(),
    val selectedApp: AppKnowledgeAppDto? = null,
    val appContexts: List<AppKnowledgeContextDto> = emptyList(),
    val systemStatus: SystemStatusDetailDto? = null,
    val quickNotes: List<QuickNoteListItemDto> = emptyList(),
    val quickNoteTotal: Int = 0,
    val quickNoteDetail: QuickNoteDetailDto? = null,
    val search: String = "",
    val taskBooksError: String? = null,
    val reminders: List<NativeReminderAction> = emptyList(),
)

@HiltViewModel
class NativeWorkbenchViewModel @Inject constructor(
    private val repository: NativeWorkbenchRepository,
    private val legacyQuickNotes: LegacyQuickNoteRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NativeWorkbenchUiState())
    val state: StateFlow<NativeWorkbenchUiState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun selectSection(section: NativeWorkbenchSection) {
        if (_state.value.section == section) return
        _state.value = _state.value.copy(
            section = section,
            error = null,
            feedback = null,
            selectedApp = null,
            appContexts = emptyList(),
            quickNoteDetail = null,
        )
        refresh()
    }

    fun setSearch(value: String) {
        _state.value = _state.value.copy(search = value)
    }

    fun setDeviceSort(sortBy: String) {
        if (sortBy != "active" && sortBy != "data") return
        _state.value = _state.value.copy(deviceSort = sortBy)
        loadDevices()
    }

    fun refresh() {
        refreshJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                when (_state.value.section) {
                    NativeWorkbenchSection.Today -> {
                        val tasks = repository.loadTasks()
                        val events = repository.loadEvents(LocalDate.now())
                        val calendars = repository.loadCalendars()
                        var usageError: String? = null
                        val mobileSummary = try {
                            repository.loadTodayUsage(LocalDate.now())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            usageError = e.workbenchMessage()
                            null
                        }
                        val today = LocalDate.now()
                        _state.value = _state.value.copy(
                            tasks = todayTasks(tasks.items, today, java.time.ZoneId.systemDefault()),
                            taskTotal = tasks.totalCount,
                            events = events.items,
                            eventTotal = events.totalCount,
                            calendars = calendars,
                            mobileSummary = mobileSummary,
                            mobileSummaryError = usageError,
                        )
                    }
                    NativeWorkbenchSection.Tasks -> {
                        val tasks = repository.loadTasks(_state.value.search)
                        var booksError: String? = null
                        val books = try {
                            repository.loadTaskBooks()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            booksError = e.workbenchMessage()
                            emptyList()
                        }
                        _state.value = _state.value.copy(
                            tasks = tasks.items,
                            taskTotal = tasks.totalCount,
                            taskBooks = books,
                            taskBooksError = booksError,
                        )
                    }
                    NativeWorkbenchSection.Calendar -> {
                        val calendars = repository.loadCalendars()
                        val events = repository.loadEvents(LocalDate.now())
                        _state.value = _state.value.copy(
                            calendars = calendars,
                            events = events.items,
                            eventTotal = events.totalCount,
                        )
                    }
                    NativeWorkbenchSection.Devices -> {
                        val devices = repository.loadDevices(_state.value.deviceSort)
                        _state.value = _state.value.copy(devices = devices)
                    }
                    NativeWorkbenchSection.Knowledge -> {
                        val categories = repository.loadCategoryTree()
                        val apps = repository.loadKnowledgeApps(_state.value.search)
                        _state.value = _state.value.copy(categories = categories, apps = apps)
                    }
                    NativeWorkbenchSection.Diagnostics -> {
                        val status = repository.loadSystemStatus()
                        _state.value = _state.value.copy(systemStatus = status)
                    }
                    NativeWorkbenchSection.QuickNotes -> {
                        val notes = legacyQuickNotes.load(_state.value.search)
                        _state.value = _state.value.copy(
                            quickNotes = notes.items,
                            quickNoteTotal = notes.totalCount,
                        )
                    }
                    NativeWorkbenchSection.Reminders -> {
                        val reminders = repository.loadReminders()
                        _state.value = _state.value.copy(reminders = reminders)
                    }
                }
                _state.value = _state.value.copy(loading = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.workbenchMessage())
            }
        }
    }

    fun refreshTasks() = loadTasks()

    fun createTask(draft: NativeTaskDraft) = mutate("新建任务") {
        repository.createTask(draft)
        refreshCurrent(showLoading = false)
        "任务已创建"
    }

    fun createTaskBook(name: String) = mutate("新建任务本") {
        repository.createTaskBook(name)
        _state.value = _state.value.copy(
            taskBooks = repository.loadTaskBooks(),
            taskBooksError = null,
        )
        "任务本已创建"
    }

    fun updateTask(task: TaskResponse, draft: NativeTaskDraft) = mutate("修改任务") {
        repository.updateTask(task, draft)
        refreshCurrent(showLoading = false)
        "任务已保存"
    }

    fun toggleTaskCompleted(task: TaskResponse) = mutate(
        if (task.status == "COMPLETED") "重新打开任务" else "完成任务"
    ) {
        repository.setTaskCompleted(task, task.status != "COMPLETED")
        refreshCurrent(showLoading = false)
        if (task.status == "COMPLETED") "任务已重新打开" else "任务已完成"
    }

    fun deleteTask(task: TaskResponse) = mutate("删除任务") {
        repository.deleteTask(task)
        refreshCurrent(showLoading = false)
        "任务已删除"
    }

    fun startTaskTimer(task: TaskResponse) = mutate("开始计时") {
        repository.startTaskTimer(task)
        "任务计时已开始"
    }

    fun pauseTaskTimer(task: TaskResponse) = mutate("暂停计时") {
        repository.pauseTaskTimer(task)
        "任务计时已暂停"
    }

    fun stopTaskTimer(task: TaskResponse) = mutate("结束计时") {
        repository.stopTaskTimer(task)
        "任务计时已结束"
    }

    fun createEvent(draft: NativeEventDraft) = mutate("新建日程") {
        repository.createEvent(draft)
        refreshCurrent(showLoading = false)
        "日程已创建"
    }

    fun createCalendar(name: String, color: String) = mutate("新建日历") {
        repository.createCalendar(name, color)
        _state.value = _state.value.copy(calendars = repository.loadCalendars())
        "日历已创建"
    }

    fun updateEvent(event: EventResponse, draft: NativeEventDraft) = mutate("修改日程") {
        repository.updateEvent(event, draft)
        refreshCurrent(showLoading = false)
        "日程已保存"
    }

    fun deleteEvent(event: EventResponse) = mutate("删除日程") {
        repository.deleteEvent(event)
        refreshCurrent(showLoading = false)
        "日程已删除"
    }

    fun renameDevice(device: DeviceListDto, displayName: String) = mutate("重命名设备") {
        repository.renameDevice(device.deviceId, displayName)
        _state.value = _state.value.copy(
            devices = repository.loadDevices(_state.value.deviceSort)
        )
        "设备名称已保存"
    }

    fun requestDeviceDelete(device: DeviceListDto) = mutate("读取删除影响") {
        _state.value = _state.value.copy(
            deviceDeletePreview = repository.deviceDeletePreview(device.deviceId)
        )
        null
    }

    fun cancelDeviceDelete() {
        _state.value = _state.value.copy(deviceDeletePreview = null)
    }

    fun confirmDeviceDelete() = mutate("删除设备") {
        val preview = _state.value.deviceDeletePreview
            ?: throw NativeWorkbenchRequestException("删除预览已失效，请重试。")
        val result = repository.deleteDevice(preview.deviceId)
        _state.value = _state.value.copy(
            deviceDeletePreview = null,
            devices = repository.loadDevices(_state.value.deviceSort),
        )
        "已删除 ${result.totalRows} 行设备数据"
    }

    fun saveCategory(
        existing: CategoryTreeNode?,
        name: String,
        parentId: String?,
        color: String,
        icon: String?,
        productivity: String,
        sortOrder: Int,
    ) = mutate("保存分类") {
        repository.saveCategory(
            id = existing?.id,
            name = name,
            parentId = parentId,
            color = color,
            icon = icon,
            productivity = productivity,
            sortOrder = sortOrder,
        )
        refreshCurrent(showLoading = false)
        "分类已保存"
    }

    fun deleteCategory(category: CategoryTreeNode) = mutate("删除分类") {
        repository.deleteCategory(category)
        refreshCurrent(showLoading = false)
        "分类已删除"
    }

    fun selectKnowledgeApp(app: AppKnowledgeAppDto) = mutate("读取应用上下文") {
        _state.value = _state.value.copy(
            selectedApp = app,
            appContexts = repository.loadAppContexts(app.id)
        )
        null
    }

    fun closeKnowledgeApp() {
        _state.value = _state.value.copy(selectedApp = null, appContexts = emptyList())
    }

    fun saveAppContext(request: com.pim.core.contract.SaveAppKnowledgeContextRequest) =
        mutate("保存应用上下文") {
            repository.saveAppContext(request)
            val app = _state.value.selectedApp
                ?: throw NativeWorkbenchRequestException("请先选择应用。")
            _state.value = _state.value.copy(
                appContexts = repository.loadAppContexts(app.id),
                apps = repository.loadKnowledgeApps(_state.value.search),
            )
            "应用上下文已保存"
        }

    fun deleteAppContext(context: AppKnowledgeContextDto) = mutate("删除应用上下文") {
        repository.deleteAppContext(context)
        val app = _state.value.selectedApp
        if (app != null) {
            _state.value = _state.value.copy(appContexts = repository.loadAppContexts(app.id))
        }
        "应用上下文已删除"
    }

    fun openQuickNote(id: String) = mutate("读取快速记录") {
        _state.value = _state.value.copy(quickNoteDetail = legacyQuickNotes.get(id))
        null
    }

    fun closeQuickNote() {
        _state.value = _state.value.copy(quickNoteDetail = null)
    }

    fun createQuickNote(contentMarkdown: String) = mutate("新建快速记录") {
        legacyQuickNotes.create(contentMarkdown)
        refreshCurrent(showLoading = false)
        "快速记录已创建"
    }

    fun updateQuickNote(detail: QuickNoteDetailDto, contentMarkdown: String) =
        mutate("修改快速记录") {
            val updated = legacyQuickNotes.update(detail, contentMarkdown)
            _state.value = _state.value.copy(quickNoteDetail = updated)
            refreshCurrent(showLoading = false)
            "快速记录已保存"
        }

    fun archiveQuickNote(detail: QuickNoteDetailDto) = mutate("归档快速记录") {
        val updated = legacyQuickNotes.archive(detail)
        _state.value = _state.value.copy(quickNoteDetail = updated)
        refreshCurrent(showLoading = false)
        "快速记录已归档"
    }

    fun processQuickNote(detail: QuickNoteDetailDto) = mutate("处理快速记录") {
        val updated = legacyQuickNotes.process(detail)
        _state.value = _state.value.copy(quickNoteDetail = updated)
        refreshCurrent(showLoading = false)
        "快速记录已进入处理流程"
    }

    fun restoreQuickNote(detail: QuickNoteDetailDto) = mutate("恢复快速记录") {
        val updated = legacyQuickNotes.restore(detail)
        _state.value = _state.value.copy(quickNoteDetail = updated)
        refreshCurrent(showLoading = false)
        "快速记录已恢复"
    }

    fun deleteQuickNote(detail: QuickNoteDetailDto) = mutate("删除快速记录") {
        legacyQuickNotes.delete(detail)
        _state.value = _state.value.copy(quickNoteDetail = null)
        refreshCurrent(showLoading = false)
        "快速记录已删除"
    }

    fun dismissReminder(reminder: NativeReminderAction) = mutate("忽略提醒") {
        repository.dismissReminder(reminder.id)
        _state.value = _state.value.copy(reminders = repository.loadReminders())
        "提醒已忽略"
    }

    fun snoozeReminder(reminder: NativeReminderAction, scheduledAt: String) =
        mutate("延后提醒") {
            repository.snoozeReminder(reminder.id, scheduledAt)
            _state.value = _state.value.copy(reminders = repository.loadReminders())
            "提醒已延后"
        }

    private fun loadTasks(showLoading: Boolean = true) {
        refreshJob?.cancel()
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val tasks = repository.loadTasks(_state.value.search)
                _state.value = _state.value.copy(
                    tasks = tasks.items,
                    taskTotal = tasks.totalCount,
                    loading = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.workbenchMessage())
            }
        }
    }

    private fun loadDevices(showLoading: Boolean = true) {
        refreshJob?.cancel()
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    devices = repository.loadDevices(_state.value.deviceSort),
                    loading = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.workbenchMessage())
            }
        }
    }

    private suspend fun refreshCurrent(showLoading: Boolean) {
        if (showLoading) _state.value = _state.value.copy(loading = true)
        try {
            when (_state.value.section) {
                NativeWorkbenchSection.Today -> {
                    val tasks = repository.loadTasks()
                    val events = repository.loadEvents(LocalDate.now())
                    var usageError: String? = null
                    val mobileSummary = try {
                        repository.loadTodayUsage(LocalDate.now())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        usageError = e.workbenchMessage()
                        null
                    }
                    val today = LocalDate.now()
                    _state.value = _state.value.copy(
                        tasks = todayTasks(tasks.items, today, java.time.ZoneId.systemDefault()),
                        taskTotal = tasks.totalCount,
                        events = events.items,
                        eventTotal = events.totalCount,
                        mobileSummary = mobileSummary,
                        mobileSummaryError = usageError,
                        loading = false,
                    )
                }
                NativeWorkbenchSection.Tasks -> {
                    val tasks = repository.loadTasks(_state.value.search)
                    _state.value = _state.value.copy(
                        tasks = tasks.items,
                        taskTotal = tasks.totalCount,
                        loading = false,
                    )
                }
                NativeWorkbenchSection.Calendar -> {
                    val events = repository.loadEvents(LocalDate.now())
                    _state.value = _state.value.copy(
                        events = events.items,
                        eventTotal = events.totalCount,
                        loading = false,
                    )
                }
                NativeWorkbenchSection.Devices -> {
                    _state.value = _state.value.copy(
                        devices = repository.loadDevices(_state.value.deviceSort),
                        loading = false,
                    )
                }
                NativeWorkbenchSection.Knowledge -> {
                    _state.value = _state.value.copy(
                        categories = repository.loadCategoryTree(),
                        apps = repository.loadKnowledgeApps(_state.value.search),
                        loading = false,
                    )
                }
                NativeWorkbenchSection.Diagnostics -> {
                    _state.value = _state.value.copy(
                        systemStatus = repository.loadSystemStatus(),
                        loading = false,
                    )
                }
                NativeWorkbenchSection.QuickNotes -> {
                    val notes = legacyQuickNotes.load(_state.value.search)
                    _state.value = _state.value.copy(
                        quickNotes = notes.items,
                        quickNoteTotal = notes.totalCount,
                        loading = false,
                    )
                }
                NativeWorkbenchSection.Reminders -> {
                    _state.value = _state.value.copy(
                        reminders = repository.loadReminders(),
                        loading = false,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                loading = false,
                error = "操作已完成，但列表刷新失败：${e.workbenchMessage()}"
            )
        }
    }

    private fun mutate(action: String, block: suspend () -> String?) {
        if (_state.value.busyAction != null) return
        _state.value = _state.value.copy(busyAction = action, error = null, feedback = null)
        viewModelScope.launch {
            try {
                val feedback = block()
                _state.value = _state.value.copy(busyAction = null, feedback = feedback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busyAction = null,
                    error = e.workbenchMessage()
                )
            }
        }
    }
}

private fun Throwable.workbenchMessage(): String {
    val message = when (this) {
        is NativeWorkbenchRequestException -> message
        else -> message
    }.orEmpty().trim()
    return message.ifEmpty { "操作失败，请稍后重试。" }.take(240)
}

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
import com.pim.core.network.UploadSessionChangedException
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
    Confirmations("确认"),
    Habits("习惯"),
    Devices("设备"),
    Knowledge("知识分类"),
    Diagnostics("诊断"),
    QuickNotes("快速记录"),
    ComputerUsage("电脑与浏览器"),
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
    val habits: List<NativeHabit> = emptyList(),
    val confirmations: List<NativeOperationConfirmation> = emptyList(),
    val selectedConfirmationId: String? = null,
    val confirmationDetail: NativeOperationConfirmation? = null,
    val loadingConfirmationDetail: Boolean = false,
    val computerUsage: ComputerUsageSnapshot? = null,
    val computerUsageDevices: List<DeviceListDto> = emptyList(),
    val computerUsageFrom: String = "",
    val computerUsageTo: String = "",
    val computerUsageDeviceId: String = "",
    val computerUsageTypeFilter: ComputerUsageTypeFilter = ComputerUsageTypeFilter.All,
    val computerUsagePage: Int = 1,
    val selectedComputerRecordIndex: Int? = null,
)

@HiltViewModel
class NativeWorkbenchViewModel @Inject constructor(
    private val repository: NativeWorkbenchRepository,
    private val legacyQuickNotes: LegacyQuickNoteRepository,
    private val transport: NativeWorkbenchTransport,
) : ViewModel() {
    private val _state = MutableStateFlow(NativeWorkbenchUiState())
    val state: StateFlow<NativeWorkbenchUiState> = _state.asStateFlow()
    private val sessionGuard = NativeWorkbenchSessionGuard(transport)
    private var refreshJob: Job? = null
    private var computerUsageRequestVersion = 0L

    private data class BoundWorkbench(
        val session: NativeWorkbenchSession,
        val repository: NativeWorkbenchRepository,
        val quickNotes: LegacyQuickNoteRepository,
        val computerUsage: ComputerUsageRepository,
    )

    init {
        refresh()
    }

    fun selectSection(section: NativeWorkbenchSection) {
        if (_state.value.section == section) return
        _state.value = _state.value.copy(
            section = section,
            computerUsagePage = if (section == NativeWorkbenchSection.ComputerUsage) 1 else _state.value.computerUsagePage,
            error = null,
            feedback = null,
            tasks = if (section == NativeWorkbenchSection.Today ||
                section == NativeWorkbenchSection.Tasks
            ) emptyList() else _state.value.tasks,
            taskTotal = if (section == NativeWorkbenchSection.Today ||
                section == NativeWorkbenchSection.Tasks
            ) 0 else _state.value.taskTotal,
            events = if (section == NativeWorkbenchSection.Today ||
                section == NativeWorkbenchSection.Calendar
            ) emptyList() else _state.value.events,
            eventTotal = if (section == NativeWorkbenchSection.Today ||
                section == NativeWorkbenchSection.Calendar
            ) 0 else _state.value.eventTotal,
            calendars = if (section == NativeWorkbenchSection.Today ||
                section == NativeWorkbenchSection.Calendar
            ) emptyList() else _state.value.calendars,
            selectedApp = null,
            appContexts = emptyList(),
            quickNoteDetail = null,
            selectedConfirmationId = null,
            confirmationDetail = null,
            loadingConfirmationDetail = false,
            computerUsage = if (section == NativeWorkbenchSection.ComputerUsage) null else _state.value.computerUsage,
            computerUsageDevices = if (section == NativeWorkbenchSection.ComputerUsage) emptyList() else _state.value.computerUsageDevices,
            selectedComputerRecordIndex = null,
        )
        refresh()
    }

    fun enterToday() {
        if (_state.value.section != NativeWorkbenchSection.Today) {
            selectSection(NativeWorkbenchSection.Today)
        } else if (!_state.value.loading) {
            refresh()
        }
    }

    fun currentEditorSession(): NativeWorkbenchSession? =
        runCatching { sessionGuard.requireLoadedSession() }.getOrNull()

    fun isCurrentEditorSession(session: NativeWorkbenchSession): Boolean =
        sessionGuard.isLoadedSession(session) && sessionGuard.canApply(session)

    fun setSearch(value: String) {
        if (_state.value.search == value) return
        _state.value = _state.value.copy(search = value)
        when (_state.value.section) {
            NativeWorkbenchSection.Tasks -> loadTasks()
            NativeWorkbenchSection.Knowledge -> loadKnowledge()
            NativeWorkbenchSection.QuickNotes -> loadQuickNotes()
            NativeWorkbenchSection.ComputerUsage -> Unit
            else -> Unit
        }
    }

    fun setDeviceSort(sortBy: String) {
        if (sortBy != "active" && sortBy != "data") return
        if (_state.value.deviceSort == sortBy) return
        _state.value = _state.value.copy(deviceSort = sortBy)
        loadDevices()
    }

    fun refresh() {
        refreshJob?.cancel()
        val previouslyLoaded = sessionGuard.loadedSessionOrNull()
        val binding = try {
            bind(sessionGuard.beginLoad())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            clearStaleSession()
            _state.value = _state.value.copy(error = e.workbenchMessage())
            return
        }
        if (previouslyLoaded != null && previouslyLoaded != binding.session) {
            clearAccountScopedData()
        }
        _state.value = _state.value.copy(loading = true, error = null)
        val requestedSection = _state.value.section
        val computerUsageVersion = if (requestedSection == NativeWorkbenchSection.ComputerUsage) {
            ++computerUsageRequestVersion
        } else null
        refreshJob = viewModelScope.launch {
            try {
                when (_state.value.section) {
                    NativeWorkbenchSection.Today -> {
                        val tasks = binding.repository.loadTasks()
                        val events = binding.repository.loadEvents(LocalDate.now())
                        val calendars = binding.repository.loadCalendars()
                        var usageError: String? = null
                        val mobileSummary = try {
                            binding.repository.loadTodayUsage(LocalDate.now())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            usageError = e.workbenchMessage()
                            null
                        }
                        val today = LocalDate.now()
                        updateIfCurrent(binding) {
                            it.copy(
                                tasks = todayTasks(
                                    tasks.items,
                                    today,
                                    java.time.ZoneId.systemDefault()
                                ),
                                taskTotal = tasks.totalCount,
                                events = events.items,
                                eventTotal = events.totalCount,
                                calendars = calendars,
                                mobileSummary = mobileSummary,
                                mobileSummaryError = usageError,
                            )
                        }
                    }
                    NativeWorkbenchSection.Tasks -> {
                        val tasks = binding.repository.loadTasks(_state.value.search)
                        var booksError: String? = null
                        val books = try {
                            binding.repository.loadTaskBooks()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            booksError = e.workbenchMessage()
                            emptyList()
                        }
                        updateIfCurrent(binding) {
                            it.copy(
                                tasks = tasks.items,
                                taskTotal = tasks.totalCount,
                                taskBooks = books,
                                taskBooksError = booksError,
                            )
                        }
                    }
                    NativeWorkbenchSection.Calendar -> {
                        val calendars = binding.repository.loadCalendars()
                        val events = binding.repository.loadEvents(LocalDate.now())
                        updateIfCurrent(binding) {
                            it.copy(
                                calendars = calendars,
                                events = events.items,
                                eventTotal = events.totalCount,
                            )
                        }
                    }
                    NativeWorkbenchSection.Devices -> {
                        val devices = binding.repository.loadDevices(_state.value.deviceSort)
                        updateIfCurrent(binding) { it.copy(devices = devices) }
                    }
                    NativeWorkbenchSection.Knowledge -> {
                        val categories = binding.repository.loadCategoryTree()
                        val apps = binding.repository.loadKnowledgeApps(_state.value.search)
                        updateIfCurrent(binding) {
                            it.copy(categories = categories, apps = apps)
                        }
                    }
                    NativeWorkbenchSection.Diagnostics -> {
                        val status = binding.repository.loadSystemStatus()
                        updateIfCurrent(binding) { it.copy(systemStatus = status) }
                    }
                    NativeWorkbenchSection.QuickNotes -> {
                        val notes = binding.quickNotes.load(_state.value.search)
                        updateIfCurrent(binding) {
                            it.copy(
                                quickNotes = notes.items,
                                quickNoteTotal = notes.totalCount,
                            )
                        }
                    }
                    NativeWorkbenchSection.Reminders -> {
                        val reminders = binding.repository.loadReminders()
                        updateIfCurrent(binding) { it.copy(reminders = reminders) }
                    }
                    NativeWorkbenchSection.Confirmations -> {
                        val confirmations = binding.repository.loadPendingConfirmations()
                        updateIfCurrent(binding) {
                            it.copy(
                                confirmations = confirmations,
                                selectedConfirmationId = null,
                                confirmationDetail = null,
                                loadingConfirmationDetail = false,
                            )
                        }
                    }
                    NativeWorkbenchSection.Habits -> {
                        val habits = binding.repository.loadHabits()
                        updateIfCurrent(binding) { it.copy(habits = habits) }
                    }
                    NativeWorkbenchSection.ComputerUsage -> {
                        val from = _state.value.computerUsageFrom.ifBlank { LocalDate.now().toString() }
                        val to = _state.value.computerUsageTo.ifBlank { from }
                        val deviceId = _state.value.computerUsageDeviceId
                        val page = _state.value.computerUsagePage
                        val typeFilter = _state.value.computerUsageTypeFilter
                        val result = binding.computerUsage.load(from, to, deviceId, page, typeFilter)
                        updateIfCurrent(binding) {
                            if (it.section == NativeWorkbenchSection.ComputerUsage &&
                                computerUsageVersion == computerUsageRequestVersion
                            ) it.copy(
                                computerUsage = result,
                                computerUsageDevices = result.devices,
                                computerUsageFrom = from,
                                computerUsageTo = to,
                                selectedComputerRecordIndex = null,
                            ) else it
                        }
                    }
                }
                if (sessionGuard.canApply(binding.session)) {
                    if (_state.value.section == requestedSection &&
                        (computerUsageVersion == null || computerUsageVersion == computerUsageRequestVersion)
                    ) _state.value = _state.value.copy(loading = false, error = null)
                } else {
                    clearStaleSession()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else if (_state.value.section == requestedSection &&
                    (computerUsageVersion == null || computerUsageVersion == computerUsageRequestVersion)
                ) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.workbenchMessage()
                    )
                }
            }
        }
    }

    fun refreshTasks() = loadTasks()

    fun setComputerUsageFrom(value: String) {
        if (_state.value.computerUsageFrom == value) return
        updateComputerUsageFilters { it.copy(computerUsageFrom = value) }
    }

    fun setComputerUsageTo(value: String) {
        if (_state.value.computerUsageTo == value) return
        updateComputerUsageFilters { it.copy(computerUsageTo = value) }
    }

    fun setComputerUsageDeviceId(value: String) {
        if (_state.value.computerUsageDeviceId == value) return
        updateComputerUsageFilters { it.copy(computerUsageDeviceId = value) }
    }

    fun setComputerUsageTypeFilter(value: ComputerUsageTypeFilter) {
        if (_state.value.computerUsageTypeFilter == value) return
        updateComputerUsageFilters { it.copy(computerUsageTypeFilter = value) }
    }

    private fun updateComputerUsageFilters(transform: (NativeWorkbenchUiState) -> NativeWorkbenchUiState) {
        if (_state.value.section == NativeWorkbenchSection.ComputerUsage) {
            computerUsageRequestVersion++
            refreshJob?.cancel()
        }
        _state.value = transform(_state.value).copy(
            computerUsage = null,
            computerUsagePage = 1,
            selectedComputerRecordIndex = null,
            loading = if (_state.value.section == NativeWorkbenchSection.ComputerUsage) false else _state.value.loading,
            error = null,
        )
    }

    fun selectComputerRecord(index: Int?) {
        if (_state.value.section == NativeWorkbenchSection.ComputerUsage &&
            requireLoadedBindingOrShowError() != null
        ) _state.value = _state.value.copy(selectedComputerRecordIndex = index)
    }

    fun searchComputerUsage() {
        if (_state.value.section != NativeWorkbenchSection.ComputerUsage) return
        _state.value = _state.value.copy(
            computerUsagePage = 1,
            computerUsage = null,
            selectedComputerRecordIndex = null,
            loading = false,
        )
        loadComputerUsage()
    }

    fun changeComputerUsagePage(page: Int) {
        val current = _state.value.computerUsage ?: return
        if (_state.value.section != NativeWorkbenchSection.ComputerUsage ||
            page !in 1..current.records.totalPages || page == _state.value.computerUsagePage
        ) return
        _state.value = _state.value.copy(
            computerUsagePage = page,
            computerUsage = null,
            selectedComputerRecordIndex = null,
            loading = false,
        )
        loadComputerUsage()
    }

    private fun loadComputerUsage() {
        refreshJob?.cancel()
        val binding = requireLoadedBindingOrShowError() ?: return
        val from = _state.value.computerUsageFrom.ifBlank { LocalDate.now().toString() }
        val to = _state.value.computerUsageTo.ifBlank { from }
        val deviceId = _state.value.computerUsageDeviceId
        val page = _state.value.computerUsagePage
        val requestVersion = ++computerUsageRequestVersion
        val typeFilter = _state.value.computerUsageTypeFilter
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            computerUsageFrom = from,
            computerUsageTo = to,
        )
        refreshJob = viewModelScope.launch {
            try {
                val result = binding.computerUsage.load(from, to, deviceId, page, typeFilter)
                updateIfCurrent(binding) {
                    if (it.section == NativeWorkbenchSection.ComputerUsage &&
                        requestVersion == computerUsageRequestVersion
                    ) it.copy(
                        computerUsage = result,
                        computerUsageDevices = result.devices,
                        selectedComputerRecordIndex = null,
                        loading = false,
                    ) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) clearStaleSession()
                else if (_state.value.section == NativeWorkbenchSection.ComputerUsage &&
                    requestVersion == computerUsageRequestVersion
                ) _state.value = _state.value.copy(loading = false, error = e.workbenchMessage())
            }
        }
    }

    fun refreshQuickNotes() = refresh()

    fun createTask(draft: NativeTaskDraft, editorSession: NativeWorkbenchSession) =
        mutate("新建任务", editorSession) { binding ->
        binding.repository.createTask(draft)
        refreshCurrent(binding, showLoading = false)
        "任务已创建"
    }

    fun createTaskBook(name: String) = mutate("新建任务本") { binding ->
        binding.repository.createTaskBook(name)
        val books = binding.repository.loadTaskBooks()
        updateIfCurrent(binding) {
            it.copy(taskBooks = books, taskBooksError = null)
        }
        "任务本已创建"
    }

    fun updateTask(
        task: TaskResponse,
        draft: NativeTaskDraft,
        editorSession: NativeWorkbenchSession,
    ) = mutate("修改任务", editorSession) { binding ->
        binding.repository.updateTask(task, draft)
        refreshCurrent(binding, showLoading = false)
        "任务已保存"
    }

    fun toggleTaskCompleted(task: TaskResponse) = mutate(
        if (task.status == "COMPLETED") "重新打开任务" else "完成任务"
    ) { binding ->
        binding.repository.setTaskCompleted(task, task.status != "COMPLETED")
        refreshCurrent(binding, showLoading = false)
        if (task.status == "COMPLETED") "任务已重新打开" else "任务已完成"
    }

    fun deleteTask(task: TaskResponse) = mutate("删除任务") { binding ->
        binding.repository.deleteTask(task)
        refreshCurrent(binding, showLoading = false)
        "任务已删除"
    }

    fun startTaskTimer(task: TaskResponse) = mutate("开始计时") { binding ->
        binding.repository.startTaskTimer(task)
        "任务计时已开始"
    }

    fun pauseTaskTimer(task: TaskResponse) = mutate("暂停计时") { binding ->
        binding.repository.pauseTaskTimer(task)
        "任务计时已暂停"
    }

    fun stopTaskTimer(task: TaskResponse) = mutate("结束计时") { binding ->
        binding.repository.stopTaskTimer(task)
        "任务计时已结束"
    }

    fun createEvent(draft: NativeEventDraft, editorSession: NativeWorkbenchSession) =
        mutate("新建日程", editorSession) { binding ->
        binding.repository.createEvent(draft)
        refreshCurrent(binding, showLoading = false)
        "日程已创建"
    }

    fun createCalendar(name: String, color: String) = mutate("新建日历") { binding ->
        binding.repository.createCalendar(name, color)
        val calendars = binding.repository.loadCalendars()
        updateIfCurrent(binding) { it.copy(calendars = calendars) }
        "日历已创建"
    }

    fun updateEvent(
        event: EventResponse,
        draft: NativeEventDraft,
        editorSession: NativeWorkbenchSession,
    ) = mutate("修改日程", editorSession) { binding ->
        binding.repository.updateEvent(event, draft)
        refreshCurrent(binding, showLoading = false)
        "日程已保存"
    }

    fun deleteEvent(event: EventResponse) = mutate("删除日程") { binding ->
        binding.repository.deleteEvent(event)
        refreshCurrent(binding, showLoading = false)
        "日程已删除"
    }

    fun renameDevice(device: DeviceListDto, displayName: String) = mutate("重命名设备") { binding ->
        binding.repository.renameDevice(device.deviceId, displayName)
        val devices = binding.repository.loadDevices(_state.value.deviceSort)
        updateIfCurrent(binding) { it.copy(devices = devices) }
        "设备名称已保存"
    }

    fun requestDeviceDelete(device: DeviceListDto) = mutate("读取删除影响") { binding ->
        val preview = binding.repository.deviceDeletePreview(device.deviceId)
        updateIfCurrent(binding) { it.copy(deviceDeletePreview = preview) }
        null
    }

    fun cancelDeviceDelete() {
        _state.value = _state.value.copy(deviceDeletePreview = null)
    }

    fun confirmDeviceDelete() = mutate("删除设备") { binding ->
        val preview = _state.value.deviceDeletePreview
            ?: throw NativeWorkbenchRequestException("删除预览已失效，请重试。")
        val result = binding.repository.deleteDevice(preview.deviceId)
        val devices = binding.repository.loadDevices(_state.value.deviceSort)
        updateIfCurrent(binding) {
            it.copy(deviceDeletePreview = null, devices = devices)
        }
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
    ) = mutate("保存分类") { binding ->
        binding.repository.saveCategory(
            id = existing?.id,
            name = name,
            parentId = parentId,
            color = color,
            icon = icon,
            productivity = productivity,
            sortOrder = sortOrder,
        )
        refreshCurrent(binding, showLoading = false)
        "分类已保存"
    }

    fun deleteCategory(category: CategoryTreeNode) = mutate("删除分类") { binding ->
        binding.repository.deleteCategory(category)
        refreshCurrent(binding, showLoading = false)
        "分类已删除"
    }

    fun selectKnowledgeApp(app: AppKnowledgeAppDto) = mutate("读取应用上下文") { binding ->
        val contexts = binding.repository.loadAppContexts(app.id)
        updateIfCurrent(binding) {
            it.copy(selectedApp = app, appContexts = contexts)
        }
        null
    }

    fun closeKnowledgeApp() {
        _state.value = _state.value.copy(selectedApp = null, appContexts = emptyList())
    }

    fun saveAppContext(request: com.pim.core.contract.SaveAppKnowledgeContextRequest) =
        mutate("保存应用上下文") { binding ->
            binding.repository.saveAppContext(request)
            val app = _state.value.selectedApp
                ?: throw NativeWorkbenchRequestException("请先选择应用。")
            val contexts = binding.repository.loadAppContexts(app.id)
            val apps = binding.repository.loadKnowledgeApps(_state.value.search)
            updateIfCurrent(binding) {
                it.copy(appContexts = contexts, apps = apps)
            }
            "应用上下文已保存"
        }

    fun deleteAppContext(context: AppKnowledgeContextDto) = mutate("删除应用上下文") { binding ->
        binding.repository.deleteAppContext(context)
        val app = _state.value.selectedApp
        if (app != null) {
            val contexts = binding.repository.loadAppContexts(app.id)
            updateIfCurrent(binding) { it.copy(appContexts = contexts) }
        }
        "应用上下文已删除"
    }

    fun openQuickNote(id: String) = mutate("读取快速记录") { binding ->
        val detail = binding.quickNotes.get(id)
        updateIfCurrent(binding) { it.copy(quickNoteDetail = detail) }
        null
    }

    fun closeQuickNote() {
        _state.value = _state.value.copy(quickNoteDetail = null)
    }

    fun createQuickNote(contentMarkdown: String) = mutate("新建快速记录") { binding ->
        binding.quickNotes.create(contentMarkdown)
        refreshCurrent(binding, showLoading = false)
        "快速记录已创建"
    }

    fun updateQuickNote(detail: QuickNoteDetailDto, contentMarkdown: String) =
        mutate("修改快速记录") { binding ->
            val updated = binding.quickNotes.update(detail, contentMarkdown)
            updateIfCurrent(binding) { it.copy(quickNoteDetail = updated) }
            refreshCurrent(binding, showLoading = false)
            "快速记录已保存"
        }

    fun archiveQuickNote(detail: QuickNoteDetailDto) = mutate("归档快速记录") { binding ->
        val updated = binding.quickNotes.archive(detail)
        updateIfCurrent(binding) { it.copy(quickNoteDetail = updated) }
        refreshCurrent(binding, showLoading = false)
        "快速记录已归档"
    }

    fun processQuickNote(detail: QuickNoteDetailDto) = mutate("处理快速记录") { binding ->
        val updated = binding.quickNotes.process(detail)
        updateIfCurrent(binding) { it.copy(quickNoteDetail = updated) }
        refreshCurrent(binding, showLoading = false)
        "快速记录已进入处理流程"
    }

    fun restoreQuickNote(detail: QuickNoteDetailDto) = mutate("恢复快速记录") { binding ->
        val updated = binding.quickNotes.restore(detail)
        updateIfCurrent(binding) { it.copy(quickNoteDetail = updated) }
        refreshCurrent(binding, showLoading = false)
        "快速记录已恢复"
    }

    fun deleteQuickNote(detail: QuickNoteDetailDto) = mutate("删除快速记录") { binding ->
        binding.quickNotes.delete(detail)
        updateIfCurrent(binding) { it.copy(quickNoteDetail = null) }
        refreshCurrent(binding, showLoading = false)
        "快速记录已删除"
    }

    fun dismissReminder(reminder: NativeReminderAction) = mutate("忽略提醒") { binding ->
        binding.repository.dismissReminder(reminder.id)
        val reminders = binding.repository.loadReminders()
        updateIfCurrent(binding) { it.copy(reminders = reminders) }
        "提醒已忽略"
    }

    fun snoozeReminder(reminder: NativeReminderAction, scheduledAt: String) =
        mutate("延后提醒") { binding ->
            binding.repository.snoozeReminder(reminder.id, scheduledAt)
            val reminders = binding.repository.loadReminders()
            updateIfCurrent(binding) { it.copy(reminders = reminders) }
            "提醒已延后"
        }

    fun openConfirmation(id: String) {
        val binding = requireLoadedBindingOrShowError() ?: return
        if (_state.value.section != NativeWorkbenchSection.Confirmations) return
        _state.value = _state.value.copy(
            selectedConfirmationId = id,
            confirmationDetail = null,
            loadingConfirmationDetail = true,
            error = null,
        )
        viewModelScope.launch {
            try {
                val detail = binding.repository.loadConfirmation(id)
                updateIfCurrent(binding) {
                    if (it.section == NativeWorkbenchSection.Confirmations &&
                        it.selectedConfirmationId == id
                    ) {
                        it.copy(confirmationDetail = detail, loadingConfirmationDetail = false)
                    } else {
                        it
                    }
                }
                if (!sessionGuard.canApply(binding.session)) clearStaleSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else if (_state.value.selectedConfirmationId == id) {
                    _state.value = _state.value.copy(
                        loadingConfirmationDetail = false,
                        error = e.workbenchMessage(),
                    )
                }
            }
        }
    }

    fun closeConfirmationDetail() {
        _state.value = _state.value.copy(
            selectedConfirmationId = null,
            confirmationDetail = null,
            loadingConfirmationDetail = false,
        )
    }

    fun rejectConfirmation(confirmation: NativeOperationConfirmation) {
        if (confirmation.status != 0 ||
            _state.value.confirmations.none { it.id == confirmation.id && it.status == 0 }
        ) return
        mutate("拒绝待确认操作") { binding ->
            binding.repository.rejectConfirmation(confirmation.id)
            val confirmations = try {
                binding.repository.loadPendingConfirmations()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateIfCurrent(binding) {
                    it.copy(
                        confirmations = it.confirmations.filterNot { item ->
                            item.id == confirmation.id
                        },
                        selectedConfirmationId = null,
                        confirmationDetail = null,
                        loadingConfirmationDetail = false,
                        error = "拒绝已提交，但列表刷新失败：${e.workbenchMessage()}",
                    )
                }
                return@mutate "待确认操作已拒绝"
            }
            updateIfCurrent(binding) {
                it.copy(
                    confirmations = confirmations,
                    selectedConfirmationId = null,
                    confirmationDetail = null,
                    loadingConfirmationDetail = false,
                )
            }
            "待确认操作已拒绝"
        }
    }

    fun archiveHabit(habit: NativeHabit) {
        if (_state.value.habits.none { it.id == habit.id }) return
        mutate("归档习惯") { binding ->
            val archived = binding.repository.archiveHabit(habit.id)
            updateIfCurrent(binding) {
                it.copy(habits = it.habits.map { current ->
                    if (current.id == archived.id) archived else current
                })
            }
            "习惯已归档"
        }
    }

    private fun loadTasks(showLoading: Boolean = true) {
        refreshJob?.cancel()
        val binding = requireLoadedBindingOrShowError() ?: return
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val tasks = binding.repository.loadTasks(_state.value.search)
                updateIfCurrent(binding) {
                    it.copy(
                        tasks = tasks.items,
                        taskTotal = tasks.totalCount,
                        loading = false,
                    )
                }
                if (!sessionGuard.canApply(binding.session)) clearStaleSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.workbenchMessage()
                    )
                }
            }
        }
    }

    private fun loadKnowledge(showLoading: Boolean = true) {
        refreshJob?.cancel()
        val binding = requireLoadedBindingOrShowError() ?: return
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val categories = binding.repository.loadCategoryTree()
                val apps = binding.repository.loadKnowledgeApps(_state.value.search)
                updateIfCurrent(binding) {
                    it.copy(categories = categories, apps = apps, loading = false)
                }
                if (!sessionGuard.canApply(binding.session)) clearStaleSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.workbenchMessage()
                    )
                }
            }
        }
    }

    private fun loadQuickNotes(showLoading: Boolean = true) {
        refreshJob?.cancel()
        val binding = requireLoadedBindingOrShowError() ?: return
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val notes = binding.quickNotes.load(_state.value.search)
                updateIfCurrent(binding) {
                    it.copy(
                        quickNotes = notes.items,
                        quickNoteTotal = notes.totalCount,
                        loading = false,
                    )
                }
                if (!sessionGuard.canApply(binding.session)) clearStaleSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.workbenchMessage()
                    )
                }
            }
        }
    }

    private fun loadDevices(showLoading: Boolean = true) {
        refreshJob?.cancel()
        val binding = requireLoadedBindingOrShowError() ?: return
        if (showLoading) _state.value = _state.value.copy(loading = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                val devices = binding.repository.loadDevices(_state.value.deviceSort)
                updateIfCurrent(binding) {
                    it.copy(devices = devices, loading = false)
                }
                if (!sessionGuard.canApply(binding.session)) clearStaleSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.workbenchMessage()
                    )
                }
            }
        }
    }

    private suspend fun refreshCurrent(binding: BoundWorkbench, showLoading: Boolean) {
        if (showLoading) _state.value = _state.value.copy(loading = true)
        try {
            when (_state.value.section) {
                NativeWorkbenchSection.Today -> {
                    val tasks = binding.repository.loadTasks()
                    val events = binding.repository.loadEvents(LocalDate.now())
                    var usageError: String? = null
                    val mobileSummary = try {
                        binding.repository.loadTodayUsage(LocalDate.now())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        usageError = e.workbenchMessage()
                        null
                    }
                    val today = LocalDate.now()
                    updateIfCurrent(binding) {
                        it.copy(
                            tasks = todayTasks(
                                tasks.items,
                                today,
                                java.time.ZoneId.systemDefault()
                            ),
                            taskTotal = tasks.totalCount,
                            events = events.items,
                            eventTotal = events.totalCount,
                            mobileSummary = mobileSummary,
                            mobileSummaryError = usageError,
                            loading = false,
                        )
                    }
                }
                NativeWorkbenchSection.Tasks -> {
                    val tasks = binding.repository.loadTasks(_state.value.search)
                    updateIfCurrent(binding) {
                        it.copy(
                            tasks = tasks.items,
                            taskTotal = tasks.totalCount,
                            loading = false,
                        )
                    }
                }
                NativeWorkbenchSection.Calendar -> {
                    val events = binding.repository.loadEvents(LocalDate.now())
                    updateIfCurrent(binding) {
                        it.copy(
                            events = events.items,
                            eventTotal = events.totalCount,
                            loading = false,
                        )
                    }
                }
                NativeWorkbenchSection.Devices -> {
                    val devices = binding.repository.loadDevices(_state.value.deviceSort)
                    updateIfCurrent(binding) {
                        it.copy(devices = devices, loading = false)
                    }
                }
                NativeWorkbenchSection.Knowledge -> {
                    val categories = binding.repository.loadCategoryTree()
                    val apps = binding.repository.loadKnowledgeApps(_state.value.search)
                    updateIfCurrent(binding) {
                        it.copy(categories = categories, apps = apps, loading = false)
                    }
                }
                NativeWorkbenchSection.Diagnostics -> {
                    val status = binding.repository.loadSystemStatus()
                    updateIfCurrent(binding) {
                        it.copy(systemStatus = status, loading = false)
                    }
                }
                NativeWorkbenchSection.QuickNotes -> {
                    val notes = binding.quickNotes.load(_state.value.search)
                    updateIfCurrent(binding) {
                        it.copy(
                            quickNotes = notes.items,
                            quickNoteTotal = notes.totalCount,
                            loading = false,
                        )
                    }
                }
                NativeWorkbenchSection.Reminders -> {
                    val reminders = binding.repository.loadReminders()
                    updateIfCurrent(binding) {
                        it.copy(reminders = reminders, loading = false)
                    }
                }
                NativeWorkbenchSection.Confirmations -> {
                    val confirmations = binding.repository.loadPendingConfirmations()
                    updateIfCurrent(binding) {
                        it.copy(
                            confirmations = confirmations,
                            selectedConfirmationId = null,
                            confirmationDetail = null,
                            loadingConfirmationDetail = false,
                            loading = false,
                        )
                    }
                }
                NativeWorkbenchSection.Habits -> {
                    val habits = binding.repository.loadHabits()
                    updateIfCurrent(binding) {
                        it.copy(habits = habits, loading = false)
                    }
                }
                NativeWorkbenchSection.ComputerUsage -> {
                    val from = _state.value.computerUsageFrom.ifBlank { LocalDate.now().toString() }
                    val to = _state.value.computerUsageTo.ifBlank { from }
                    val deviceId = _state.value.computerUsageDeviceId
                    val page = _state.value.computerUsagePage
                    val typeFilter = _state.value.computerUsageTypeFilter
                    val requestVersion = ++computerUsageRequestVersion
                    val result = binding.computerUsage.load(
                        from, to, deviceId, page, typeFilter,
                    )
                    updateIfCurrent(binding) {
                        if (it.section == NativeWorkbenchSection.ComputerUsage &&
                            requestVersion == computerUsageRequestVersion
                        ) it.copy(
                            computerUsage = result,
                            computerUsageDevices = result.devices,
                            computerUsageFrom = from,
                            computerUsageTo = to,
                            selectedComputerRecordIndex = null,
                            loading = false,
                        ) else it
                    }
                }
            }
            if (!sessionGuard.canApply(binding.session)) clearStaleSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!sessionGuard.canApply(binding.session)) {
                clearStaleSession()
            } else {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "操作已完成，但列表刷新失败：${e.workbenchMessage()}"
                )
            }
        }
    }

    private fun mutate(
        action: String,
        editorSession: NativeWorkbenchSession? = null,
        block: suspend (BoundWorkbench) -> String?,
    ) {
        if (_state.value.busyAction != null) return
        val binding = if (editorSession == null) {
            requireLoadedBindingOrShowError()
        } else {
            requireEditorBindingOrShowError(editorSession)
        } ?: return
        _state.value = _state.value.copy(busyAction = action, error = null, feedback = null)
        viewModelScope.launch {
            try {
                val feedback = block(binding)
                if (sessionGuard.canApply(binding.session)) {
                    _state.value = _state.value.copy(busyAction = null, feedback = feedback)
                } else {
                    clearStaleSession()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sessionGuard.canApply(binding.session)) {
                    clearStaleSession()
                } else {
                    _state.value = _state.value.copy(
                        busyAction = null,
                        error = e.workbenchMessage()
                    )
                }
            }
        }
    }

    private fun bind(session: NativeWorkbenchSession): BoundWorkbench {
        val api = transport.apiFor(session)
        return BoundWorkbench(
            session = session,
            repository = repository.boundTo(api),
            quickNotes = legacyQuickNotes.boundTo(api),
            computerUsage = ComputerUsageRepository(api),
        )
    }

    private fun requireLoadedBindingOrShowError(): BoundWorkbench? = try {
        bind(sessionGuard.requireLoadedSession())
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        clearStaleSession()
        _state.value = _state.value.copy(error = e.workbenchMessage())
        null
    }

    private fun requireEditorBindingOrShowError(
        editorSession: NativeWorkbenchSession,
    ): BoundWorkbench? = try {
        if (!sessionGuard.isLoadedSession(editorSession) || !sessionGuard.canApply(editorSession)) {
            throw UploadSessionChangedException()
        }
        bind(editorSession)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        _state.value = _state.value.copy(
            error = "编辑期间账号或服务器已切换；旧草稿已保留且不能提交，请关闭后重新打开。"
        )
        null
    }

    private fun updateIfCurrent(
        binding: BoundWorkbench,
        transform: (NativeWorkbenchUiState) -> NativeWorkbenchUiState,
    ) {
        if (sessionGuard.canApply(binding.session)) {
            _state.value = transform(_state.value)
        }
    }

    private fun clearStaleSession() {
        clearAccountScopedData(error = "账号或服务器已切换，旧页面数据已清除，请重新加载。")
    }

    private fun clearAccountScopedData(error: String? = null) {
        _state.value = NativeWorkbenchUiState(
            section = _state.value.section,
            search = _state.value.search,
            deviceSort = _state.value.deviceSort,
            computerUsage = null,
            computerUsageDevices = emptyList(),
            selectedComputerRecordIndex = null,
            error = error,
        )
    }
}

private fun Throwable.workbenchMessage(): String {
    val message = when (this) {
        is NativeWorkbenchRequestException -> message
        else -> message
    }.orEmpty().trim()
    return message.ifEmpty { "操作失败，请稍后重试。" }.take(240)
}

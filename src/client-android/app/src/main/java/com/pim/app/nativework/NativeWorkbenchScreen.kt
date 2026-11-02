@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pim.app.nativework

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pim.app.nativework.quicknotes.QuickNoteDraft
import com.pim.app.nativework.quicknotes.isLegacyQuickNoteHtml
import com.pim.core.contract.AppKnowledgeAppDto
import com.pim.core.contract.CategoryTreeNode
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.EventResponse
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.QuickNoteListItemDto
import com.pim.core.contract.SaveAppKnowledgeContextRequest
import com.pim.core.contract.TaskBookDto
import com.pim.core.contract.TaskResponse
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class TaskEditorTarget(val task: TaskResponse? = null)
private data class EventEditorTarget(val event: EventResponse? = null)
private data class DeviceRenameTarget(val device: DeviceListDto)
private data class CategoryEditorTarget(
    val category: CategoryTreeNode? = null,
    val parentId: String? = null,
)
@Composable
fun NativeWorkbenchScreen(
    modifier: Modifier = Modifier,
    onOpenStatusCenter: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: NativeWorkbenchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var taskEditor by remember { mutableStateOf<TaskEditorTarget?>(null) }
    var taskSavePending by remember { mutableStateOf(false) }
    var showTaskBookCreate by remember { mutableStateOf(false) }
    var showCalendarCreate by remember { mutableStateOf(false) }
    var taskToDelete by remember { mutableStateOf<TaskResponse?>(null) }
    var eventEditor by remember { mutableStateOf<EventEditorTarget?>(null) }
    var eventSavePending by remember { mutableStateOf(false) }
    var eventToDelete by remember { mutableStateOf<EventResponse?>(null) }
    var deviceRename by remember { mutableStateOf<DeviceRenameTarget?>(null) }
    var categoryEditor by remember { mutableStateOf<CategoryEditorTarget?>(null) }
    var categoryToDelete by remember { mutableStateOf<CategoryTreeNode?>(null) }
    var contextEditor by remember { mutableStateOf<AppKnowledgeAppDto?>(null) }
    var showQuickNoteCreate by remember { mutableStateOf(false) }
    var showQuickNoteDetail by remember { mutableStateOf(false) }
    var reminderToSnooze by remember { mutableStateOf<NativeReminderAction?>(null) }
    var confirmationToReject by remember { mutableStateOf<NativeOperationConfirmation?>(null) }
    var habitToArchive by remember { mutableStateOf<NativeHabit?>(null) }

    LaunchedEffect(state.feedback, taskSavePending) {
        if (taskSavePending && state.feedback in setOf("任务已创建", "任务已保存")) {
            taskEditor = null
            taskSavePending = false
        }
    }
    LaunchedEffect(state.feedback, eventSavePending) {
        if (eventSavePending && state.feedback in setOf("日程已创建", "日程已保存")) {
            eventEditor = null
            eventSavePending = false
        }
    }

    Column(modifier.fillMaxSize()) {
        WorkbenchHeader(
            section = state.section,
            busy = state.busyAction,
            onRefresh = viewModel::refresh
        )
        SectionSelector(
            selected = state.section,
            enabled = state.busyAction == null,
            onSelected = viewModel::selectSection
        )
        if (state.section != NativeWorkbenchSection.Today && state.loading) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在读取服务端…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.section != NativeWorkbenchSection.Today) state.busyAction?.let {
            Text(
                "$it…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.section != NativeWorkbenchSection.Today) {
            state.error?.let { MessageBanner("操作未完成：$it", isError = true) }
            state.feedback?.let { MessageBanner(it, isError = false) }
        }

        when (state.section) {
            NativeWorkbenchSection.Today -> NativeWorkbenchTodaySection(
                modifier = Modifier.weight(1f),
                viewModel = viewModel,
                showOperationStatus = false,
            )
            NativeWorkbenchSection.Tasks -> TasksNativeContent(
                state = state,
                viewModel = viewModel,
                onNewTask = { taskEditor = TaskEditorTarget() },
                onNewTaskBook = { showTaskBookCreate = true },
                onEditTask = { taskEditor = TaskEditorTarget(it) },
                onDeleteTask = { taskToDelete = it },
            )
            NativeWorkbenchSection.Calendar -> CalendarNativeContent(
                state = state,
                onNewEvent = { eventEditor = EventEditorTarget() },
                onCreateCalendar = { showCalendarCreate = true },
                onEditEvent = { eventEditor = EventEditorTarget(it) },
                onDeleteEvent = { eventToDelete = it },
            )
            NativeWorkbenchSection.Reminders -> RemindersNativeContent(
                state = state,
                onDismiss = viewModel::dismissReminder,
                onSnooze = { reminderToSnooze = it },
            )
            NativeWorkbenchSection.Confirmations -> ConfirmationsNativeContent(
                state = state,
                onOpen = viewModel::openConfirmation,
                onReject = { confirmationToReject = it },
                onCloseDetail = viewModel::closeConfirmationDetail,
            )
            NativeWorkbenchSection.Habits -> HabitsNativeContent(
                state = state,
                onArchive = { habitToArchive = it },
            )
            NativeWorkbenchSection.Devices -> DevicesNativeContent(
                state = state,
                onSort = viewModel::setDeviceSort,
                onRename = { deviceRename = DeviceRenameTarget(it) },
                onDelete = viewModel::requestDeviceDelete,
            )
            NativeWorkbenchSection.Knowledge -> KnowledgeNativeContent(
                state = state,
                viewModel = viewModel,
                onAddCategory = { categoryEditor = CategoryEditorTarget(parentId = it) },
                onEditCategory = { categoryEditor = CategoryEditorTarget(category = it) },
                onDeleteCategory = { categoryToDelete = it },
                onAddContext = { contextEditor = it },
            )
            NativeWorkbenchSection.Diagnostics -> DiagnosticsNativeContent(
                state = state,
                onOpenStatusCenter = onOpenStatusCenter,
                onOpenSettings = onOpenSettings,
            )
            NativeWorkbenchSection.QuickNotes -> QuickNotesNativeContent(
                state = state,
                viewModel = viewModel,
                onNew = { showQuickNoteCreate = true },
                onOpen = { item ->
                    showQuickNoteDetail = true
                    viewModel.openQuickNote(item.id)
                },
            )
        }
    }

    taskEditor?.let { target ->
        TaskEditorDialog(
            task = target.task,
            taskBooks = state.taskBooks,
            isSaving = state.busyAction != null,
            operationError = if (taskSavePending) state.error else null,
            onDismiss = { taskEditor = null; taskSavePending = false },
            onSave = { draft ->
                taskSavePending = true
                if (target.task == null) viewModel.createTask(draft)
                else viewModel.updateTask(target.task, draft)
            }
        )
    }
    if (showTaskBookCreate) {
        NameAndColorDialog(
            title = "新建任务本",
            nameLabel = "任务本名称",
            defaultColor = null,
            onDismiss = { showTaskBookCreate = false },
            onSave = { name, _ ->
                showTaskBookCreate = false
                viewModel.createTaskBook(name)
            }
        )
    }
    taskToDelete?.let { task ->
        ConfirmDeleteDialog(
            title = "删除任务？",
            body = "将删除「${task.title}」。此操作会立即写入服务端。",
            onDismiss = { taskToDelete = null },
            onConfirm = {
                taskToDelete = null
                viewModel.deleteTask(task)
            }
        )
    }
    eventEditor?.let { target ->
        EventEditorDialog(
            calendars = state.calendars,
            event = target.event,
            isSaving = state.busyAction != null,
            operationError = if (eventSavePending) state.error else null,
            onDismiss = { eventEditor = null; eventSavePending = false },
            onSave = { draft ->
                eventSavePending = true
                if (target.event == null) viewModel.createEvent(draft)
                else viewModel.updateEvent(target.event, draft)
            }
        )
    }
    if (showCalendarCreate) {
        NameAndColorDialog(
            title = "新建日历",
            nameLabel = "日历名称",
            defaultColor = "#2563EB",
            onDismiss = { showCalendarCreate = false },
            onSave = { name, color ->
                showCalendarCreate = false
                viewModel.createCalendar(name, color)
            }
        )
    }
    eventToDelete?.let { event ->
        ConfirmDeleteDialog(
            title = "删除日程？",
            body = "将删除「${event.title}」及其当前日程记录。Outlook 镜像日程不会被原生页直接删除。",
            onDismiss = { eventToDelete = null },
            onConfirm = {
                eventToDelete = null
                viewModel.deleteEvent(event)
            }
        )
    }
    deviceRename?.let { target ->
        DeviceRenameDialog(
            device = target.device,
            onDismiss = { deviceRename = null },
            onSave = {
                deviceRename = null
                viewModel.renameDevice(target.device, it)
            }
        )
    }
    state.deviceDeletePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDeviceDelete,
            title = { Text("删除设备「${preview.displayName}」？") },
            text = {
                Text(
                    "将删除 ${preview.sessionCount} 个会话、${preview.eventCount} 条事件、" +
                        "${preview.locationCount} 个位置点、${preview.summaryCount} 条汇总。\n\n" +
                        "影响表：${preview.tables.joinToString { "${it.table} ${it.rowCount}" }}"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDeviceDelete) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeviceDelete) { Text("取消") }
            }
        )
    }
    categoryEditor?.let { target ->
        CategoryEditorDialog(
            category = target.category,
            initialParentId = target.parentId,
            onDismiss = { categoryEditor = null },
            onSave = { name, parentId, color, icon, productivity, sortOrder ->
                categoryEditor = null
                viewModel.saveCategory(
                    target.category, name, parentId, color, icon, productivity, sortOrder
                )
            }
        )
    }
    categoryToDelete?.let { category ->
        ConfirmDeleteDialog(
            title = "删除分类？",
            body = "将删除「${category.name}」。内置分类会被服务端拒绝，若仍有子项也会保留失败原因。",
            onDismiss = { categoryToDelete = null },
            onConfirm = {
                categoryToDelete = null
                viewModel.deleteCategory(category)
            }
        )
    }
    contextEditor?.let { app ->
        AppContextEditorDialog(
            app = app,
            categories = state.categories,
            onDismiss = { contextEditor = null },
            onSave = {
                contextEditor = null
                viewModel.saveAppContext(it)
            }
        )
    }
    if (showQuickNoteCreate) {
        QuickNoteCreateDialog(
            onDismiss = { showQuickNoteCreate = false },
            onSave = {
                showQuickNoteCreate = false
                viewModel.createQuickNote(it)
            }
        )
    }
    if (showQuickNoteDetail) {
        val detail = state.quickNoteDetail
        if (detail != null) {
            QuickNoteEditorDialog(
                detail = detail,
                onDismiss = {
                    showQuickNoteDetail = false
                    viewModel.closeQuickNote()
                },
                onSave = { viewModel.updateQuickNote(detail, it) },
                onArchive = { viewModel.archiveQuickNote(detail) },
                onProcess = { viewModel.processQuickNote(detail) },
                onRestore = { viewModel.restoreQuickNote(detail) },
                onDelete = {
                    showQuickNoteDetail = false
                    viewModel.deleteQuickNote(detail)
                },
            )
        } else if (state.busyAction == null) {
            AlertDialog(
                onDismissRequest = {
                    showQuickNoteDetail = false
                    viewModel.closeQuickNote()
                },
                title = { Text("快速记录未打开") },
                text = { Text(state.error ?: "服务端未返回记录内容。") },
                confirmButton = {
                    TextButton(onClick = {
                        showQuickNoteDetail = false
                        viewModel.closeQuickNote()
                    }) { Text("关闭") }
                }
            )
        }
    }
    reminderToSnooze?.let { reminder ->
        ReminderSnoozeDialog(
            reminder = reminder,
            onDismiss = { reminderToSnooze = null },
            onSave = { scheduledAt ->
                reminderToSnooze = null
                viewModel.snoozeReminder(reminder, scheduledAt)
            },
        )
    }
    confirmationToReject?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { confirmationToReject = null },
            title = { Text("拒绝这项待确认操作？") },
            text = {
                Text(
                    "${confirmation.actionLabel}\n\n" +
                        "这会拒绝所选请求，不会执行其操作。原始请求和预览内容不会显示。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmationToReject = null
                    viewModel.rejectConfirmation(confirmation)
                }) {
                    Text("拒绝操作", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationToReject = null }) { Text("取消") }
            },
        )
    }
    habitToArchive?.let { habit ->
        AlertDialog(
            onDismissRequest = { habitToArchive = null },
            title = { Text("归档习惯？") },
            text = {
                Text("将请求服务端归档「${habit.title}」。归档结果以服务端响应为准。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        habitToArchive = null
                        viewModel.archiveHabit(habit)
                    },
                    enabled = state.busyAction == null,
                ) {
                    Text("归档")
                }
            },
            dismissButton = {
                TextButton(onClick = { habitToArchive = null }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun NativeWorkbenchTodaySection(
    modifier: Modifier = Modifier,
    viewModel: NativeWorkbenchViewModel,
    showOperationStatus: Boolean = true,
    onOpenSection: (NativeWorkbenchSection) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var taskEditor by remember { mutableStateOf<TaskEditorTarget?>(null) }
    var eventEditor by remember { mutableStateOf<EventEditorTarget?>(null) }
    var taskSavePending by remember { mutableStateOf(false) }
    var eventSavePending by remember { mutableStateOf(false) }

    LaunchedEffect(state.feedback, taskSavePending) {
        if (taskSavePending && state.feedback in setOf("任务已创建", "任务已保存")) {
            taskEditor = null
            taskSavePending = false
        }
    }
    LaunchedEffect(state.feedback, eventSavePending) {
        if (eventSavePending && state.feedback in setOf("日程已创建", "日程已保存")) {
            eventEditor = null
            eventSavePending = false
        }
    }

    Column(modifier.fillMaxSize()) {
        if (showOperationStatus) {
            if (state.loading) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在读取服务端…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.busyAction?.let {
                Text(
                    "$it…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.error?.let { MessageBanner("操作未完成：$it", isError = true) }
            state.feedback?.let { MessageBanner(it, isError = false) }
        }

        TodayNativeContent(
            state = state,
            modifier = Modifier.weight(1f).testTag("today-native-business-content"),
            onNewTask = { taskEditor = TaskEditorTarget() },
            onEditTask = { taskEditor = TaskEditorTarget(it) },
            onToggleTask = viewModel::toggleTaskCompleted,
            onNewEvent = { eventEditor = EventEditorTarget() },
            onOpenSection = { section ->
                viewModel.selectSection(section)
                onOpenSection(section)
            },
        )
    }

    taskEditor?.let { target ->
        TaskEditorDialog(
            task = target.task,
            taskBooks = state.taskBooks,
            isSaving = state.busyAction != null,
            operationError = if (taskSavePending) state.error else null,
            onDismiss = { taskEditor = null; taskSavePending = false },
            onSave = { draft ->
                taskSavePending = true
                if (target.task == null) viewModel.createTask(draft)
                else viewModel.updateTask(target.task, draft)
            }
        )
    }
    eventEditor?.let { target ->
        EventEditorDialog(
            calendars = state.calendars,
            event = target.event,
            isSaving = state.busyAction != null,
            operationError = if (eventSavePending) state.error else null,
            onDismiss = { eventEditor = null; eventSavePending = false },
            onSave = { draft ->
                eventSavePending = true
                if (target.event == null) viewModel.createEvent(draft)
                else viewModel.updateEvent(target.event, draft)
            }
        )
    }
}

@Composable
private fun WorkbenchHeader(
    section: NativeWorkbenchSection,
    busy: String?,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("原生工作台", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${section.label} · 真实契约操作",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRefresh, enabled = busy == null) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun SectionSelector(
    selected: NativeWorkbenchSection,
    enabled: Boolean,
    onSelected: (NativeWorkbenchSection) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(NativeWorkbenchSection.entries, key = { it.name }) { section ->
            FilterChip(
                selected = section == selected,
                onClick = { onSelected(section) },
                enabled = enabled,
                label = { Text(section.label) },
                modifier = Modifier.testTag("native-workbench-${section.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun MessageBanner(text: String, isError: Boolean) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
internal fun TodayNativeContent(
    state: NativeWorkbenchUiState,
    modifier: Modifier = Modifier,
    onNewTask: () -> Unit,
    onEditTask: (TaskResponse) -> Unit,
    onToggleTask: (TaskResponse) -> Unit,
    onNewEvent: () -> Unit,
    onOpenSection: (NativeWorkbenchSection) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewTask) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新任务")
                }
                Button(onClick = onNewEvent, enabled = state.calendars.isNotEmpty()) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新日程")
                }
            }
        }
        if (state.calendars.isEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("尚无可写日历", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onOpenSection(NativeWorkbenchSection.Calendar) }) {
                        Text("前往创建日历")
                    }
                }
            }
        }
        item {
            SummaryCard(
                "今日焦点",
                "今日任务 ${state.tasks.count { it.status != "COMPLETED" }} · 今日已有日程 " +
                    eventCountOnDate(
                        state.events,
                        LocalDate.now(),
                        ZoneId.systemDefault(),
                    )
            )
        }
        state.mobileSummary?.let { summary ->
            item {
                SummaryCard(
                    "今日设备使用",
                    "前台 ${formatDuration(summary.totalForegroundSeconds)} · " +
                        "${summary.appsUsed} 个应用 · ${summary.appSwitchCount} 次切换 · " +
                        "完整度 ${(summary.completeness * 100).toInt()}% · " +
                        "质量异常 ${summary.qualityIssueCount}"
                )
            }
        }
        state.mobileSummaryError?.let { error ->
            item { SummaryCard("今日设备使用暂不可用", error) }
        }
        item { SectionTitle("今日任务", "${state.tasks.size} 项") }
        if (state.tasks.isEmpty()) {
            item { EmptyCard("暂无任务") }
        } else {
            items(state.tasks.take(6), key = { "today-task-${it.id}" }) { task ->
                CompactTaskRow(task, onEdit = { onEditTask(task) }, onToggle = { onToggleTask(task) })
            }
        }
        item {
            TextButton(onClick = { onOpenSection(NativeWorkbenchSection.Tasks) }) {
                Text("查看全部任务")
            }
        }
        item { SectionTitle("未来 7 天日程", "${state.eventTotal} 项") }
        if (state.events.isEmpty()) {
            item { EmptyCard("暂无日程") }
        } else {
            items(state.events.take(6), key = { "today-event-${it.id}" }) { event ->
                CompactEventRow(event)
            }
        }
        item {
            TextButton(onClick = { onOpenSection(NativeWorkbenchSection.Calendar) }) {
                Text("查看全部日程")
            }
        }
    }
}

@Composable
private fun TasksNativeContent(
    state: NativeWorkbenchUiState,
    viewModel: NativeWorkbenchViewModel,
    onNewTask: () -> Unit,
    onNewTaskBook: () -> Unit,
    onEditTask: (TaskResponse) -> Unit,
    onDeleteTask: (TaskResponse) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setSearch,
                modifier = Modifier.weight(1f).testTag("native-task-search"),
                singleLine = true,
                label = { Text("搜索任务") },
                trailingIcon = {
                    IconButton(onClick = viewModel::refreshTasks) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                }
            )
            TextButton(onClick = onNewTaskBook) { Text("任务本") }
            Button(onClick = onNewTask) { Text("新建") }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "共 ${state.taskTotal} 项，本轮最多读取 100 项 · 任务本 ${state.taskBooks.size} 个",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.taskBooksError?.let { error ->
                item {
                    Text(
                        "任务本读取失败：$error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (state.tasks.isEmpty()) {
                item { EmptyCard("没有匹配的任务") }
            }
            items(state.tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    busy = state.busyAction != null,
                    onEdit = { onEditTask(task) },
                    onToggle = { viewModel.toggleTaskCompleted(task) },
                    onStart = { viewModel.startTaskTimer(task) },
                    onPause = { viewModel.pauseTaskTimer(task) },
                    onStop = { viewModel.stopTaskTimer(task) },
                    onDelete = { onDeleteTask(task) },
                )
            }
        }
    }
}

@Composable
private fun CalendarNativeContent(
    state: NativeWorkbenchUiState,
    onNewEvent: () -> Unit,
    onCreateCalendar: () -> Unit,
    onEditEvent: (EventResponse) -> Unit,
    onDeleteEvent: (EventResponse) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("未来 7 天 · ${state.eventTotal} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCreateCalendar) {
                    Text("新建日历")
                }
                Button(onClick = onNewEvent, enabled = state.calendars.isNotEmpty()) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建日程")
                }
            }
            if (state.calendars.isEmpty()) {
                Text(
                    "没有可写日历，请先用上方「新建日历」创建。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (state.events.isEmpty()) item { EmptyCard("未来 7 天暂无日程") }
        items(state.events, key = { it.id }) { event ->
            EventCard(
                event = event,
                onEdit = { onEditEvent(event) },
                onDelete = { onDeleteEvent(event) },
            )
        }
    }
}

@Composable
private fun DevicesNativeContent(
    state: NativeWorkbenchUiState,
    onSort: (String) -> Unit,
    onRename: (DeviceListDto) -> Unit,
    onDelete: (DeviceListDto) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.deviceSort == "active",
                    onClick = { onSort("active") },
                    label = { Text("按活跃排序") }
                )
                FilterChip(
                    selected = state.deviceSort == "data",
                    onClick = { onSort("data") },
                    label = { Text("按数据量排序") }
                )
            }
        }
        if (state.devices.isEmpty()) item { EmptyCard("没有已注册设备") }
        items(state.devices, key = { it.deviceId }) { device ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.displayName,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text(if (device.isOnline) "在线" else "离线") }
                        )
                    }
                    Text(
                        "${device.brand} ${device.model} · Android ${device.osVersion} · App ${device.appVersion}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "会话 ${device.sessionCount} · 事件 ${device.eventCount} · 位置 ${device.locationCount} · " +
                            "存储 ${device.storageEstimateKb} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "同步 ${device.syncStatus} · 数据质量 ${device.dataQuality} · 存储压力 ${device.storagePressure}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onRename(device) }) {
                            Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("重命名")
                        }
                        TextButton(onClick = { onDelete(device) }) {
                            Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除（先看影响）", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemindersNativeContent(
    state: NativeWorkbenchUiState,
    onDismiss: (NativeReminderAction) -> Unit,
    onSnooze: (NativeReminderAction) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("服务端提醒", "${state.reminders.size} 条") }
        if (state.reminders.isEmpty()) item { EmptyCard("暂无提醒") }
        items(state.reminders, key = { it.id }) { reminder ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium)
                    Text(reminder.body, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${reminder.status} · ${reminder.riskLevel} · ${reminder.scheduledAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "关联 ${reminder.relatedObjectType} · ${reminder.relatedObjectId}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onSnooze(reminder) }) { Text("延后") }
                        TextButton(onClick = { onDismiss(reminder) }) {
                            Text("忽略", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationsNativeContent(
    state: NativeWorkbenchUiState,
    onOpen: (String) -> Unit,
    onReject: (NativeOperationConfirmation) -> Unit,
    onCloseDetail: () -> Unit,
) {
    val detailId = state.selectedConfirmationId
    val detail = state.confirmationDetail
    if (detailId != null) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TextButton(onClick = onCloseDetail) { Text("返回待确认列表") }
            }
            when {
                state.loadingConfirmationDetail -> item {
                    Text("正在读取安全摘要…", style = MaterialTheme.typography.bodyMedium)
                }
                detail == null -> item { EmptyCard("未能读取安全摘要") }
                else -> item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(detail.actionLabel, style = MaterialTheme.typography.titleMedium)
                            Text("状态：${confirmationStatusLabel(detail.status)}")
                            Text("风险：${confirmationRiskLabel(detail.riskLevel)}")
                            Text("创建：${detail.createdAt}", style = MaterialTheme.typography.bodySmall)
                            Text("到期：${detail.expiresAt}", style = MaterialTheme.typography.bodySmall)
                            if (detail.requiresSecondLevelConfirmation || detail.requiresStrictConfirmation) {
                                Text("该请求具有额外确认要求；原生页不会绕过这些要求。")
                            }
                            Divider()
                            Text(
                                "原始 payload、preview、外部标识和个人数据不会显示。" +
                                    "当前原生页仅支持查看安全摘要和拒绝，不执行确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionTitle("待确认操作", "${state.confirmations.size} 条")
        }
        if (state.confirmations.isEmpty()) item { EmptyCard("暂无待确认操作") }
        items(state.confirmations, key = { it.id }) { confirmation ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(confirmation.actionLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${confirmationRiskLabel(confirmation.riskLevel)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${confirmationStatusLabel(confirmation.status)} · 到期 ${confirmation.expiresAt}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpen(confirmation.id) }) {
                            Text("查看安全摘要")
                        }
                        TextButton(onClick = { onReject(confirmation) }) {
                            Text("拒绝", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        item {
            Text(
                "为避免泄露私密负载或误执行，原始预览不会显示；确认执行暂由服务端完整页面处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HabitsNativeContent(
    state: NativeWorkbenchUiState,
    onArchive: (NativeHabit) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("习惯", "${state.habits.size} 条") }
        if (state.habits.isEmpty()) item { EmptyCard("暂无习惯") }
        items(state.habits, key = { it.id }) { habit ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(habit.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${habitCadenceLabel(habit.cadence)} · ${habit.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onArchive(habit) },
                        enabled = state.busyAction == null,
                    ) { Text("归档") }
                }
            }
        }
        item {
            Text(
                "原生页提供服务端列表与明确确认的归档；创建、编辑和打卡记录仍由服务端完整页面处理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KnowledgeNativeContent(
    state: NativeWorkbenchUiState,
    viewModel: NativeWorkbenchViewModel,
    onAddCategory: (String?) -> Unit,
    onEditCategory: (CategoryTreeNode) -> Unit,
    onDeleteCategory: (CategoryTreeNode) -> Unit,
    onAddContext: (AppKnowledgeAppDto) -> Unit,
) {
    val flatCategories = remember(state.categories) { flattenCategories(state.categories) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分类树 · ${flatCategories.size} 个", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onAddCategory(null) }) { Text("添加根分类") }
            }
        }
        if (flatCategories.isEmpty()) item { EmptyCard("还没有分类") }
        items(flatCategories, key = { it.node.id }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = (12 + item.depth * 14).dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.node.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${item.node.productivity} · ${item.node.color}" +
                                if (item.node.isBuiltin) " · 内置" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onAddCategory(item.node.id) }) { Text("子分类") }
                    IconButton(onClick = { onEditCategory(item.node) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑分类")
                    }
                    IconButton(
                        onClick = { onDeleteCategory(item.node) },
                        enabled = !item.node.isBuiltin
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除分类")
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = viewModel::setSearch,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索进程名/显示名") }
                )
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索应用")
                }
            }
        }
        item { SectionTitle("应用知识", "${state.apps.size} 个") }
        state.selectedApp?.let { app ->
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${app.displayName} 的上下文", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = viewModel::closeKnowledgeApp) { Text("关闭") }
                        }
                        Text(app.processName, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { onAddContext(app) }) { Text("添加上下文") }
                    }
                }
            }
            if (state.appContexts.isEmpty()) item { EmptyCard("暂无上下文") }
            items(state.appContexts, key = { "context-${it.id}" }) { context ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${context.patternType}: ${context.patternValue}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "→ ${context.targetCategoryName ?: "未指定分类"} · " +
                                    "${if (context.enabled) "启用" else "停用"} · 影响 ${context.affectedRecordCount} 条",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { viewModel.deleteAppContext(context) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除上下文")
                        }
                    }
                }
            }
        }
        items(state.apps, key = { it.id }) { app ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(app.processName, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "分类 ${app.categoryPath ?: "未标注"} · 上下文 ${app.contextCount}" +
                                if (app.pendingContextCount > 0) " · 待确认 ${app.pendingContextCount}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.selectKnowledgeApp(app) }) { Text("上下文") }
                }
            }
        }
        item {
            Text(
                "应用签名的创建与修改没有出现在当前客户端契约中，因此本页只提供真实的上下文规则管理与分类动作，不伪造 app-signature 接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticsNativeContent(
    state: NativeWorkbenchUiState,
    onOpenStatusCenter: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onOpenStatusCenter) { Text("打开本机状态与诊断导出") }
                TextButton(onClick = onOpenSettings) { Text("打开本机设置") }
            }
        }
        val status = state.systemStatus
        if (status == null) {
            item { EmptyCard("尚未读取服务端诊断") }
        } else {
            item {
                SummaryCard(
                    status.summary.label,
                    "${status.summary.message} · 状态码 ${status.summary.status} · ${status.summary.checkedAt}"
                )
            }
            items(status.components, key = { it.key }) { component ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(component.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text("状态 ${component.status}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(component.message, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "kind=${component.kind} · checked=${component.checkedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        component.details.forEach { (key, value) ->
                            Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (status.nextSteps.isNotEmpty()) {
                item { SectionTitle("建议下一步", "${status.nextSteps.size} 项") }
                items(status.nextSteps) { step ->
                    Text("• $step", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun QuickNotesNativeContent(
    state: NativeWorkbenchUiState,
    viewModel: NativeWorkbenchViewModel,
    onNew: () -> Unit,
    onOpen: (QuickNoteListItemDto) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::setSearch,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("搜索旧快速记录") }
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Filled.Search, contentDescription = "搜索快速记录")
            }
            Button(onClick = onNew) { Text("新建") }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("共 ${state.quickNoteTotal} 条 · 兼容旧 HTML/附件的身份与记录", style = MaterialTheme.typography.bodySmall) }
            if (state.quickNotes.isEmpty()) item { EmptyCard("暂无快速记录") }
            items(state.quickNotes, key = { it.id }) { note ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text(note.status) })
                            Spacer(Modifier.width(8.dp))
                            Text("${note.attachmentCount} 个附件", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            Text(note.updatedAt.take(16), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            note.contentPreview.ifBlank { "（无文本预览）" },
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onOpen(note) }) { Text("查看与处理") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTaskRow(task: TaskResponse, onEdit: () -> Unit, onToggle: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${task.status} · P${task.priority}" + (task.due?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onToggle) {
                Icon(Icons.Filled.Check, contentDescription = "切换完成状态")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑任务")
            }
        }
    }
}

@Composable
private fun CompactEventRow(event: EventResponse) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(event.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatEventRange(event), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskResponse,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                AssistChip(onClick = {}, label = { Text(task.status) })
            }
            val descriptionText = task.description
            if (!descriptionText.isNullOrBlank()) {
                Text(
                    descriptionText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "优先级 ${task.priority} · 预计 ${task.estimatedDuration ?: "未设"} · " +
                    "截止 ${task.due ?: "未设"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("编辑") }
                TextButton(onClick = onToggle, enabled = !busy) {
                    Text(if (task.status == "COMPLETED") "重新打开" else "完成")
                }
                IconButton(onClick = onStart, enabled = !busy) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "开始计时")
                }
                IconButton(onClick = onPause, enabled = !busy) {
                    Icon(Icons.Filled.Pause, contentDescription = "暂停计时")
                }
                IconButton(onClick = onStop, enabled = !busy) {
                    Icon(Icons.Filled.Stop, contentDescription = "结束计时")
                }
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    event: EventResponse,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val blocked = event.isOutlookMirror() || event.hasRecurrenceMetadata()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium)
            Text(formatEventRange(event), style = MaterialTheme.typography.bodySmall)
            if (!event.location.isNullOrBlank()) {
                Text("地点 ${event.location}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "来源 ${event.source}" + if (event.isSeriesMaster == true) " · 系列主记录" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocked) {
                    Text(
                        if (event.isOutlookMirror()) {
                            "Outlook 镜像需通过同步写回"
                        } else {
                            "重复日程需确认编辑范围，请暂用日历网页"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, meta: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TaskEditorDialog(
    task: TaskResponse?,
    taskBooks: List<TaskBookDto>,
    isSaving: Boolean = false,
    operationError: String? = null,
    onDismiss: () -> Unit,
    onSave: (NativeTaskDraft) -> Unit,
) {
    var title by remember(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var description by remember(task?.id) { mutableStateOf(task?.description.orEmpty()) }
    var priority by remember(task?.id) { mutableStateOf((task?.priority ?: 3).toString()) }
    var due by remember(task?.id) { mutableStateOf(toDateTimeInput(task?.due)) }
    var start by remember(task?.id) { mutableStateOf(toDateTimeInput(task?.dtStart)) }
    var taskBookId by remember(task?.id) { mutableStateOf(task?.taskBookId) }
    var validation by remember(task?.id) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(task?.id) { mutableStateOf(false) }
    val dirty = taskEditorHasChanges(task, title, description, priority, due, start, taskBookId)
    val requestDismiss = {
        if (!isSaving) {
            if (dirty) confirmDiscard = true else onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (task == null) "新建任务" else "编辑任务") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, enabled = !isSaving)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("描述") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !isSaving,
                )
                OutlinedTextField(
                    priority,
                    { priority = it.filter(Char::isDigit).take(2) },
                    label = { Text("优先级（1-5）") },
                    singleLine = true,
                    enabled = !isSaving,
                )
                DateTimeField("截止时间（可空，YYYY-MM-DDTHH:MM）", due, enabled = !isSaving) { due = it }
                DateTimeField("开始时间（可空）", start, enabled = !isSaving) { start = it }
                if (taskBooks.isNotEmpty()) {
                    Text("任务本", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(taskBooks, key = { it.id }) { book ->
                            FilterChip(
                                selected = taskBookId == book.id,
                                onClick = { if (!isSaving) taskBookId = book.id },
                                enabled = !isSaving,
                                label = { Text(book.name) }
                            )
                        }
                    }
                }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                operationError?.let { Text("保存失败：$it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !isSaving, onClick = {
                val parsedPriority = priority.toIntOrNull()
                val parsedDue = normalizeDateTimeInput(due)
                val parsedStart = normalizeDateTimeInput(start)
                validation = when {
                    title.isBlank() -> "标题不能为空"
                    parsedPriority == null || parsedPriority !in 1..5 -> "优先级需为 1-5"
                    due.isNotBlank() && parsedDue == null -> "截止时间格式无效"
                    start.isNotBlank() && parsedStart == null -> "开始时间格式无效"
                    else -> null
                }
                if (validation == null) {
                    onSave(
                        NativeTaskDraft(
                            title = title.trim(),
                            description = description.trim(),
                            priority = parsedPriority ?: 3,
                            due = parsedDue,
                            dtStart = parsedStart,
                            taskBookId = taskBookId,
                        )
                    )
                }
            }) { Text(if (isSaving) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(onClick = requestDismiss, enabled = !isSaving) { Text("取消") } }
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的任务修改？") },
            text = { Text("当前编辑内容尚未保存。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
}

internal fun taskEditorHasChanges(
    task: TaskResponse?,
    title: String,
    description: String,
    priority: String,
    due: String,
    start: String,
    taskBookId: String?,
): Boolean = title != task?.title.orEmpty() ||
    description != task?.description.orEmpty() ||
    priority != (task?.priority ?: 3).toString() ||
    due != toDateTimeInput(task?.due) ||
    start != toDateTimeInput(task?.dtStart) ||
    taskBookId != task?.taskBookId

@Composable
private fun EventEditorDialog(
    calendars: List<com.pim.core.contract.CalendarResponse>,
    event: EventResponse?,
    isSaving: Boolean = false,
    operationError: String? = null,
    onDismiss: () -> Unit,
    onSave: (NativeEventDraft) -> Unit,
) {
    val defaultStart = remember {
        LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }
    val initialCalendarId = remember(event?.id, calendars.firstOrNull()?.id) {
        event?.calendarId ?: calendars.firstOrNull()?.id.orEmpty()
    }
    var calendarId by remember(event?.id, calendars.firstOrNull()?.id) {
        mutableStateOf(initialCalendarId)
    }
    var title by remember(event?.id) { mutableStateOf(event?.title.orEmpty()) }
    var description by remember(event?.id) { mutableStateOf(event?.description.orEmpty()) }
    val defaultEnd = remember {
        LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }
    val initialStart = remember(event?.id) { toDateTimeInput(event?.dtStart) ?: defaultStart }
    val initialEnd = remember(event?.id) { toDateTimeInput(event?.dtEnd) ?: defaultEnd }
    var start by remember(event?.id) { mutableStateOf(initialStart) }
    var end by remember(event?.id) { mutableStateOf(initialEnd) }
    var location by remember(event?.id) { mutableStateOf(event?.location.orEmpty()) }
    var validation by remember(event?.id) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(event?.id) { mutableStateOf(false) }
    val dirty = eventEditorHasChanges(
        event, calendarId, title, description, start, end, location,
        initialCalendarId, initialStart, initialEnd,
    )
    val requestDismiss = {
        if (!isSaving) {
            if (dirty) confirmDiscard = true else onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (event == null) "新建日程" else "编辑日程") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "日历：${calendars.firstOrNull { it.id == calendarId }?.name ?: calendarId.ifBlank { "未选择" }}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (event == null && calendars.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(calendars, key = { it.id }) { calendar ->
                            FilterChip(
                                selected = calendar.id == calendarId,
                                onClick = { if (!isSaving) calendarId = calendar.id },
                                enabled = !isSaving,
                                label = { Text(calendar.name) }
                            )
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, enabled = !isSaving)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("描述") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !isSaving,
                )
                DateTimeField("开始（YYYY-MM-DDTHH:MM）", start, enabled = !isSaving) { start = it }
                DateTimeField("结束（YYYY-MM-DDTHH:MM）", end, enabled = !isSaving) { end = it }
                OutlinedTextField(location, { location = it }, label = { Text("地点") }, singleLine = true, enabled = !isSaving)
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                operationError?.let { Text("保存失败：$it", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !isSaving, onClick = {
                val startIso = normalizeDateTimeInput(start)
                val endIso = normalizeDateTimeInput(end)
                validation = when {
                    calendarId.isBlank() -> "请选择日历"
                    title.isBlank() -> "标题不能为空"
                    startIso == null -> "开始时间格式无效"
                    endIso == null -> "结束时间格式无效"
                    endIso <= startIso -> "结束时间必须晚于开始时间"
                    else -> null
                }
                if (validation == null) {
                    onSave(
                        NativeEventDraft(
                            calendarId = calendarId,
                            title = title.trim(),
                            description = description.trim(),
                            dtStart = startIso!!,
                            dtEnd = endIso!!,
                            location = location.trim(),
                        )
                    )
                }
            }) { Text(if (isSaving) "正在保存…" else "保存") }
        },
        dismissButton = { TextButton(onClick = requestDismiss, enabled = !isSaving) { Text("取消") } }
    )
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的日程修改？") },
            text = { Text("当前编辑内容尚未保存。") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
}

internal fun eventEditorHasChanges(
    event: EventResponse?,
    calendarId: String,
    title: String,
    description: String,
    start: String,
    end: String,
    location: String,
    initialCalendarId: String,
    initialStart: String,
    initialEnd: String,
): Boolean =
    calendarId != (event?.calendarId ?: initialCalendarId) ||
        title != event?.title.orEmpty() ||
        description != event?.description.orEmpty() ||
        start != (event?.let { toDateTimeInput(it.dtStart) } ?: initialStart) ||
        end != (event?.let { toDateTimeInput(it.dtEnd) } ?: initialEnd) ||
        location != event?.location.orEmpty()

@Composable
private fun DeviceRenameDialog(
    device: DeviceListDto,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(device.deviceId) { mutableStateOf(device.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名设备") },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text("显示名称") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onSave(value.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NameAndColorDialog(
    title: String,
    nameLabel: String,
    defaultColor: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember(defaultColor) { mutableStateOf(defaultColor.orEmpty()) }
    val colorValid = defaultColor == null || Regex("^#[0-9A-Fa-f]{6}$").matches(color)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(nameLabel) }, singleLine = true)
                if (defaultColor != null) {
                    OutlinedTextField(
                        color,
                        { color = it },
                        label = { Text("颜色（#RRGGBB）") },
                        singleLine = true,
                        isError = !colorValid
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), color) },
                enabled = name.isNotBlank() && colorValid
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CategoryEditorDialog(
    category: CategoryTreeNode?,
    initialParentId: String?,
    onDismiss: () -> Unit,
    onSave: (String, String?, String, String?, String, Int) -> Unit,
) {
    var name by remember(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var parentId by remember(category?.id, initialParentId) {
        mutableStateOf(category?.parentId ?: initialParentId.orEmpty())
    }
    var color by remember(category?.id) { mutableStateOf(category?.color ?: "#2563EB") }
    var icon by remember(category?.id) { mutableStateOf(category?.icon.orEmpty()) }
    var productivity by remember(category?.id) { mutableStateOf(category?.productivity ?: "neutral") }
    var sortOrder by remember(category?.id) { mutableStateOf((category?.sortOrder ?: 0).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category == null) "添加分类" else "编辑分类") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(parentId, { parentId = it }, label = { Text("父分类 ID（可空）") }, singleLine = true)
                OutlinedTextField(color, { color = it }, label = { Text("颜色（#RRGGBB）") }, singleLine = true)
                OutlinedTextField(icon, { icon = it }, label = { Text("图标（可空）") }, singleLine = true)
                Text("生产力属性", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("productive", "neutral", "distracting").forEach { value ->
                        FilterChip(
                            selected = productivity == value,
                            onClick = { productivity = value },
                            label = { Text(value) }
                        )
                    }
                }
                OutlinedTextField(
                    sortOrder,
                    { sortOrder = it.filter(Char::isDigit).take(5) },
                    label = { Text("排序值") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && Regex("^#[0-9A-Fa-f]{6}$").matches(color)) {
                    onSave(
                        name.trim(),
                        parentId.trim().takeIf { it.isNotEmpty() },
                        color,
                        icon.trim().takeIf { it.isNotEmpty() },
                        productivity,
                        sortOrder.toIntOrNull() ?: 0,
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AppContextEditorDialog(
    app: AppKnowledgeAppDto,
    categories: List<CategoryTreeNode>,
    onDismiss: () -> Unit,
    onSave: (SaveAppKnowledgeContextRequest) -> Unit,
) {
    var patternType by remember(app.id) { mutableStateOf("process") }
    var patternValue by remember(app.id) { mutableStateOf(app.processName) }
    var processName by remember(app.id) { mutableStateOf(app.processName) }
    var projectTag by remember(app.id) { mutableStateOf("") }
    var targetCategory by remember(app.id) {
        mutableStateOf(app.categoryPath.orEmpty().substringAfterLast('/'))
    }
    var confidence by remember(app.id) { mutableStateOf("0.8") }
    var enabled by remember(app.id) { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为 ${app.displayName} 添加上下文") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(patternType, { patternType = it }, label = { Text("规则类型") }, singleLine = true)
                OutlinedTextField(patternValue, { patternValue = it }, label = { Text("规则值") }, singleLine = true)
                OutlinedTextField(processName, { processName = it }, label = { Text("进程名") }, singleLine = true)
                OutlinedTextField(projectTag, { projectTag = it }, label = { Text("项目标签（可空）") }, singleLine = true)
                OutlinedTextField(
                    targetCategory,
                    { targetCategory = it },
                    label = { Text("目标分类名称") },
                    singleLine = true
                )
                if (categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(flattenCategories(categories), key = { it.node.id }) { node ->
                            FilterChip(
                                selected = node.node.name == targetCategory,
                                onClick = { targetCategory = node.node.name },
                                label = { Text(node.node.name) }
                            )
                        }
                    }
                }
                OutlinedTextField(confidence, { confidence = it }, label = { Text("置信度 0-1") }, singleLine = true)
                FilterChip(
                    selected = enabled,
                    onClick = { enabled = !enabled },
                    label = { Text(if (enabled) "启用" else "停用") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val confidenceValue = confidence.toDoubleOrNull()
                if (patternType.isNotBlank() && patternValue.isNotBlank() &&
                    processName.isNotBlank() && confidenceValue != null &&
                    confidenceValue in 0.0..1.0
                ) {
                    onSave(
                        SaveAppKnowledgeContextRequest(
                            appId = app.id,
                            confidence = confidenceValue,
                            enabled = enabled,
                            patternType = patternType.trim(),
                            patternValue = patternValue.trim(),
                            processName = processName.trim(),
                            projectTag = projectTag.trim().takeIf { it.isNotEmpty() },
                            targetCategoryName = targetCategory.trim().takeIf { it.isNotEmpty() },
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun QuickNoteCreateDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建快速记录") },
        text = {
            OutlinedTextField(
                content,
                { content = it },
                label = { Text("Markdown / 原始文本") },
                minLines = 5,
                maxLines = 10
            )
        },
        confirmButton = {
            TextButton(onClick = { if (content.isNotBlank()) onSave(content) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun QuickNoteEditorDialog(
    detail: QuickNoteDetailDto,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onArchive: () -> Unit,
    onProcess: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val legacyHtml = detail.isLegacyQuickNoteHtml()
    var draft by remember(detail.id) {
        mutableStateOf(QuickNoteDraft(detail.contentMarkdown, detail.contentMarkdown))
    }
    LaunchedEffect(detail.id, detail.updatedAt) {
        draft = draft.onServerDetailChanged(detail.contentMarkdown)
    }
    var confirmDelete by remember(detail.id) { mutableStateOf(false) }
    var confirmDiscard by remember(detail.id) { mutableStateOf(false) }
    val requestClose = {
        if (draft.isDirty) confirmDiscard = true else onDismiss()
    }
    AlertDialog(
        onDismissRequest = requestClose,
        title = { Text("快速记录 · ${detail.status}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "原始记录 ${detail.createdAt} · 来源 ${detail.source} · " +
                        "${detail.attachments.size} 个附件",
                    style = MaterialTheme.typography.bodySmall
                )
                if (legacyHtml) {
                    Text(
                        "旧 HTML 记录正文只读：更新契约只有 contentMarkdown，保存纯文本会覆盖原 HTML。" +
                            "处理、归档、恢复和删除不会改写正文。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (detail.metadataJson.isNotBlank()) {
                    Text(
                        "扩展 metadata 由服务端维护，原生正文编辑不会改写。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (detail.attachments.isNotEmpty()) {
                    Text(
                        "附件：${detail.attachments.joinToString { it.fileName }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    draft.content,
                    { if (!legacyHtml) draft = draft.edit(it) },
                    label = {
                        Text(
                            if (legacyHtml) "旧 HTML 正文（只读）"
                            else "正文（保存时保留现有附件 ID）"
                        )
                    },
                    readOnly = legacyHtml,
                    minLines = 6,
                    maxLines = 14
                )
                if (draft.isDirty) {
                    Text(
                        "有未保存修改；刷新或关闭不会静默丢弃。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TextButton(onClick = onProcess) { Text("处理") }
                    TextButton(onClick = onArchive) { Text("归档") }
                    if (detail.status != "inbox") {
                        TextButton(onClick = onRestore) { Text("恢复") }
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (confirmDelete) {
                    Text("再次确认：服务端记录会删除，已存在附件是否保留由服务端策略决定。")
                    Button(onClick = onDelete) {
                        Text("确认删除快速记录")
                    }
                }
                if (confirmDiscard) {
                    Text("正文有未保存修改。确认放弃后才会关闭。")
                    Button(onClick = onDismiss) { Text("放弃更改并关闭") }
                }
            }
        },
        confirmButton = {
            if (!legacyHtml) {
                TextButton(
                    onClick = { if (draft.content.isNotBlank()) onSave(draft.content) },
                    enabled = draft.isDirty && draft.content.isNotBlank(),
                ) { Text("保存正文") }
            } else {
                TextButton(onClick = { requestClose() }) { Text("关闭") }
            }
        },
        dismissButton = {
            if (!legacyHtml) {
                TextButton(onClick = { requestClose() }) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun ReminderSnoozeDialog(
    reminder: NativeReminderAction,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var minutes by remember(reminder.id) { mutableStateOf("10") }
    val parsed = minutes.toIntOrNull()
    val valid = parsed != null && parsed in 1..10_080
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("延后提醒") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reminder.title, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 30, 60).forEach { preset ->
                        FilterChip(
                            selected = minutes == preset.toString(),
                            onClick = { minutes = preset.toString() },
                            label = { Text("$preset 分钟") },
                        )
                    }
                }
                OutlinedTextField(
                    minutes,
                    { minutes = it.filter(Char::isDigit).take(5) },
                    label = { Text("自定义分钟数（1-10080）") },
                    singleLine = true,
                    isError = !valid,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(Instant.now().plusSeconds((parsed ?: 0).toLong() * 60L).toString())
                },
                enabled = valid,
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DateTimeField(
    label: String,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
    )
}

private data class FlatCategory(val node: CategoryTreeNode, val depth: Int)

private fun flattenCategories(
    nodes: List<CategoryTreeNode>,
    depth: Int = 0,
    out: MutableList<FlatCategory> = mutableListOf(),
): List<FlatCategory> {
    nodes.forEach { node ->
        out += FlatCategory(node, depth)
        flattenCategories(node.children, depth + 1, out)
    }
    return out
}

private fun formatEventRange(event: EventResponse): String {
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
        .withZone(ZoneId.systemDefault())
    val start = runCatching { Instant.parse(event.dtStart) }.getOrNull()
    val end = runCatching { Instant.parse(event.dtEnd) }.getOrNull()
    return when {
        start != null && end != null -> "${formatter.format(start)} – ${formatter.format(end)}"
        else -> "${event.dtStart} – ${event.dtEnd}"
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分钟"
}

internal fun eventCountOnDate(
    events: List<EventResponse>,
    date: LocalDate,
    zoneId: ZoneId,
): Int = events.count { event ->
    runCatching { Instant.parse(event.dtStart).atZone(zoneId).toLocalDate() == date }
        .getOrDefault(false)
}

internal fun normalizeDateTimeInput(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching {
        Instant.parse(value).toString()
    }.recoverCatching {
        LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toString()
    }.getOrNull()
}

private fun toDateTimeInput(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching {
        LocalDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }.getOrElse { raw }
}

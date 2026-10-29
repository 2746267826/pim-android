package com.pim.app.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.AiAsrDestinationConsent
import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrSourceDto

data class NoteAsrActions(
    val refresh: () -> Unit = {},
    val refreshTask: (String) -> Unit = {},
    val loadSegments: (String) -> Unit = {},
    val stop: (String) -> Unit = {},
    val retry: (String, List<Int>) -> Unit = { _, _ -> },
    val start: (String, NoteAsrDestinationDto, String) -> Unit = { _, _, _ -> },
    val replayStart: (String) -> Unit = {},
    val replayRetry: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteAsrSurface(
    state: NoteAsrRepositoryState,
    currentTarget: NoteUploadTarget?,
    initialVersionId: String?,
    onDismiss: () -> Unit,
    actions: NoteAsrActions,
) {
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var consentVersionId by rememberSaveable { mutableStateOf(initialVersionId) }
    val selected = state.tasks.firstOrNull { it.taskId == selectedTaskId }
    LaunchedEffect(initialVersionId) { consentVersionId = initialVersionId }
    BackHandler {
        if (selectedTaskId != null) selectedTaskId = null else onDismiss()
    }
    LaunchedEffect(currentTarget?.binding) { actions.refresh() }
    LaunchedEffect(selectedTaskId, selected?.task?.revision) {
        if (selected?.task != null && selected.isBoundTo(currentTarget)) actions.loadSegments(selected.taskId)
    }
    val canOfferStart = consentVersionId != null &&
        state.destinations.isNotEmpty() &&
        state.destinationBinding == currentTarget?.binding &&
        state.sourceBinding == currentTarget?.binding &&
        state.sources.any { it.versionId == consentVersionId } &&
        state.tasks.none { it.versionId == consentVersionId && it.task != null }
    Surface(Modifier.fillMaxSize().testTag("note-asr-screen"), color = MaterialTheme.colorScheme.background) {
        if (selected == null) {
            NoteAsrTaskList(
                state = state,
                currentTarget = currentTarget,
                initialVersionId = initialVersionId,
                onDismiss = onDismiss,
                onRefresh = actions.refresh,
                onOpen = { selectedTaskId = it },
                onRefreshTask = actions.refreshTask,
            )
        } else {
            NoteAsrTaskDetail(
                record = selected,
                state = state,
                currentTarget = currentTarget,
                onBack = { selectedTaskId = null },
                onRefreshTask = actions.refreshTask,
                onLoadSegments = actions.loadSegments,
                onStop = actions.stop,
                onRetry = { indexes -> actions.retry(selected.taskId, indexes) },
                onReplayStart = actions.replayStart,
                onReplayRetry = actions.replayRetry,
            )
        }
    }
    if (canOfferStart) {
        val versionId = checkNotNull(consentVersionId)
        NoteAsrConsentSheet(
            versionId = versionId,
            source = state.sources.firstOrNull { it.versionId == versionId },
            target = currentTarget,
            destinations = state.destinations,
            onDismiss = { consentVersionId = null },
            onConfirm = { destination, language ->
                consentVersionId = null
                actions.start(versionId, destination, language)
            },
        )
    }
}

@Composable
private fun NoteAsrTaskList(
    state: NoteAsrRepositoryState,
    currentTarget: NoteUploadTarget?,
    initialVersionId: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onRefreshTask: (String) -> Unit,
) {
    val d = PimTheme.dimensions
    val source = state.sources.firstOrNull { it.versionId == initialVersionId }
    val tasks = remember(state.tasks, initialVersionId) {
        state.tasks.sortedWith(compareByDescending<NoteAsrTaskRecord> { it.versionId == initialVersionId }
            .thenByDescending { it.createdAtMillis })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = d.spaceSmall, vertical = d.spaceSmall),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("note-asr-close")) {
                Icon(Icons.Default.ArrowBack, "返回笔记")
            }
            Column(Modifier.weight(1f)) {
                Text("逐段转写", style = MaterialTheme.typography.titleLarge)
                Text("固定材料版本 · 独立 ASR 授权", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !state.syncing,
                modifier = Modifier.testTag("note-asr-refresh")) {
                Icon(Icons.Default.Refresh, "刷新")
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("note-asr-tasks"),
            contentPadding = PaddingValues(horizontal = d.spaceLarge, vertical = d.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(d.spaceMedium),
        ) {
            if (state.syncing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                PimStatusBanner(
                    "上传同意只表示保存材料，不代表同意发送给模型。ASR 是独立的有费用、第三方发送操作。",
                    PimStatusTone.Info,
                    Modifier.testTag("note-asr-after-upload-notice"),
                )
            }
            if (!state.newTaskAvailable) item {
                PimStatusBanner(state.newTaskProblem, PimStatusTone.Warning,
                    Modifier.testTag("note-asr-destination-unavailable"))
            }
            state.sourceProblem?.let { problem ->
                item { PimStatusBanner(problem, PimStatusTone.Warning) }
            }
            state.message?.let { problem ->
                item { PimStatusBanner(problem, PimStatusTone.Warning) }
            }
            if (state.loadFailed) item {
                PimStatusBanner("部分本机转写任务无法读取；其余任务保留，损坏文件未被覆盖。", PimStatusTone.Error)
            }
            if (initialVersionId != null) item {
                FixedSourceCard(initialVersionId, source)
            }
            if (state.tasks.isEmpty() && !state.syncing) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                        Text("没有已保存的转写任务", style = MaterialTheme.typography.titleMedium)
                        Text("从材料版本选择“逐段转写”。选择目标并明确同意前，不会发送任何录音。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(tasks, key = { it.taskId }) { record ->
                TaskCard(record, currentTarget, onOpen)
                val status = record.statusView(currentTarget)
                if (status.status == NoteAsrStatus.VerificationPending && record.task == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                        TextButton(onClick = { onRefreshTask(record.taskId) },
                            modifier = Modifier.testTag("note-asr-refresh-task-${record.taskId}")) {
                            Text("核对同一 TaskId")
                        }
                        TextButton(onClick = { onOpen(record.taskId) },
                            modifier = Modifier.testTag("note-asr-replay-start-${record.taskId}")) {
                            Text("查看并核对")
                        }
                    }
                }
                if (record.pendingRetry?.phase == NoteAsrIntentPhase.VerificationPending) {
                    TextButton(onClick = { onOpen(record.taskId) },
                        modifier = Modifier.testTag("note-asr-replay-retry-${record.taskId}")) {
                        Text("查看并核对恢复")
                    }
                }
            }
            item {
                Text("逐段转写结果按服务器持久任务保存；切换账号或服务器后旧任务仍保留，但当前登录不能访问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FixedSourceCard(versionId: String, source: NoteAsrSourceDto?) {
    val d = PimTheme.dimensions
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-asr-source-$versionId")) {
        Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
                Text("固定材料版本", style = MaterialTheme.typography.titleMedium)
            }
            Text("versionId $versionId", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (source == null) {
                Text("该版本尚无法从服务器源目录读取；不会改用当前新稿或猜测录音数。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(source.title.ifBlank { "未命名记录" }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${source.recordings} 次录音 · ${asrDuration(source.totalSamples, source.sampleRate)} · " +
                    "${source.sampleRate} Hz", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        source.hasConflict -> "冲突版本 · 已固定，不会自动覆盖当前版本"
                        source.isCurrent -> "当前版本"
                        else -> "旧版本 · 仍与本篇当前编辑分离"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.hasConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(record: NoteAsrTaskRecord, currentTarget: NoteUploadTarget?, onOpen: (String) -> Unit) {
    val d = PimTheme.dimensions
    val status = record.statusView(currentTarget)
    val task = record.task
    val presentation = record.listPresentation(currentTarget)
    OutlinedCard(
        onClick = { onOpen(record.taskId) },
        modifier = Modifier.fillMaxWidth().testTag("note-asr-task-${record.taskId}"),
    ) {
        Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                Icon(statusIcon(status.status), null, tint = statusTone(status.status).let {
                    if (it == PimStatusTone.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                })
                Column(Modifier.weight(1f)) {
                    Text(presentation.title,
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(presentation.subtitle,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null)
            }
            TextButton(
                onClick = { onOpen(record.taskId) },
                modifier = Modifier.testTag("note-asr-task-open-${record.taskId}"),
            ) { Text("查看任务与逐段结果") }
            Text(status.label, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("note-asr-phase-${record.taskId}"))
            Text(status.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (presentation.showProgress && task != null) {
                val progress = if (task.totalSamples <= 0) 0f
                else (task.completedSamples.toDouble() / task.totalSamples).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text("成功片 ${task.completedSegments}/${task.totalSegments} · 成功样本 ${task.completedSamples}/${task.totalSamples}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("note-asr-progress-${record.taskId}"))
            }
        }
    }
}

@Composable
private fun NoteAsrTaskDetail(
    record: NoteAsrTaskRecord,
    state: NoteAsrRepositoryState,
    currentTarget: NoteUploadTarget?,
    onBack: () -> Unit,
    onRefreshTask: (String) -> Unit,
    onLoadSegments: (String) -> Unit,
    onStop: (String) -> Unit,
    onRetry: (List<Int>) -> Unit,
    onReplayStart: (String) -> Unit,
    onReplayRetry: (String) -> Unit,
) {
    val d = PimTheme.dimensions
    val status = record.statusView(currentTarget)
    val bound = record.isBoundTo(currentTarget)
    if (!bound) {
        UnboundTaskDetail(record.taskId, onBack)
        return
    }
    var selectingRetry by rememberSaveable(record.taskId) { mutableStateOf(false) }
    var selected by remember(record.task?.revision, record.taskId) { mutableStateOf<Set<Int>>(emptySet()) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    var confirmRetry by rememberSaveable { mutableStateOf(false) }
    var replayStart by rememberSaveable { mutableStateOf(false) }
    var replayRetry by rememberSaveable { mutableStateOf(false) }
    val task = record.task
    val groups = record.recordingGroups()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = d.spaceSmall, vertical = d.spaceSmall),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回任务列表") }
            Column(Modifier.weight(1f)) {
                Text("逐段结果", style = MaterialTheme.typography.titleLarge)
                Text(checkNotNull(record.detailSourceTitle(currentTarget)),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onRefreshTask(record.taskId) }, enabled = !state.syncing,
                modifier = Modifier.testTag("note-asr-refresh-task-${record.taskId}")) {
                Icon(Icons.Default.Refresh, "刷新任务")
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("note-asr-detail-${record.taskId}"),
            contentPadding = PaddingValues(horizontal = d.spaceLarge, vertical = d.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(d.spaceMedium),
        ) {
            item {
                PimStatusBanner("${status.label} · ${status.detail}", statusTone(status.status),
                    Modifier.testTag("note-asr-phase-${record.taskId}"))
            }
            state.message?.let { problem ->
                item { PimStatusBanner(problem, PimStatusTone.Warning) }
            }
            if (status.status == NoteAsrStatus.VerificationPending) item {
                PimStatusBanner(
                    "回执未知不等于未执行。请先按同一 TaskId 查询；系统未自动重发，也不会生成新 TaskId。",
                    PimStatusTone.Warning,
                    Modifier.testTag("note-asr-verification-pending-${record.taskId}"),
                )
                if (task == null) TextButton(
                    onClick = { replayStart = true },
                    enabled = bound,
                    modifier = Modifier.testTag("note-asr-replay-start-${record.taskId}"),
                ) { Text("用同一 TaskId 明确重试创建") }
            }
            if (task?.backgroundProcessingAvailable == false) item {
                PimStatusBanner(
                    "服务器报告后台调度不可用。任务和材料可保留，但当前不能宣称正在后台执行。",
                    PimStatusTone.Warning,
                    Modifier.testTag("note-asr-background-unavailable-${record.taskId}"),
                )
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                        Text("固定输入与目标", style = MaterialTheme.typography.titleMedium)
                        Text("versionId ${record.versionId}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            (if (record.sourceRecordings.isEmpty()) "本机录音名称映射缺失 · "
                            else "${record.sourceRecordings.size} 次录音映射 · ") +
                                asrDuration(record.effectiveTotalSamples, record.effectiveSampleRate),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("语言：${record.language}", style = MaterialTheme.typography.bodyMedium)
                        if (record.destinationDisplayName.isNotBlank()) {
                            Text("目标：${record.destinationDisplayName}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (record.destinationModel.isNotBlank()) {
                            Text("模型：${record.destinationModel}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (record.destinationLanguages.isNotEmpty()) {
                            Text("目标支持语言：${record.destinationLanguages.joinToString(" / ")}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        DestinationDetails(record.destination)
                        Text("可能产生模型费用；录音片段会发送给上述第三方 ASR 服务。费用与处理以服务商为准。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (task != null) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                            Text("覆盖与缺口", style = MaterialTheme.typography.titleMedium)
                            Text("成功片 ${task.completedSegments}/${task.totalSegments} · 失败/未确认 ${task.failedSegments}",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("成功样本 ${task.completedSamples}/${task.totalSamples} · " +
                                "${asrDuration(task.completedSamples, task.sampleRate)}/${asrDuration(task.totalSamples, task.sampleRate)}",
                                style = MaterialTheme.typography.bodyMedium)
                            val progress = if (task.totalSamples <= 0) 0f
                            else (task.completedSamples.toDouble() / task.totalSamples).toFloat().coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth()
                                .testTag("note-asr-progress-${record.taskId}"))
                            Text(if (record.canClaimWholeComplete()) "全部片段已有非空文字。" else
                                "逐段结果未证明整篇完整；空文字、缺字段、失败或未加载片段均不冒充完成。",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (record.canClaimWholeComplete()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                        OutlinedButton(onClick = { onLoadSegments(record.taskId) }, enabled = bound && !state.syncing,
                            modifier = Modifier.testTag("note-asr-load-segments-${record.taskId}")) {
                            Text(if (record.segmentsComplete) "重新核对逐段结果" else "加载逐段结果")
                        }
                        if (!task.stopRequested && task.phase in setOf("Queued", "Running")) {
                            TextButton(onClick = { confirmStop = true }, enabled = bound,
                                modifier = Modifier.testTag("note-asr-stop-${record.taskId}")) { Text("停止后续发送") }
                        }
                        if (record.segments.any(record::isRetryEligible)) {
                            TextButton(onClick = { selectingRetry = true }, enabled = bound,
                                modifier = Modifier.testTag("note-asr-retry-${record.taskId}")) { Text("选择失败片恢复") }
                        }
                    }
                }
            }
            if (state.segmentsLoadingTaskId == record.taskId) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (selectingRetry) item {
                Text("只选择失败、未知或已阻止的内部片；成功、排队与运行中的片不可选。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (groups.isEmpty() && task != null) item {
                Text("尚未加载逐段文字。能显示任务状态不代表已取得任何段结果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            groups.forEach { group ->
                item(key = "recording-${group.recordingId}") {
                    Text(group.label, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("note-asr-recording-${group.recordingId}"))
                    Text("用户录音 ${group.recordingId.take(8)} · ${group.items.size} 个内部片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(group.items, key = { "${group.recordingId}-${it.index}" }) { segment ->
                    SegmentCard(
                        record = record,
                        segment = segment,
                        selecting = selectingRetry,
                        selected = segment.index in selected,
                        enabled = bound,
                        onSelection = { checked ->
                            selected = if (checked) selected + segment.index else selected - segment.index
                        },
                    )
                }
            }
            if (selectingRetry) item {
                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    TextButton(onClick = { selectingRetry = false; selected = emptySet() }) { Text("取消选择") }
                    Button(onClick = { confirmRetry = true }, enabled = bound && selected.isNotEmpty(),
                        modifier = Modifier.testTag("note-asr-retry-continue-${record.taskId}")) {
                        Text("核对 ${selected.size} 个片段的恢复")
                    }
                }
            }
            if (record.pendingRetry?.phase == NoteAsrIntentPhase.VerificationPending) item {
                PimStatusBanner(
                    "恢复结果未确认。若服务器进度已变化，应先刷新；不会自动创建新的恢复标识。",
                    PimStatusTone.Warning,
                )
                TextButton(onClick = { replayRetry = true }, enabled = bound,
                    modifier = Modifier.testTag("note-asr-replay-retry-${record.taskId}")) {
                    Text("用原恢复标识明确核对")
                }
            }
            item {
                Text("内部片按用户录音分组。片序号、录音内时偏和全篇拼接时间均为结果定位，不代表模型逐字时间戳。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        title = { Text("停止后续转写？") },
        text = { Text("不会再发送下一片；已成功片段保留。已发出的服务商调用仍可能计费，停止不保证撤销费用。") },
        confirmButton = {
            TextButton(onClick = { confirmStop = false; onStop(record.taskId) }) { Text("确认停止") }
        },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续处理") } },
    )
    if (confirmRetry) {
        var chargeConsent by remember(confirmRetry) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmRetry = false },
            title = { Text("恢复所选片段？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    Text("目标仍为任务固定的模型版本、endpoint 和 preset。恢复可能重复调用并重复计费，成功片段不会重跑。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(chargeConsent, { chargeConsent = it },
                            modifier = Modifier.testTag("note-asr-retry-charge-check"))
                        Text("我确认所选失败/未知片段可能重复计费", Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmRetry = false; selectingRetry = false; onRetry(selected.toList()); selected = emptySet() },
                    enabled = chargeConsent,
                    modifier = Modifier.testTag("note-asr-retry-confirm"),
                ) { Text("按原目标恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("取消") } },
        )
    }
    if (replayStart) FreshConfirmationDialog(
        title = "用同一 TaskId 重试创建？",
        body = "先刷新核对；如果服务器仍无该 TaskId，才会再次提交完全相同的固定版本、目标、语言和同意快照。不会生成第二个任务。",
        tag = "note-asr-replay-start-confirm",
        onDismiss = { replayStart = false },
        onConfirm = { replayStart = false; onReplayStart(record.taskId) },
    )
    if (replayRetry) FreshConfirmationDialog(
        title = "用原恢复标识明确重试？",
        body = "恢复请求结果未知时可能已执行。再次使用原标识重放同一片段集合与目标；可能重复计费，不会选择成功或运行中的片。",
        tag = "note-asr-replay-retry-confirm",
        onDismiss = { replayRetry = false },
        onConfirm = { replayRetry = false; onReplayRetry(record.taskId) },
    )
}

@Composable
private fun UnboundTaskDetail(taskId: String, onBack: () -> Unit) {
    val d = PimTheme.dimensions
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = d.spaceSmall, vertical = d.spaceSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回任务列表") }
            Column(Modifier.weight(1f)) {
                Text("逐段结果", style = MaterialTheme.typography.titleLarge)
                Text("原账号任务", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("note-asr-detail-$taskId"),
            contentPadding = PaddingValues(horizontal = d.spaceLarge, vertical = d.spaceMedium),
            verticalArrangement = Arrangement.spacedBy(d.spaceMedium),
        ) {
            item {
                PimStatusBanner(
                    "原账号任务，当前不可访问",
                    PimStatusTone.Error,
                    Modifier.testTag("note-asr-binding-mismatch-$taskId"),
                )
            }
            item {
                Text(
                    "本机记录未删除，但当前页面不会显示旧账号/服务器的材料标题、目标或逐段文字，" +
                        "也不会用当前账号读取、停止或重试该任务。切回创建它的账号和服务器后可恢复查看。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SegmentCard(
    record: NoteAsrTaskRecord,
    segment: NoteAsrSegmentDto,
    selecting: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelection: (Boolean) -> Unit,
) {
    val d = PimTheme.dimensions
    val eligible = record.isRetryEligible(segment)
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-asr-segment-${record.taskId}-${segment.index}")) {
        Column(Modifier.padding(d.spaceMedium), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                if (selecting) Checkbox(
                    checked = selected,
                    onCheckedChange = onSelection,
                    enabled = enabled && eligible,
                    modifier = Modifier.testTag("note-asr-segment-select-${segment.index}"),
                )
                Column(Modifier.weight(1f)) {
                    Text("内部片 ${segment.index + 1} · ${segment.status}", style = MaterialTheme.typography.titleSmall)
                    Text("录音段 ${segment.recordingSegment} · 分块 ${segment.chunkIndex} · " +
                        "录音内 ${asrDuration(segment.recordingOffsetSamples)} · 全篇 ${asrDuration(segment.timelineOffsetSamples)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${segment.samples} 样本 · 尝试 ${segment.attempts.size}/10",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                when {
                    segment.transcript.isNullOrBlank() -> "空文字或缺失字段：请试听，不能视为该片已有转写。"
                    else -> segment.transcript.orEmpty()
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (segment.transcript.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            segment.attempts.lastOrNull()?.let { attempt ->
                Text("最近尝试 ${attempt.status} · ${attempt.requestId.take(8)}" +
                    (attempt.errorMessage?.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attempt.errorMessage.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error)
            }
            if (selecting && !eligible) Text(
                if (segment.status in setOf("Succeeded", "Running", "Queued"))
                    "不可选择：成功、排队或运行中的片段不会被重跑。"
                else "不可选择：该片已达尝试上限或不符合服务器恢复条件。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DestinationDetails(destination: AiAsrDestinationConsent) {
    Column(verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceSmall)) {
        Text("模型 ID：${destination.modelId}", style = MaterialTheme.typography.bodySmall)
        Text("模型 revision：${destination.modelRevision}", style = MaterialTheme.typography.bodySmall)
        Text("endpoint：${destination.endpoint}", style = MaterialTheme.typography.bodySmall)
        Text("preset：${destination.presetId} · ${destination.presetVersion}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FreshConfirmationDialog(
    title: String,
    body: String,
    tag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceSmall)) {
                Text(body)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it }, modifier = Modifier.testTag("$tag-check"))
                    Text("页面重建后重新核对并明确同意", Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmed, modifier = Modifier.testTag(tag)) {
                Text("确认核对同一标识")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteAsrConsentSheet(
    versionId: String,
    source: NoteAsrSourceDto?,
    target: NoteUploadTarget?,
    destinations: List<NoteAsrDestinationDto>,
    onDismiss: () -> Unit,
    onConfirm: (NoteAsrDestinationDto, String) -> Unit,
) {
    val d = PimTheme.dimensions
    val pinnedTarget = remember(versionId, target?.binding) { target }
    var selectedIndex by remember(destinations.map { "${it.modelId}:${it.modelRevision}" }) { mutableStateOf(0) }
    val selected = destinations.getOrNull(selectedIndex) ?: destinations.firstOrNull()
    LaunchedEffect(destinations) {
        if (selectedIndex !in destinations.indices) selectedIndex = 0
    }
    var language by remember(
        versionId, selected?.modelId, selected?.modelRevision, selected?.presetVersion
    ) {
        mutableStateOf(selected?.languages?.firstOrNull { it == "auto" } ?: selected?.languages?.firstOrNull().orEmpty())
    }
    var consent by remember(versionId, pinnedTarget?.binding, selected?.modelId, selected?.modelRevision) {
        mutableStateOf(false)
    }
    val same = pinnedTarget != null && pinnedTarget.binding == target?.binding
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(d.spaceLarge)
            .testTag("note-asr-consent-$versionId"), verticalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
            Text("确认发送固定材料至 ASR", style = MaterialTheme.typography.titleLarge)
            Text("固定 versionId $versionId", style = MaterialTheme.typography.bodySmall)
            Text(source?.let { "${it.recordings} 次录音 · ${asrDuration(it.totalSamples, it.sampleRate)} · " +
                (if (it.hasConflict) "冲突版本" else if (it.isCurrent) "当前版本" else "旧版本") }
                ?: "尚未读取服务器固定材料源，不能确认材料范围。",
                style = MaterialTheme.typography.bodyMedium)
            if (pinnedTarget == null) Text("请先登录。", color = MaterialTheme.colorScheme.error)
            else {
                Text("账号：${pinnedTarget.name.ifBlank { pinnedTarget.binding.ownerId.take(8) }}",
                    style = MaterialTheme.typography.titleSmall)
                Text("服务器：${pinnedTarget.binding.serverIdentity}", style = MaterialTheme.typography.bodyMedium)
                if (!same) Text("账号或服务器已变化，请关闭后重新核对。", color = MaterialTheme.colorScheme.error)
            }
            if (destinations.isEmpty()) {
                PimStatusBanner("当前账号没有可用的 ASR 目标。", PimStatusTone.Warning)
            } else {
                Text("选择 ASR 目标", style = MaterialTheme.typography.titleMedium)
                destinations.forEachIndexed { index, option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { selectedIndex = index }
                            .testTag("note-asr-destination-${option.modelId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { selectedIndex = index },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(option.displayLabel(), style = MaterialTheme.typography.titleSmall)
                            Text("${option.model} · preset ${option.presetId}/${option.presetVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                selected?.let {
                    DestinationDetails(it)
                    Text(
                        if (it.backgroundProcessingAvailable)
                            "后台处理：服务端报告可用"
                        else "后台处理：服务端未报告可用；任务可能只保存而不执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.backgroundProcessingAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                    Text("选择语言", style = MaterialTheme.typography.titleSmall)
                    it.languages.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { language = option }
                                .testTag("note-asr-language-$option"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == option, onClick = { language = option })
                            Text(option)
                        }
                    }
                    Text("发送后果：该固定版本的全部录音片段会出现或复制到第三方 ASR 服务，并可能产生费用。上传同意不覆盖本项同意。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }, enabled = same && selected != null && language.isNotBlank(),
                    modifier = Modifier.testTag("note-asr-consent-check"))
                Text("我明确同意将上述固定版本的全部录音发送至所示目标，并知悉费用和第三方发送后果。",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { if (consent && same) selected?.let { onConfirm(it, language) } },
                enabled = consent && same && selected != null && language.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("note-asr-submit-$versionId"),
            ) { Text("确认发送并创建任务") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("暂不发送") }
        }
    }
}

@Composable
private fun DestinationDetails(destination: NoteAsrDestinationDto) {
    Column(verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceSmall)) {
        Text("显示名：${destination.displayName}", style = MaterialTheme.typography.bodySmall)
        Text("模型：${destination.model}", style = MaterialTheme.typography.bodySmall)
        Text("模型 ID：${destination.modelId}", style = MaterialTheme.typography.bodySmall)
        Text("模型 revision：${destination.modelRevision}", style = MaterialTheme.typography.bodySmall)
        Text("endpoint：${destination.endpoint}", style = MaterialTheme.typography.bodySmall)
        Text("preset：${destination.presetId} · ${destination.presetVersion}", style = MaterialTheme.typography.bodySmall)
        Text("语言：${destination.languages.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun statusIcon(status: NoteAsrStatus) = when (status) {
    NoteAsrStatus.Succeeded -> Icons.Default.CheckCircleOutline
    NoteAsrStatus.NeedsAttention, NoteAsrStatus.BindingUnavailable -> Icons.Default.ErrorOutline
    NoteAsrStatus.VerificationPending, NoteAsrStatus.Unknown, NoteAsrStatus.Stopped -> Icons.Default.WarningAmber
    NoteAsrStatus.Running -> Icons.Default.GraphicEq
    NoteAsrStatus.Queued, NoteAsrStatus.Prepared -> Icons.Default.HourglassEmpty
}

private fun statusTone(status: NoteAsrStatus) = when (status) {
    NoteAsrStatus.Succeeded -> PimStatusTone.Success
    NoteAsrStatus.NeedsAttention, NoteAsrStatus.BindingUnavailable -> PimStatusTone.Error
    NoteAsrStatus.VerificationPending, NoteAsrStatus.Unknown, NoteAsrStatus.Stopped -> PimStatusTone.Warning
    NoteAsrStatus.Queued, NoteAsrStatus.Running, NoteAsrStatus.Prepared -> PimStatusTone.Info
}

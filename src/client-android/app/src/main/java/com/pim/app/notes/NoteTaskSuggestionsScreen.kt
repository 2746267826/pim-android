package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pim.core.contract.NoteTaskSuggestionExtractionStatusDto
import com.pim.core.contract.NoteTaskSuggestionKindDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto

data class NoteTaskSuggestionsActions(
    val dismiss: () -> Unit = {},
    val refresh: () -> Unit = {},
    val revalidate: () -> Unit = {},
    val extract: (NoteTaskSuggestionActionScope, NoteTaskSuggestionDestination, Boolean) -> Unit =
        { _, _, _ -> },
    val edit: (NoteTaskSuggestionActionScope, String, NoteTaskSuggestionEdit) -> Unit =
        { _, _, _ -> },
    val save: (NoteTaskSuggestionActionScope, String) -> Unit = { _, _ -> },
    val discard: (NoteTaskSuggestionActionScope, String) -> Unit = { _, _ -> },
    val rebase: (NoteTaskSuggestionActionScope, String) -> Unit = { _, _ -> },
    val confirm: (NoteTaskSuggestionActionScope, String) -> Unit = { _, _ -> },
    val dismissSuggestion: (NoteTaskSuggestionActionScope, String) -> Unit = { _, _ -> },
)

@Composable
fun NoteTaskSuggestionsScreen(
    state: NoteTaskSuggestionsState,
    actions: NoteTaskSuggestionsActions = NoteTaskSuggestionsActions(),
) {
    val source = state.source
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) actions.revalidate()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var destinationKey by rememberSaveable(state.actionScope) { mutableStateOf<String?>(null) }
    val selectedDestination = if (state.frozenExtraction) {
        state.frozenDestination ?: state.destinations.firstOrNull()
    } else {
        state.destinations.firstOrNull { destinationIdentity(it) == destinationKey }
            ?: state.destinations.firstOrNull()
    }
    var acknowledgeCharge by rememberSaveable(
        state.actionScope,
        selectedDestination?.let(::destinationIdentity),
    ) { mutableStateOf(false) }
    val activeExtraction = state.extraction?.status in setOf(
        NoteTaskSuggestionExtractionStatusDto.QUEUED,
        NoteTaskSuggestionExtractionStatusDto.RUNNING,
    )
    val dialogScope = remember(state.actionScope) { Any() }
    var pendingDismissId by remember(dialogScope) { mutableStateOf<String?>(null) }
    var pendingConfirmId by remember(dialogScope) { mutableStateOf<String?>(null) }
    var showSource by remember(dialogScope) { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize().testTag("note-task-suggestions-screen")) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("任务与日程建议", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("固定来源 · ${source?.title?.ifBlank { "未命名笔记" } ?: "未选择版本"}")
                    Text("版本 ${source?.versionId?.take(8).orEmpty()} · 材料 ${source?.materialVersionId?.take(8).orEmpty()}")
                }
                TextButton(onClick = actions.dismiss, modifier = Modifier.testTag("task-suggestions-back")) {
                    Text("返回")
                }
                if (source != null) TextButton(
                    onClick = actions.refresh,
                    enabled = !state.loading,
                    modifier = Modifier.testTag("task-suggestions-refresh"),
                ) { Text("刷新核验") }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().testTag("task-suggestions-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "task-suggestions-source-status") {
                    Text(
                        text = if (state.sourceVerified) "服务端原文与内容摘要已核验（${state.sourceCharacterCount} 字）；不会发送本机未保存文字。"
                        else "来源尚未核验；提取与建议操作保持关闭。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!state.loading && state.actionScope != null && state.sourceContent != null) {
                    item(key = "task-suggestions-view-source") {
                        TextButton(onClick = { showSource = true },
                            modifier = Modifier.testTag("task-suggestions-view-source")) {
                            Text("查看将用于提取的服务端原文")
                        }
                    }
                }
                state.extraction?.takeIf { !state.loading && state.actionScope != null }?.let { extraction ->
                    item(key = "task-suggestions-extraction") {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("提取记录：${extraction.status} · 尝试 ${extraction.attemptCount} · 建议 ${extraction.suggestionCount}")
                                Text("目标 ${extraction.modelProfileId.take(8)} / 修订 ${extraction.modelRevision.take(8)}")
                                extraction.errorMessage?.takeIf(String::isNotBlank)?.let { Text("错误：$it") }
                                if (state.frozenExtraction) Text("请求标识与模型目标已冻结；状态未确认时只能按原请求核对/重试。")
                            }
                        }
                    }
                }
                selectedDestination?.takeIf { !state.loading && state.actionScope != null }?.let { selected ->
                    item(key = "task-suggestions-extraction-controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("模型目标", style = MaterialTheme.typography.titleMedium)
                            if (state.frozenExtraction) {
                                Text("${selected.displayName} · ${selected.model}")
                            } else {
                                DestinationPicker(
                                    destinations = state.destinations,
                                    selected = selected,
                                    onSelected = { destinationKey = destinationIdentity(it) },
                                )
                            }
                            Row {
                                Checkbox(
                                    checked = acknowledgeCharge,
                                    onCheckedChange = { acknowledgeCharge = it },
                                    enabled = !state.frozenExtraction,
                                    modifier = Modifier.testTag("task-suggestions-charge-acknowledgement"),
                                )
                                Text("我确认发送固定服务端版本，且接受可能产生的费用")
                            }
                            Button(
                                onClick = {
                                    state.actionScope?.let { scope ->
                                        actions.extract(scope, selected, acknowledgeCharge)
                                    }
                                },
                                enabled = state.sourceVerified && !state.loading && !activeExtraction &&
                                    (state.frozenExtraction || selected in state.destinations) &&
                                    state.sourceCharacterCount <= NoteTaskSuggestionRepository.MAX_SOURCE_CHARACTERS &&
                                    (!state.frozenExtraction && acknowledgeCharge || state.frozenExtraction),
                                modifier = Modifier.fillMaxWidth().testTag("task-suggestions-extract"),
                            ) {
                                Text(if (state.frozenExtraction) "核对 / 重试原提取请求" else "提取任务与日程建议")
                            }
                        }
                    }
                }
                items(state.items, key = { "${state.actionScope?.source?.versionId}:${it.remote.id}" }) { item ->
                    NoteTaskSuggestionCard(
                        item = item,
                        scope = state.actionScope,
                        actions = actions,
                        onDismiss = { pendingDismissId = item.remote.id },
                        onConfirm = { pendingConfirmId = item.remote.id },
                    )
                }
            }
        }
    }
    pendingDismissId?.takeIf { state.actionScope != null && !state.loading }?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDismissId = null },
            title = { Text("放弃此建议？") },
            text = { Text("服务端建议会标记为已放弃，不会创建任务或日程。请先保存或明确删除未同步的本机修订。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDismissId = null
                        state.actionScope?.let { scope -> actions.dismissSuggestion(scope, id) }
                    },
                    enabled = state.actionScope != null,
                ) { Text("确认放弃建议") }
            },
            dismissButton = { TextButton(onClick = { pendingDismissId = null }) { Text("取消") } },
        )
    }
    pendingConfirmId?.takeIf { state.actionScope != null && !state.loading }?.let { id ->
        val item = state.items.firstOrNull { it.remote.id == id }
        val isEvent = item?.kind == NoteTaskSuggestionKindDto.EVENT
        AlertDialog(
            onDismissRequest = { pendingConfirmId = null },
            title = {
                Text(
                    if (item?.confirmationPending == true) "重试同一创建请求？"
                    else if (isEvent) "创建日历日程？" else "创建日历任务？",
                )
            },
            text = {
                Text(
                    item?.let {
                        val summary = if (isEvent) {
                            "将使用当前保存版本创建日程：${it.title}\n" +
                                "时区 ${it.remote.timeZoneId?.ifBlank { "未设置" } ?: "未设置"} · " +
                                "${formatEventRange(it.remote.start, it.remote.end, it.remote.timeZoneId)}。"
                        } else {
                            "将使用当前保存版本创建任务：${it.title}\n" +
                                "优先级 ${it.priority} · 截止 ${it.due.ifBlank { "未设置" }}。"
                        }
                        summary +
                            if (it.confirmationPending) "\n未确认请求会复用原 requestId，不创建新的请求身份。" else ""
                    } ?: "该建议已变化；请刷新后核对。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmId = null
                        state.actionScope?.let { scope -> actions.confirm(scope, id) }
                    },
                    enabled = state.actionScope != null && item != null && !item.dirty &&
                        !item.hasConflict && !item.confirmationBlocked && item.eventReady,
                    modifier = Modifier.testTag("task-suggestion-confirm-dialog-$id"),
                ) { Text("确认创建") }
            },
            dismissButton = { TextButton(onClick = { pendingConfirmId = null }) { Text("返回检查") } },
        )
    }
    if (showSource) {
        AlertDialog(
            onDismissRequest = { showSource = false },
            title = { Text("固定版本服务端原文") },
            text = { Text(state.sourceContent.orEmpty()) },
            confirmButton = { TextButton(onClick = { showSource = false }) { Text("关闭") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationPicker(
    destinations: List<NoteTaskSuggestionDestination>,
    selected: NoteTaskSuggestionDestination,
    onSelected: (NoteTaskSuggestionDestination) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected.destinationLabel(),
            onValueChange = {},
            readOnly = true,
            label = { Text("已验证的文本目标") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("task-suggestions-destination"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            destinations.forEach { destination ->
                DropdownMenuItem(
                    text = { Text(destination.destinationLabel()) },
                    onClick = { expanded = false; onSelected(destination) },
                )
            }
        }
    }
}

private fun destinationIdentity(destination: NoteTaskSuggestionDestination): String =
    "${destination.modelId}\u0000${destination.modelRevision}\u0000${destination.endpoint}"

private fun NoteTaskSuggestionDestination.destinationLabel(): String =
    "$displayName · $model · 修订 ${modelRevision.take(8)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteTaskSuggestionCard(
    item: NoteTaskSuggestionItem,
    scope: NoteTaskSuggestionActionScope?,
    actions: NoteTaskSuggestionsActions,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val remote = item.remote
    val draft = item.draft
    val isEvent = item.kind == NoteTaskSuggestionKindDto.EVENT
    var title by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.title) }
    var description by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.description) }
    var priority by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.priority) }
    var due by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.due) }
    var start by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.start) }
    var end by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.end) }
    var timeZoneId by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.timeZoneId) }
    var lastDraftRevision by remember(scope, remote.id, remote.revision) {
        mutableStateOf(item.draft?.expectedRevision)
    }
    var locallyEdited by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(false) }
    LaunchedEffect(item.draft?.expectedRevision) {
        val currentDraft = item.draft
        val currentRevision = currentDraft?.expectedRevision
        when {
            currentDraft == null && lastDraftRevision != null -> {
                title = remote.title
                description = remote.description.orEmpty()
                priority = remote.priority
                due = if (isEvent) "" else suggestionDueInput(remote.due)
                start = if (isEvent) suggestionEventInput(remote.start, remote.timeZoneId) else ""
                end = if (isEvent) suggestionEventInput(remote.end, remote.timeZoneId) else ""
                timeZoneId = if (isEvent) remote.timeZoneId.orEmpty() else ""
                locallyEdited = false
            }
            currentDraft != null && lastDraftRevision == null && !locallyEdited -> {
                title = currentDraft.title
                description = currentDraft.description
                priority = currentDraft.priority
                due = currentDraft.due
                start = currentDraft.start
                end = currentDraft.end
                timeZoneId = currentDraft.timeZoneId
            }
            currentDraft != null && lastDraftRevision != null && lastDraftRevision != currentRevision -> {
                title = currentDraft.title
                description = currentDraft.description
                priority = currentDraft.priority
                due = currentDraft.due
                start = currentDraft.start
                end = currentDraft.end
                timeZoneId = currentDraft.timeZoneId
            }
        }
        lastDraftRevision = currentRevision
    }
    val currentEdit = NoteTaskSuggestionEdit(
        title = title,
        description = description,
        priority = priority,
        due = if (isEvent) "" else due,
        start = if (isEvent) start else "",
        end = if (isEvent) end else "",
        timeZoneId = if (isEvent) timeZoneId else "",
    )
    fun emitEdit(edit: NoteTaskSuggestionEdit) {
        scope?.let { actions.edit(it, remote.id, edit) }
    }

    val editable = remote.status == NoteTaskSuggestionStatusDto.DRAFT && scope != null
    val conflicted = item.hasConflict
    OutlinedCard(Modifier.fillMaxWidth().testTag("task-suggestion-${remote.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "建议 ${remote.ordinal + 1} · ${remote.status} · ${if (isEvent) "日程" else "任务"}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("task-suggestion-kind-${remote.id}"),
            )
            Text("服务端修订 ${remote.revision.take(8)} · 来源摘要 ${remote.sourceContentSha256.take(12)}")
            if (isEvent) {
                Text(
                    "日程时间：${formatEventRange(remote.start, remote.end, remote.timeZoneId)}",
                    modifier = Modifier.testTag("task-suggestion-range-${remote.id}"),
                )
            }
            if (remote.status == NoteTaskSuggestionStatusDto.CONFIRMED) {
                if (isEvent) {
                    Text("已确认创建日程 ${remote.eventId?.take(8) ?: "（服务端未提供日程编号）"}")
                } else {
                    Text("已确认创建任务 ${remote.taskId?.take(8) ?: "（服务端未提供任务编号）"}")
                }
            } else if (remote.status == NoteTaskSuggestionStatusDto.DISMISSED) {
                Text("已放弃的建议只读。")
            } else {
                OutlinedTextField(title, {
                    title = it
                    locallyEdited = true
                    emitEdit(currentEdit.copy(title = it))
                },
                    label = { Text("标题") }, enabled = editable && !item.confirmationPending,
                    modifier = Modifier.fillMaxWidth().testTag("task-suggestion-title-${remote.id}"))
                OutlinedTextField(description, {
                    description = it
                    locallyEdited = true
                    emitEdit(currentEdit.copy(description = it))
                },
                    label = { Text("说明") }, enabled = editable && !item.confirmationPending,
                    modifier = Modifier.fillMaxWidth().testTag("task-suggestion-description-${remote.id}"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(priority.toString(), {
                        it.toIntOrNull()?.takeIf { value -> value == 1 || value == 5 || value == 9 }?.let { value ->
                            priority = value
                            locallyEdited = true
                            emitEdit(currentEdit.copy(priority = value))
                        }
                    }, label = { Text("优先级 1 / 5 / 9") }, enabled = editable && !item.confirmationPending,
                        modifier = Modifier.weight(1f).testTag("task-suggestion-priority-${remote.id}"))
                    if (isEvent) {
                        OutlinedTextField(timeZoneId, {
                            val nextStart = if (start.isBlank()) {
                                suggestionEventInput(remote.start, it)
                            } else {
                                start
                            }
                            val nextEnd = if (end.isBlank()) {
                                suggestionEventInput(remote.end, it)
                            } else {
                                end
                            }
                            timeZoneId = it
                            start = nextStart
                            end = nextEnd
                            locallyEdited = true
                            emitEdit(currentEdit.copy(
                                start = nextStart,
                                end = nextEnd,
                                timeZoneId = it,
                            ))
                        }, label = { Text("IANA 时区") }, enabled = editable && !item.confirmationPending,
                            modifier = Modifier.weight(2f).testTag("task-suggestion-timezone-${remote.id}"))
                    } else {
                        OutlinedTextField(due, {
                            due = it
                            locallyEdited = true
                            emitEdit(currentEdit.copy(due = it))
                        }, label = { Text("截止时间（本地）") }, enabled = editable && !item.confirmationPending,
                            modifier = Modifier.weight(2f).testTag("task-suggestion-due-${remote.id}"))
                    }
                }
                if (isEvent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(start, {
                            start = it
                            locallyEdited = true
                            emitEdit(currentEdit.copy(start = it))
                        }, label = { Text("开始时间") }, enabled = editable && !item.confirmationPending,
                            modifier = Modifier.weight(1f).testTag("task-suggestion-start-${remote.id}"))
                        OutlinedTextField(end, {
                            end = it
                            locallyEdited = true
                            emitEdit(currentEdit.copy(end = it))
                        }, label = { Text("结束时间") }, enabled = editable && !item.confirmationPending,
                            modifier = Modifier.weight(1f).testTag("task-suggestion-end-${remote.id}"))
                    }
                    item.eventHint?.let { hint ->
                        Text(
                            hint,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("task-suggestion-event-hint-${remote.id}"),
                        )
                    }
                }
                if (conflicted) {
                    Text("发现服务端修订冲突。双方内容均保留；只有明确更新 CAS 基线后才能保存。")
                    TextButton(onClick = { scope?.let { s -> actions.rebase(s, remote.id) } }, enabled = !item.confirmationPending,
                        modifier = Modifier.testTag("task-suggestion-rebase-${remote.id}")) { Text("保留本机内容并以当前修订为新基线") }
                }
                if (item.confirmationPending) Text(
                    if (item.confirmationBlocked) "旧修订的创建回执未确认，不能绑定到新修订；请刷新核对。"
                    else "创建请求未确认；重试将复用同一请求标识。",
                    color = MaterialTheme.colorScheme.error,
                )
                if (draft?.dirty == true) Text("本机修订尚未保存；请先保存或明确放弃，不能直接放弃服务端建议。")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { scope?.let { s -> actions.save(s, remote.id) } }, enabled = scope != null && draft?.dirty == true && !conflicted &&
                        !item.confirmationPending, modifier = Modifier.testTag("task-suggestion-save-${remote.id}")) {
                        Text("保存到服务器")
                    }
                    TextButton(onClick = { scope?.let { s -> actions.discard(s, remote.id) } }, enabled = scope != null && draft != null && !item.confirmationPending,
                        modifier = Modifier.testTag("task-suggestion-discard-${remote.id}")) { Text("放弃本机修订") }
                    TextButton(onClick = onDismiss, enabled = scope != null && !item.confirmationPending && !item.dirty,
                        modifier = Modifier.testTag("task-suggestion-dismiss-${remote.id}")) { Text("放弃建议") }
                    Button(onClick = onConfirm, enabled = scope != null && !item.dirty && !conflicted &&
                        !item.confirmationBlocked && item.eventReady,
                        modifier = Modifier.testTag("task-suggestion-confirm-${remote.id}")) {
                        Text(
                            if (item.confirmationPending) "重试原创建请求"
                            else if (isEvent) "确认创建日程" else "确认创建任务",
                        )
                    }
                }
            }
            if (remote.status != NoteTaskSuggestionStatusDto.DRAFT && draft?.dirty == true) {
                Text("服务端状态已变化；未同步的本机修订仍保留，未自动删除。")
                Text("本机标题：${draft.title}")
                if (draft.description.isNotBlank()) Text("本机说明：${draft.description}")
                if (isEvent) {
                    Text(
                        "本机日程 ${draft.timeZoneId.ifBlank { "未设置时区" }} · " +
                            "开始 ${draft.start.ifBlank { "未设置" }} · 结束 ${draft.end.ifBlank { "未设置" }}",
                    )
                } else {
                    Text("本机优先级 ${draft.priority} · 截止 ${draft.due.ifBlank { "未设置" }}")
                }
                TextButton(
                    onClick = { scope?.let { s -> actions.discard(s, remote.id) } },
                    enabled = scope != null && !item.confirmationPending,
                    modifier = Modifier.testTag("task-suggestion-discard-terminal-${remote.id}"),
                ) { Text("明确删除本机未同步修订") }
            }
        }
    }
}

package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExposedDropdownMenu
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
import com.pim.core.contract.NoteTaskSuggestionStatusDto

data class NoteTaskSuggestionsActions(
    val dismiss: () -> Unit = {},
    val refresh: () -> Unit = {},
    val revalidate: () -> Unit = {},
    val extract: (NoteTaskSuggestionActionScope, NoteTaskSuggestionDestination, Boolean) -> Unit =
        { _, _, _ -> },
    val edit: (NoteTaskSuggestionActionScope, String, String, String, Int, String) -> Unit =
        { _, _, _, _, _, _ -> },
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
    var destinationId by rememberSaveable(state.actionScope) { mutableStateOf<String?>(null) }
    var acknowledgeCharge by rememberSaveable(state.actionScope) { mutableStateOf(false) }
    val selectedDestination = state.destinations.firstOrNull {
        it.modelId == destinationId
    } ?: state.frozenDestination ?: state.destinations.firstOrNull()
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
                    Text("任务建议", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
                                    onSelected = { destinationId = it.modelId },
                                )
                            }
                            Row {
                                Checkbox(
                                    checked = acknowledgeCharge,
                                    onCheckedChange = { acknowledgeCharge = it },
                                    enabled = !state.frozenExtraction,
                                )
                                Text("我确认发送固定服务端版本，且接受可能产生的费用")
                            }
                            Button(
                                onClick = {
                                    val scope = state.actionScope ?: return@onClick
                                    actions.extract(scope, selected, acknowledgeCharge)
                                },
                                enabled = state.sourceVerified && !state.loading && !activeExtraction &&
                                    (state.frozenExtraction || selected in state.destinations) &&
                                    state.sourceCharacterCount <= NoteTaskSuggestionRepository.MAX_SOURCE_CHARACTERS &&
                                    (!state.frozenExtraction && acknowledgeCharge || state.frozenExtraction),
                                modifier = Modifier.fillMaxWidth().testTag("task-suggestions-extract"),
                            ) {
                                Text(if (state.frozenExtraction) "核对 / 重试原提取请求" else "提取任务建议")
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
            title = { Text("放弃此任务建议？") },
            text = { Text("服务端建议会标记为已放弃。请先保存或明确删除未同步的本机修订。") },
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
        AlertDialog(
            onDismissRequest = { pendingConfirmId = null },
            title = { Text(if (item?.confirmationPending == true) "重试同一创建请求？" else "创建日历任务？") },
            text = {
                Text(
                    item?.let {
                        "将使用当前保存版本创建任务：${it.title}\n优先级 ${it.priority} · 截止 ${it.due.ifBlank { "未设置" }}。" +
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
                        !item.hasConflict && !item.confirmationBlocked,
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
            value = "${selected.displayName} · ${selected.model}",
            onValueChange = {},
            readOnly = true,
            label = { Text("已验证的文本目标") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("task-suggestions-destination"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            destinations.forEach { destination ->
                DropdownMenuItem(
                    text = { Text("${destination.displayName} · ${destination.model}") },
                    onClick = { expanded = false; onSelected(destination) },
                )
            }
        }
    }
}

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
    var title by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.title) }
    var description by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.description) }
    var priority by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.priority) }
    var due by rememberSaveable(scope, remote.id, remote.revision) { mutableStateOf(item.due) }
    val editable = remote.status == NoteTaskSuggestionStatusDto.DRAFT && scope != null
    val conflicted = item.hasConflict
    OutlinedCard(Modifier.fillMaxWidth().testTag("task-suggestion-${remote.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("建议 ${remote.ordinal + 1} · ${remote.status}", style = MaterialTheme.typography.labelLarge)
            Text("服务端修订 ${remote.revision.take(8)} · 来源摘要 ${remote.sourceContentSha256.take(12)}")
            if (remote.status == NoteTaskSuggestionStatusDto.CONFIRMED) {
                Text("已确认创建任务 ${remote.taskId?.take(8) ?: "（服务端未提供任务编号）"}")
            } else if (remote.status == NoteTaskSuggestionStatusDto.DISMISSED) {
                Text("已放弃的建议只读。")
            } else {
                OutlinedTextField(title, { title = it; scope?.let { s -> actions.edit(s, remote.id, it, description, priority, due) } },
                    label = { Text("标题") }, enabled = editable && !item.confirmationPending,
                    modifier = Modifier.fillMaxWidth().testTag("task-suggestion-title-${remote.id}"))
                OutlinedTextField(description, { description = it; scope?.let { s -> actions.edit(s, remote.id, title, it, priority, due) } },
                    label = { Text("说明") }, enabled = editable && !item.confirmationPending,
                    modifier = Modifier.fillMaxWidth().testTag("task-suggestion-description-${remote.id}"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(priority.toString(), {
                        it.toIntOrNull()?.takeIf { value -> value == 1 || value == 5 || value == 9 }?.let { value ->
                            scope?.let { s -> actions.edit(s, remote.id, title, description, value, due) }
                        }
                    }, label = { Text("优先级 1 / 5 / 9") }, enabled = editable && !item.confirmationPending,
                        modifier = Modifier.weight(1f).testTag("task-suggestion-priority-${remote.id}"))
                    OutlinedTextField(due, { due = it; scope?.let { s -> actions.edit(s, remote.id, title, description, priority, it) } },
                        label = { Text("截止时间（本地）") }, enabled = editable && !item.confirmationPending,
                        modifier = Modifier.weight(2f).testTag("task-suggestion-due-${remote.id}"))
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope?.let { s -> actions.save(s, remote.id) } }, enabled = scope != null && draft?.dirty == true && !conflicted &&
                        !item.confirmationPending, modifier = Modifier.testTag("task-suggestion-save-${remote.id}")) {
                        Text("保存到服务器")
                    }
                    TextButton(onClick = { scope?.let { s -> actions.discard(s, remote.id) } }, enabled = scope != null && draft != null && !item.confirmationPending,
                        modifier = Modifier.testTag("task-suggestion-discard-${remote.id}")) { Text("放弃本机修订") }
                    TextButton(onClick = onDismiss, enabled = scope != null && !item.confirmationPending && !item.dirty,
                        modifier = Modifier.testTag("task-suggestion-dismiss-${remote.id}")) { Text("放弃建议") }
                    Button(onClick = onConfirm, enabled = scope != null && !item.dirty && !conflicted &&
                        !item.confirmationBlocked, modifier = Modifier.testTag("task-suggestion-confirm-${remote.id}")) {
                        Text(if (item.confirmationPending) "重试原创建请求" else "确认创建任务")
                    }
                }
            }
            if (remote.status != NoteTaskSuggestionStatusDto.DRAFT && draft?.dirty == true) {
                Text("服务端状态已变化；未同步的本机修订仍保留，未自动删除。")
                Text("本机标题：${draft.title}")
                if (draft.description.isNotBlank()) Text("本机说明：${draft.description}")
                Text("本机优先级 ${draft.priority} · 截止 ${draft.due.ifBlank { "未设置" }}")
                TextButton(
                    onClick = { scope?.let { s -> actions.discard(s, remote.id) } },
                    enabled = scope != null && !item.confirmationPending,
                    modifier = Modifier.testTag("task-suggestion-discard-terminal-${remote.id}"),
                ) { Text("明确删除本机未同步修订") }
            }
        }
    }
}

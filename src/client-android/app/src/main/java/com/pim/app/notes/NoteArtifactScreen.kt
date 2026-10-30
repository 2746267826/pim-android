package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme

/**
 * Explicit callbacks exposed by the pure Compose surface.
 *
 * Network integration is intentionally absent. Every state-changing action is
 * hoisted so a later writer can persist it through the official generated
 * contract without this screen creating operations or mutating source data.
 */
data class NoteArtifactScreenActions(
    val dismiss: () -> Unit = {},
    val selectKind: (NoteArtifactKind) -> Unit = {},
    val refresh: (NoteArtifactKind) -> Unit = {},
    val changeContentView: (NoteArtifactKind, NoteArtifactContentView) -> Unit = { _, _ -> },
    val viewCandidateSource: (NoteArtifactKind, String) -> Unit = { _, _ -> },
    val selectCandidate: (NoteArtifactKind, String) -> Unit = { _, _ -> },
    val changeDraftText: (NoteArtifactDraftEditRequest) -> Unit = {},
    val saveLocalDraft: (NoteArtifactDraftSaveRequest) -> Unit = {},
    val saveServerRevision: (NoteArtifactDraftSaveRequest) -> Unit = {},
    val regenerate: (NoteArtifactKind) -> Unit = {},
    val stop: (NoteArtifactKind) -> Unit = {},
    val recover: (NoteArtifactKind) -> Unit = {},
)

@Composable
fun NoteArtifactScreen(
    state: NoteArtifactUiState,
    actions: NoteArtifactScreenActions = NoteArtifactScreenActions(),
) {
    val kind = state.selectedKind
    val editor = state.editor(kind)

    Surface(
        modifier = Modifier.fillMaxSize().testTag("note-artifact-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            NoteArtifactHeader(
                kind = kind,
                loading = state.loading,
                onDismiss = actions.dismiss,
                onRefresh = { actions.refresh(kind) },
            )
            NoteArtifactKindTabs(
                state = state,
                onSelected = actions.selectKind,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("note-artifact-list"),
                contentPadding = PaddingValues(
                    start = PimTheme.dimensions.spaceLarge,
                    top = PimTheme.dimensions.spaceMedium,
                    end = PimTheme.dimensions.spaceLarge,
                    bottom = PimTheme.dimensions.spaceExtraLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceMedium),
            ) {
                if (state.loading) {
                    item(key = "note-artifact-loading") {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (state.loadFailed) {
                    item(key = "note-artifact-load-failed") {
                        PimStatusBanner(
                            text = "部分本机稿无法读取；其余草稿保留，损坏文件没有被覆盖。",
                            tone = PimStatusTone.Error,
                            modifier = Modifier.testTag("note-artifact-load-failed"),
                        )
                    }
                }
                state.message?.let { message ->
                    item(key = "note-artifact-message") {
                        PimStatusBanner(
                            text = message.text,
                            tone = message.level.toStatusTone(),
                            modifier = Modifier.testTag("note-artifact-message"),
                        )
                    }
                }
                item(key = "note-artifact-material") {
                    FixedArtifactMaterialCard(state.material)
                }
                item(key = "note-artifact-coverage") {
                    ArtifactCoverageCard(state.coverage)
                }
                item(key = "note-artifact-work-${kind.name}") {
                    ArtifactWorkCard(
                        editor = editor,
                        onRegenerate = { actions.regenerate(kind) },
                        onStop = { actions.stop(kind) },
                        onRecover = { actions.recover(kind) },
                    )
                }
                item(key = "note-artifact-content-switch-${kind.name}") {
                    ArtifactContentViewSwitch(
                        editor = editor,
                        onViewChanged = { view -> actions.changeContentView(kind, view) },
                    )
                }
                item(key = "note-artifact-content-${kind.name}") {
                    when (editor.contentView) {
                        NoteArtifactContentView.LocalDraft -> LocalDraftPanel(
                            editor = editor,
                            onTextChanged = actions.changeDraftText,
                            onSave = actions.saveLocalDraft,
                            onSaveServer = actions.saveServerRevision,
                        )
                        NoteArtifactContentView.ServerOriginal -> ServerOriginalPanel(editor)
                    }
                }
                item(key = "note-artifact-candidates-heading-${kind.name}") {
                    Column(verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceExtraSmall)) {
                        Text(
                            text = "服务端候选",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "模型原稿、人工稿和后续候选分别标明来源与基线。查看原文不会混入本机未同步修改。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (editor.candidates.isEmpty()) {
                    item(key = "note-artifact-candidates-empty-${kind.name}") {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(
                                text = "尚未加载服务端候选。本机稿仍可继续查看和保存。",
                                modifier = Modifier.padding(PimTheme.dimensions.spaceLarge),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(
                    items = editor.candidates,
                    key = { candidate -> "note-artifact-candidate-${kind.name}-${candidate.candidateId}" },
                ) { candidate ->
                    CandidateCard(
                        editor = editor,
                        candidate = candidate,
                        onViewSource = { actions.viewCandidateSource(kind, candidate.candidateId) },
                        onSelect = { actions.selectCandidate(kind, candidate.candidateId) },
                    )
                }
                item(key = "note-artifact-candidate-boundary-${kind.name}") {
                    Text(
                        text = if (editor.isDirty) {
                            "本机稿有未同步修改。刷新或切换候选都只更新服务端视图；输入框、未同步文字和编辑基线保持不变，直到你明确保存或选择应用方式。"
                        } else {
                            "刷新只读取服务端状态；选择候选是显式操作。本机稿与服务端原文始终分开显示。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (editor.isDirty) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("note-artifact-draft-boundary-${kind.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteArtifactHeader(
    kind: NoteArtifactKind,
    loading: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    val dimensions = PimTheme.dimensions
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = dimensions.spaceSmall,
            vertical = dimensions.spaceSmall,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("note-artifact-close"),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回笔记")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "笔记派生结果",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${kind.displayName()} · 固定来源 · 本机稿独立",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.testTag("note-artifact-refresh-${kind.name}"),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新服务端状态")
        }
    }
}

@Composable
private fun NoteArtifactKindTabs(
    state: NoteArtifactUiState,
    onSelected: (NoteArtifactKind) -> Unit,
) {
    val selectedIndex = when (state.selectedKind) {
        NoteArtifactKind.FullRecord -> 0
        NoteArtifactKind.Analysis -> 1
    }
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth().testTag("note-artifact-kind-tabs"),
    ) {
        Tab(
            selected = selectedIndex == 0,
            onClick = { onSelected(NoteArtifactKind.FullRecord) },
            modifier = Modifier.testTag("note-artifact-kind-FullRecord"),
            text = {
                ArtifactTabLabel(
                    label = "完整记录",
                    dirty = state.fullRecord.isDirty,
                )
            },
        )
        Tab(
            selected = selectedIndex == 1,
            onClick = { onSelected(NoteArtifactKind.Analysis) },
            modifier = Modifier.testTag("note-artifact-kind-Analysis"),
            text = {
                ArtifactTabLabel(
                    label = "独立分析",
                    dirty = state.analysis.isDirty,
                )
            },
        )
    }
}

@Composable
private fun ArtifactTabLabel(label: String, dirty: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label)
        if (dirty) {
            Text(
                text = "本机未同步",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FixedArtifactMaterialCard(material: NoteArtifactMaterialUi) {
    val dimensions = PimTheme.dimensions
    OutlinedCard(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = "固定材料来源，不随当前草稿变化"
            }
            .testTag("note-artifact-material"),
    ) {
        Column(
            modifier = Modifier.padding(dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("固定材料与来源", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = material.title.ifBlank { "未命名材料来源" },
                style = MaterialTheme.typography.titleSmall,
            )
            if (material.summary.isNotBlank()) {
                Text(
                    text = material.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ArtifactFact("服务端", material.serverLabel)
            ArtifactFact("账号", material.ownerLabel)
            ArtifactFact("笔记 ID", material.noteId)
            ArtifactFact("材料版本 ID", material.materialVersionId)
            ArtifactFact("固定时间", material.fixedAtLabel)
        }
    }
}

@Composable
private fun ArtifactCoverageCard(coverage: NoteArtifactCoverageUi) {
    val dimensions = PimTheme.dimensions
    OutlinedCard(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = if (coverage.complete) "材料完整覆盖" else "材料存在明确缺口"
            }
            .testTag("note-artifact-coverage"),
    ) {
        Column(
            modifier = Modifier.padding(dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (coverage.complete) Icons.Default.CheckCircle
                    else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (coverage.complete) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text("全部材料覆盖", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = if (coverage.complete) "材料完整覆盖，无已知缺口。"
                else "材料覆盖未完成；以下缺口会参与派生结果的状态判断。",
                style = MaterialTheme.typography.bodyMedium,
                color = if (coverage.complete) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
            CoverageCount("文本块", coverage.textBlocks, null)
            CoverageCount(
                "转写段",
                coverage.transcriptSegments,
                coverage.completedTranscriptSegments,
            )
            CoverageCount("照片", coverage.photos, coverage.completedPhotos)
            CoverageCount("墨迹预览", coverage.inkPreviews, coverage.completedInkPreviews)
            CoverageCount(
                "图像识别批次",
                coverage.visionBatches,
                coverage.completedVisionBatches,
            )
            Text("明确缺口", style = MaterialTheme.typography.titleSmall)
            if (coverage.gapMessages.isEmpty()) {
                Text(
                    text = "无缺口：服务端覆盖完整且各项完成数未超过总数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                coverage.gapMessages.forEachIndexed { index, gap ->
                    Text(
                        text = "${index + 1}. $gap",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("note-artifact-coverage-gap-$index"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverageCount(label: String, total: Int, completed: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(PimTheme.dimensions.spaceSmall))
        Text(
            text = if (completed == null) "$total 个已纳入" else "$completed/$total 已完成",
            style = MaterialTheme.typography.bodyMedium,
            color = if (completed == null || completed >= total) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun ArtifactWorkCard(
    editor: NoteArtifactEditorUiState,
    onRegenerate: () -> Unit,
    onStop: () -> Unit,
    onRecover: () -> Unit,
) {
    val dimensions = PimTheme.dimensions
    var confirmRegenerate by rememberSaveable(editor.kind) { mutableStateOf(false) }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("重新生成候选") },
            text = {
                Text(
                    "重新生成会创建新的模型候选，可能产生额外费用。不会覆盖本机稿或已有人工版本；" +
                        "只有你之后显式选择候选，才会改变当前版本。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRegenerate = false
                        onRegenerate()
                    },
                    modifier = Modifier.testTag("note-artifact-confirm-regenerate-${editor.kind.name}"),
                ) { Text("同意并重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text("取消") }
            },
        )
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-artifact-work-${editor.kind.name}")) {
        Column(
            modifier = Modifier.padding(dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
        ) {
            Text(
                text = "${editor.kind.displayName()} · ${editor.workPhase.displayName()}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!editor.workDetail.isNullOrBlank()) {
                Text(
                    text = editor.workDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            editor.problem?.let { problem ->
                PimStatusBanner(
                    text = problem,
                    tone = PimStatusTone.Warning,
                    modifier = Modifier.testTag("note-artifact-problem-${editor.kind.name}"),
                )
            }
            ArtifactFact(
                label = "服务端当前版本",
                value = confirmedHeadVersionLabel(editor),
            )
            ArtifactFact(
                label = "本机编辑基线",
                value = editor.localBaseLabel,
            )
            Button(
                onClick = { confirmRegenerate = true },
                enabled = editor.canRegenerate,
                modifier = Modifier.fillMaxWidth()
                    .testTag("note-artifact-regenerate-${editor.kind.name}"),
            ) {
                Text("重新生成")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = editor.canStop,
                modifier = Modifier.fillMaxWidth()
                    .testTag("note-artifact-stop-${editor.kind.name}"),
            ) {
                Text("停止生成")
            }
            if (editor.canRecover) {
                PimStatusBanner(
                    text = "上次结果${editor.workPhase.displayName()}。系统不会自动重发；请按显式恢复核对原任务标识后再继续。",
                    tone = PimStatusTone.Warning,
                    modifier = Modifier.testTag("note-artifact-recovery-notice-${editor.kind.name}"),
                )
                Button(
                    onClick = onRecover,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("note-artifact-recover-${editor.kind.name}"),
                ) {
                    Text("恢复失败或未知任务")
                }
            }
        }
    }
}

@Composable
private fun ArtifactContentViewSwitch(
    editor: NoteArtifactEditorUiState,
    onViewChanged: (NoteArtifactContentView) -> Unit,
) {
    val dimensions = PimTheme.dimensions
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
    ) {
        Text("查看来源", style = MaterialTheme.typography.titleSmall)
        if (editor.contentView == NoteArtifactContentView.LocalDraft) {
            Button(
                onClick = { onViewChanged(NoteArtifactContentView.LocalDraft) },
                modifier = Modifier.fillMaxWidth().testTag("note-artifact-view-local"),
            ) {
                Text("本机稿")
            }
        } else {
            OutlinedButton(
                onClick = { onViewChanged(NoteArtifactContentView.LocalDraft) },
                modifier = Modifier.fillMaxWidth().testTag("note-artifact-view-local"),
            ) {
                Text("本机稿")
            }
        }
        if (editor.contentView == NoteArtifactContentView.ServerOriginal) {
            Button(
                onClick = { onViewChanged(NoteArtifactContentView.ServerOriginal) },
                modifier = Modifier.fillMaxWidth().testTag("note-artifact-view-server"),
            ) {
                Text("服务端原文")
            }
        } else {
            OutlinedButton(
                onClick = { onViewChanged(NoteArtifactContentView.ServerOriginal) },
                modifier = Modifier.fillMaxWidth().testTag("note-artifact-view-server"),
            ) {
                Text("服务端原文")
            }
        }
        Text(
            text = "两个来源分开显示；服务端原文只读，本机稿可编辑并独立标注未同步状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalDraftPanel(
    editor: NoteArtifactEditorUiState,
    onTextChanged: (NoteArtifactDraftEditRequest) -> Unit,
    onSave: (NoteArtifactDraftSaveRequest) -> Unit,
    onSaveServer: (NoteArtifactDraftSaveRequest) -> Unit,
) {
    val dimensions = PimTheme.dimensions
    val draft = editor.draft
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-artifact-local-${editor.kind.name}")) {
        Column(
            modifier = Modifier.padding(dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceMedium),
        ) {
            Text("本机稿 · 全文编辑", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "本机编辑基线：${editor.localBaseLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("note-artifact-base-${editor.kind.name}"),
            )
            if (draft == null) {
                PimStatusBanner(
                    text = "本机稿尚未准备好。当前不能编辑或保存，请先完成本机草稿加载。",
                    tone = PimStatusTone.Warning,
                    modifier = Modifier.testTag("note-artifact-draft-unavailable-${editor.kind.name}"),
                )
            } else {
                PimStatusBanner(
                    text = if (draft.dirty) {
                        "本机未同步：输入框基于 ${draft.baseServerArtifactVersionId ?: "尚无服务端基线"}。刷新或切换候选不会覆盖这些文字。"
                    } else {
                        "本机稿已保存，尚未同步服务端；编辑后会在本机立即标记为未同步。"
                    },
                    tone = if (draft.dirty) PimStatusTone.Warning else PimStatusTone.Info,
                    modifier = Modifier.testTag("note-artifact-dirty-state-${editor.kind.name}"),
                )
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { text ->
                        onTextChanged(
                            NoteArtifactDraftEditRequest(
                                kind = editor.kind,
                                localDraftId = draft.localDraftId,
                                expectedLocalRevision = draft.localRevision,
                                text = text,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .testTag("note-artifact-editor-${editor.kind.name}"),
                    label = { Text("全文内容（本机稿）") },
                    supportingText = {
                        Text("${draft.text.length} 字符 · 本机版本 ${draft.localRevision}")
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = Int.MAX_VALUE,
                )
                Button(
                    onClick = {
                        onSave(
                            NoteArtifactDraftSaveRequest(
                                kind = editor.kind,
                                localDraftId = draft.localDraftId,
                                expectedLocalRevision = draft.localRevision,
                                text = draft.text,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .testTag("note-artifact-save-${editor.kind.name}"),
                ) {
                    Text(if (draft.dirty) "保存本机稿" else "确认本机稿已保存")
                }
                OutlinedButton(
                    onClick = {
                        onSaveServer(
                            NoteArtifactDraftSaveRequest(
                                kind = editor.kind,
                                localDraftId = draft.localDraftId,
                                expectedLocalRevision = draft.localRevision,
                                text = draft.text,
                            ),
                        )
                    },
                    enabled = editor.canSaveManualRevision,
                    modifier = Modifier.fillMaxWidth()
                        .testTag("note-artifact-save-server-${editor.kind.name}"),
                ) {
                    Text(
                        when {
                            draft.dirty -> "先保存本机稿再同步服务端"
                            editor.confirmedHeadCandidate == null -> "请先选择服务端版本"
                            else -> "保存人工稿到服务端"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerOriginalPanel(editor: NoteArtifactEditorUiState) {
    val dimensions = PimTheme.dimensions
    val candidate = editor.viewedCandidate
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-artifact-server-original-${editor.kind.name}")) {
        Column(
            modifier = Modifier.padding(dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
        ) {
            Text("服务端原文 · 只读", style = MaterialTheme.typography.titleMedium)
            if (candidate == null) {
                Text(
                    text = "尚未选择要查看的服务端候选。请从下方候选列表明确选择“查看服务端原文”。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${candidate.origin.displayName()} · ${candidate.createdAtLabel}",
                    style = MaterialTheme.typography.titleSmall,
                )
                ArtifactFact("服务端版本 ID", candidate.serverVersionId)
                ArtifactFact(
                    "候选基线",
                    candidate.baseServerArtifactVersionId ?: "尚无服务端基线",
                )
                Text(
                    text = "此区域只显示服务端候选原文，不包含本机未同步修改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = candidate.text.ifBlank { "（服务端原文为空）" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(
                            "note-artifact-server-text-${editor.kind.name}-${candidate.candidateId}",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    editor: NoteArtifactEditorUiState,
    candidate: NoteArtifactCandidateUi,
    onViewSource: () -> Unit,
    onSelect: () -> Unit,
) {
    val dimensions = PimTheme.dimensions
    val isCurrent = editor.confirmedHeadCandidateId == candidate.candidateId
    val isPending = editor.isSelectingCandidate(candidate.candidateId)
    OutlinedCard(
        Modifier.fillMaxWidth()
            .semantics {
                stateDescription = when {
                    isCurrent && editor.isDirty -> "服务端当前版本；本机稿有未同步修改"
                    isCurrent -> "服务端当前版本"
                    isPending -> "正在确认选择"
                    else -> "未选用"
                }
            }
            .testTag("note-artifact-candidate-${editor.kind.name}-${candidate.candidateId}"),
    ) {
        Column(
            modifier = Modifier.padding(dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.origin.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(dimensions.iconSize),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "服务端当前版本",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(
                            "note-artifact-current-${editor.kind.name}-${candidate.candidateId}",
                        ),
                    )
                }
            }
            Text(
                text = candidate.createdAtLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ArtifactFact(
                "候选基线",
                candidate.baseServerArtifactVersionId ?: "尚无服务端基线",
            )
            if (candidate.isServerHead) {
                Text(
                    text = "服务端头版本",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (candidate.hasConflict) {
                Text(
                    text = "该候选标记为冲突版本；选择前请核对基线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = candidate.text.ifBlank { "（候选原文为空）" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            if (editor.isDirty) {
                Text(
                    text = "本机稿有未同步修改。选择会请求服务端确认此版本；成功后更新服务端当前版本，不会覆盖输入框或改变本机编辑基线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onViewSource,
                modifier = Modifier.fillMaxWidth()
                    .testTag("note-artifact-view-source-${editor.kind.name}-${candidate.candidateId}"),
            ) {
                Text("查看服务端原文")
            }
            Button(
                onClick = onSelect,
                enabled = editor.canSelectCandidate(candidate.candidateId),
                modifier = Modifier.fillMaxWidth()
                    .testTag("note-artifact-select-${editor.kind.name}-${candidate.candidateId}"),
            ) {
                Text(
                    when {
                        isCurrent -> "服务端当前版本"
                        isPending -> "正在确认选择…"
                        editor.isDirty -> "选为当前版本（保留本机稿）"
                        else -> "选择此稿"
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtifactFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceExtraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun confirmedHeadVersionLabel(editor: NoteArtifactEditorUiState): String {
    val selected = editor.confirmedHeadCandidate
    return when {
        selected != null -> "${selected.origin.displayName()} · ${selected.serverVersionId}"
        !editor.confirmedHeadCandidateId.isNullOrBlank() ->
            "${editor.confirmedHeadCandidateId}（当前候选列表未包含）"
        else -> "尚未确认服务端当前版本"
    }
}

private fun NoteArtifactKind.displayName(): String = when (this) {
    NoteArtifactKind.FullRecord -> "完整记录"
    NoteArtifactKind.Analysis -> "独立分析"
}

private fun NoteArtifactCandidateOrigin.displayName(): String = when (this) {
    NoteArtifactCandidateOrigin.ModelOriginal -> "模型原稿"
    NoteArtifactCandidateOrigin.ManualRevision -> "人工稿"
    NoteArtifactCandidateOrigin.FollowUpCandidate -> "后续候选"
}

private fun NoteArtifactWorkPhase.displayName(): String = when (this) {
    NoteArtifactWorkPhase.Idle -> "尚未生成"
    NoteArtifactWorkPhase.Running -> "生成中"
    NoteArtifactWorkPhase.Ready -> "已有结果"
    NoteArtifactWorkPhase.Failed -> "失败"
    NoteArtifactWorkPhase.Unknown -> "结果未知"
    NoteArtifactWorkPhase.Stopped -> "已停止"
}

private fun NoteArtifactMessageLevel.toStatusTone(): PimStatusTone = when (this) {
    NoteArtifactMessageLevel.Info -> PimStatusTone.Info
    NoteArtifactMessageLevel.Warning -> PimStatusTone.Warning
    NoteArtifactMessageLevel.Error -> PimStatusTone.Error
}

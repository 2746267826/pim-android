package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme

data class NoteArtifactEntryActions(
    val dismiss: () -> Unit = {},
    val refresh: () -> Unit = {},
    val confirm: (NoteArtifactDestination, Set<NoteArtifactKind>) -> Unit = { _, _ -> },
)

/**
 * Explicit destination and charge consent. Nothing is sent until the user
 * presses the confirmation button; refresh and reading the catalogue are free.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NoteArtifactEntrySheet(
    state: NoteArtifactRepositoryState,
    entry: NoteArtifactEntry,
    starting: Boolean,
    actions: NoteArtifactEntryActions,
) {
    val dimensions = PimTheme.dimensions
    var selectedDestinationId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullRecordSelected by rememberSaveable { mutableStateOf(true) }
    var analysisSelected by rememberSaveable { mutableStateOf(true) }
    val selectedKinds = buildSet {
        if (fullRecordSelected) add(NoteArtifactKind.FullRecord)
        if (analysisSelected) add(NoteArtifactKind.Analysis)
    }
    val destination = remember(state.destinations, selectedDestinationId) {
        state.destinations.firstOrNull { it.modelId == selectedDestinationId }
    }
    Surface(
        Modifier.fillMaxSize().testTag("note-artifact-entry"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(dimensions.spaceSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = actions.dismiss,
                    modifier = Modifier.testTag("note-artifact-entry-close"),
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回原页面")
                }
                Column(Modifier.weight(1f)) {
                    Text("生成笔记结果", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "固定材料 · 明确目标 · 人工与模型来源分开",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = actions.refresh,
                    enabled = !state.syncing && !starting,
                    modifier = Modifier.testTag("note-artifact-entry-refresh"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新目标目录")
                }
            }
            if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("note-artifact-entry-list"),
                contentPadding = PaddingValues(
                    start = dimensions.spaceLarge,
                    top = dimensions.spaceMedium,
                    end = dimensions.spaceLarge,
                    bottom = dimensions.spaceExtraLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceMedium),
            ) {
                item {
                    OutlinedCard(Modifier.fillMaxWidth().testTag("note-artifact-entry-source")) {
                        Column(
                            Modifier.padding(dimensions.spaceLarge),
                            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
                        ) {
                            Text(
                                entry.title.ifBlank { "固定材料版本" },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "笔记 ${entry.noteId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "材料版本 ${entry.materialVersionId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (entry.asrTaskId != null) {
                                Text(
                                    "复用已完成的转写 ${entry.asrTaskId.take(8)}，不会重跑转写",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                state.message?.let { problem ->
                    item {
                        PimStatusBanner(
                            problem,
                            PimStatusTone.Warning,
                            Modifier.testTag("note-artifact-entry-problem"),
                        )
                    }
                }
                item {
                    Text("要生成的结果", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSmall)) {
                        FilterChip(
                            selected = fullRecordSelected,
                            onClick = { fullRecordSelected = !fullRecordSelected },
                            label = { Text("完整记录") },
                            modifier = Modifier.testTag("note-artifact-entry-kind-FullRecord"),
                        )
                        FilterChip(
                            selected = analysisSelected,
                            onClick = { analysisSelected = !analysisSelected },
                            label = { Text("独立分析") },
                            modifier = Modifier.testTag("note-artifact-entry-kind-Analysis"),
                        )
                    }
                }
                item {
                    PimStatusBanner(
                        "生成会调用外部模型，可能产生费用。只有点击下方确认才会提交；" +
                            "刷新、查看和上传不会自动调用模型。失败或未知结果不会自动重发。",
                        PimStatusTone.Warning,
                        Modifier.testTag("note-artifact-entry-charge-notice"),
                    )
                }
                item { Text("模型与服务器目标", style = MaterialTheme.typography.titleMedium) }
                if (state.destinations.isEmpty()) {
                    item {
                        PimStatusBanner(
                            state.destinationProblem
                                ?: "当前账号没有可用的派生目标；不能创建收费任务。",
                            PimStatusTone.Error,
                            Modifier.testTag("note-artifact-entry-no-destination"),
                        )
                    }
                }
                items(
                    items = state.destinations,
                    key = { "${it.modelId}:${it.modelRevision}" },
                ) { option ->
                    val selected = destination == option
                    OutlinedCard(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = !starting,
                                onClick = { selectedDestinationId = option.modelId },
                            )
                            .testTag("note-artifact-entry-destination-${option.modelId}"),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(dimensions.spaceLarge),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = !starting,
                            )
                            Spacer(Modifier.padding(horizontal = dimensions.spaceExtraSmall))
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(dimensions.spaceExtraSmall),
                            ) {
                                Text(option.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    option.model,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    option.endpoint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (option.capabilities.isNotEmpty()) {
                                    Text(
                                        option.capabilities.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            val chosen = destination ?: return@Button
                            if (selectedKinds.isEmpty()) return@Button
                            actions.confirm(chosen, selectedKinds)
                        },
                        enabled = destination != null && selectedKinds.isNotEmpty() && !starting,
                        modifier = Modifier.fillMaxWidth().testTag("note-artifact-entry-confirm"),
                    ) {
                        Text(if (starting) "正在提交…" else "同意并开始生成")
                    }
                }
            }
        }
    }
}

package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pim.app.nativework.NativeWorkbenchSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LegacyQuickNoteMigrationActions(
    val open: () -> Unit = {},
    val refresh: () -> Unit = {},
    val page: (Int) -> Unit = {},
    val select: (String) -> Unit = {},
    val confirm: () -> Unit = {},
    val revoke: (String, NativeWorkbenchSession) -> Unit = { _, _ -> },
    val dismissPreview: () -> Unit = {},
    val openNote: (String) -> Unit = {},
    val close: () -> Unit = {},
)

internal fun legacyQuickNoteMigrationActions(
    model: NotesViewModel,
    openNote: (String) -> Unit,
) = LegacyQuickNoteMigrationActions(
    open = model::openLegacyQuickNoteMigration,
    refresh = model::refreshLegacyQuickNoteMigration,
    page = model::loadLegacyQuickNoteMigrationPage,
    select = model::selectLegacyQuickNote,
    confirm = model::confirmLegacyQuickNoteMigration,
    revoke = model::revokeLegacyQuickNoteMigration,
    dismissPreview = model::dismissLegacyQuickNotePreview,
    openNote = { id -> model.openMappedLegacyQuickNoteNote(id, openNote) },
    close = model::closeLegacyQuickNoteMigration,
)

@Composable
internal fun LegacyQuickNoteSourceBlock(block: JsonObject) {
    val content = block["rawContentMarkdown"]?.jsonPrimitive?.content.orEmpty()
    val metadata = block["rawMetadataJson"]?.jsonPrimitive?.content.orEmpty()
    val attachments = block["attachments"]?.jsonArray.orEmpty()
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("legacy-quicknote-readonly-source"),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("旧快速记录来源（只读）", style = MaterialTheme.typography.titleSmall)
            Text("状态 ${block["status"]?.jsonPrimitive?.content.orEmpty()} · 来源 ${block["source"]?.jsonPrimitive?.content.orEmpty()}")
            Text("旧记录 ID ${block["legacyNoteId"]?.jsonPrimitive?.content.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            Text("原始正文", style = MaterialTheme.typography.labelLarge)
            Text(content.take(8_000).ifBlank { "（空正文）" })
            if (content.length > 8_000) Text("界面仅显示前 8,000 个字符；完整原文仍保存在本机来源块。", style = MaterialTheme.typography.bodySmall)
            Text("原始 metadata", style = MaterialTheme.typography.labelLarge)
            Text(metadata.take(4_000).ifBlank { "（空 metadata）" }, style = MaterialTheme.typography.bodySmall)
            if (metadata.length > 4_000) Text("界面仅显示前 4,000 个字符；完整 metadata 仍保存在本机来源块。", style = MaterialTheme.typography.bodySmall)
            Text("附件引用（未下载或复制二进制）", style = MaterialTheme.typography.labelLarge)
            attachments.forEach { element ->
                val attachment = element.jsonObject
                Text(
                    "${attachment["fileName"]?.jsonPrimitive?.content.orEmpty()} · " +
                        "${attachment["contentType"]?.jsonPrimitive?.content.orEmpty()} · " +
                        "${attachment["sizeBytes"]?.jsonPrimitive?.content.orEmpty()} B · ID " +
                        attachment["id"]?.jsonPrimitive?.content.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun LegacyQuickNoteMigrationScreen(
    state: LegacyQuickNoteMigrationState,
    onRefresh: () -> Unit,
    onPage: (Int) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onRevoke: (String, NativeWorkbenchSession) -> Unit,
    onDismissPreview: () -> Unit,
    onOpenNote: (String) -> Unit,
    onClose: () -> Unit,
) {
    var revokeTarget by remember { mutableStateOf<Pair<String, NativeWorkbenchSession>?>(null) }
    LaunchedEffect(state.session) { revokeTarget = null }
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("legacy-migration-close")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回笔记库")
            }
            Column(Modifier.weight(1f)) {
                Text("迁移旧快速记录", style = MaterialTheme.typography.titleLarge)
                Text("只在本机新建副本；不修改来源服务器记录。", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.testTag("legacy-migration-refresh")) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新旧记录")
            }
        }
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "旧 HTML/metadata 原样只读保留在笔记中。附件不会下载或复制，只保存附件 ID 与基本信息；该副本不能作为完整材料上传。",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                )
                if (state.loading) CircularProgressIndicator(Modifier.testTag("legacy-migration-loading"))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("legacy-migration-error")) }
                state.feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("legacy-migration-feedback")) }
                state.migratedNoteId?.let { noteId ->
                    Button(onClick = { onOpenNote(noteId) }, modifier = Modifier.testTag("legacy-migration-open-created")) {
                        Text("打开刚创建的本机副本")
                    }
                }
                if (state.items.isEmpty() && !state.loading && state.error == null) {
                    Text("当前账号和服务器没有可显示的旧记录。", modifier = Modifier.testTag("legacy-migration-empty"))
                }
            }
            items(state.items, key = { it.id }) { item ->
                val mapped = state.links.firstOrNull { it.legacyNoteId == item.id }
                OutlinedCard(
                    onClick = { onSelect(item.id) },
                    enabled = !state.loading && !state.confirming,
                    modifier = Modifier.fillMaxWidth().testTag("legacy-migration-item-${item.id}"),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${item.status} · ${item.source}", style = MaterialTheme.typography.labelMedium)
                        Text(item.contentPreview.ifBlank { "（空正文）" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("附件 ${item.attachmentCount} 个 · 更新于 ${item.updatedAt}", style = MaterialTheme.typography.bodySmall)
                        if (mapped != null) {
                            Text("已映射到本机笔记 ${mapped.noteId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { onOpenNote(mapped.noteId) }, modifier = Modifier.testTag("legacy-migration-open-${item.id}")) {
                                    Text("打开本机副本")
                                }
                                TextButton(
                                    onClick = { state.session?.let { revokeTarget = item.id to it } },
                                    enabled = state.session != null && !state.loading && !state.confirming,
                                    modifier = Modifier.testTag("legacy-migration-revoke-${item.id}"),
                                ) {
                                    Text("撤回映射（保留双方记录）")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${state.page} / ${state.totalPages} 页")
                    Row {
                        TextButton(onClick = { onPage(state.page - 1) }, enabled = state.page > 1 && !state.loading) { Text("上一页") }
                        TextButton(onClick = { onPage(state.page + 1) }, enabled = state.page < state.totalPages && !state.loading) { Text("下一页") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    state.selected?.let { detail ->
        val html = detail.isLegacyQuickNoteHtml()
        AlertDialog(
            onDismissRequest = { if (!state.confirming) onDismissPreview() },
            title = { Text("迁移前预览 · ${detail.status}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原始内容（只读）", style = MaterialTheme.typography.labelLarge)
                    Text(
                        detail.contentMarkdown.take(4_000).ifBlank { "（空正文）" } +
                            if (detail.contentMarkdown.length > 4_000) "\n…预览已截断；迁移保留完整正文。" else "",
                        modifier = Modifier.testTag("legacy-migration-preview-content"),
                    )
                    Text("metadata 原样保留；状态与来源也写入本机笔记。", style = MaterialTheme.typography.bodySmall)
                    if (detail.attachments.isNotEmpty()) {
                        Text("附件仅引用，不复制二进制：", style = MaterialTheme.typography.labelLarge)
                        detail.attachments.take(10).forEach { Text("${it.fileName} · ${it.contentType} · ${it.sizeBytes} B · ID ${it.id}") }
                        if (detail.attachments.size > 10) Text("另有 ${detail.attachments.size - 10} 个附件引用")
                    }
                    if (html) Text("识别为旧 HTML：不会转换或覆盖原文；副本正文暂留空白。", color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    enabled = !state.confirming && !state.loading,
                    modifier = Modifier.testTag("legacy-migration-confirm"),
                ) { Text(if (state.confirming) "正在本机保存…" else "确认创建本机副本") }
            },
            dismissButton = { TextButton(onClick = { if (!state.confirming) onDismissPreview() }) { Text("取消") } },
            modifier = Modifier.testTag("legacy-migration-confirm-dialog"),
        )
    }

    revokeTarget?.let { (id, expectedSession) ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("撤回迁移映射？") },
            text = { Text("只移除本机对应关系。旧快速记录和已创建的原生笔记都会保留。之后再次迁移会复用同一稳定本机笔记 ID。") },
            confirmButton = {
                Button(
                    onClick = {
                        if (state.session != expectedSession) return@Button
                        revokeTarget = null
                        onRevoke(id, expectedSession)
                    },
                    enabled = state.session == expectedSession && !state.loading && !state.confirming,
                    modifier = Modifier.testTag("legacy-migration-revoke-confirm"),
                ) { Text("撤回映射") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("取消") } },
        )
    }
}

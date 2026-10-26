package com.pim.app.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.pim.app.recording.RecordingState
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteLibraryScreen(
    state: NotesState, selectedId: String?, recording: RecordingState, busy: Boolean,
    onCreate: () -> Unit, onOpen: (String?) -> Unit, onExit: () -> Unit
) {
    val d = PimTheme.dimensions
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.align(Alignment.TopCenter).widthIn(max = d.noteDocumentMaxWidth).fillMaxSize().testTag("notes-library"),
            contentPadding = PaddingValues(start = d.spaceLarge, end = d.spaceLarge, bottom = d.noteDockClearance),
            verticalArrangement = Arrangement.spacedBy(d.spaceMedium)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onExit) { Icon(Icons.Default.ArrowBack, "返回今日") }
                    Spacer(Modifier.weight(1f))
                    Text("本机 · 离线记录", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.padding(vertical = d.spaceLarge)) {
                    Text("笔记", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(d.spaceSmall))
                    Text(if (state.entries.isEmpty()) "留住课堂、会议与每一个想法" else "${state.entries.size} 篇记录，保存在这台设备",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PimStatusBanner("本地预览版支持笔记、照片、手写和录音；上传、转写与 AI 分析尚未接入。", PimStatusTone.Info)
            }
            if (state.loadFailed) item { PimStatusBanner("本地记录读取失败，原文件保留。请重新打开页面。", PimStatusTone.Error) }
            if (selectedId != null) item { PimStatusBanner("该记录仍在加载或无法读取，不会用空白文档覆盖它。", PimStatusTone.Warning) }
            recording.session?.takeIf { it.active && it.noteId != null }?.let { session ->
                item {
                    OutlinedCard(onClick = { onOpen(session.noteId) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(d.spaceLarge), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
                            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("返回正在录音的笔记", style = MaterialTheme.typography.titleSmall)
                                Text("离开编辑页不会结束录音", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
            if (!state.loaded && !state.loadFailed) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.loaded && state.entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = d.noteHeaderClearance), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(Icons.Default.EditNote, null, Modifier.padding(d.spaceExtraLarge).size(d.minimumTouchTarget),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(d.spaceExtraLarge))
                    Text("从一页空白开始", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(d.spaceSmall))
                    Text("写笔记、拍照片，也可以随手写下来。\n需要时再开启录音，全程可以离线。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                val note = state.documents[entry.id]
                OutlinedCard(
                    onClick = { onOpen(entry.id) }, enabled = !entry.damaged && !busy,
                    modifier = Modifier.fillMaxWidth().testTag("note-open-${entry.id}"),
                    border = BorderStroke(d.borderWidth, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                            Icon(Icons.Default.Description, null, Modifier.size(d.iconSize), tint = MaterialTheme.colorScheme.primary)
                            Text(entry.title.ifBlank { "未命名记录" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val snippet = note?.blocks?.firstOrNull { it.string("kind") == "markdown" && it.string("text").isNotBlank() }
                            ?.string("text")?.replace('\n', ' ')?.take(180)
                        if (snippet != null) Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (entry.damaged) "无法读取，原材料保留" else buildString {
                            append(note?.let(::noteDate).orEmpty())
                            val photos = note?.blocks?.count { it.string("kind") == "photo" } ?: 0
                            val inks = note?.blocks?.count { it.string("kind") == "ink" } ?: 0
                            if (photos > 0) append(" · $photos 张照片")
                            if (inks > 0) append(" · $inks 张手写")
                        }, style = MaterialTheme.typography.bodySmall,
                            color = if (entry.damaged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (state.loaded && !busy) onCreate() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(d.spaceLarge).testTag("notes-create")
                .semantics { if (!state.loaded || busy) disabled() },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text(if (busy) "处理中" else "新建记录") },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }
}

internal fun noteDate(note: NoteDocument): String = (note.source["createdAtMillis"] as? JsonPrimitive)?.longOrNull?.let {
    SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(it))
}.orEmpty()

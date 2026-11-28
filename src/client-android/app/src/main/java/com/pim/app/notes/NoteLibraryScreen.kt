package com.pim.app.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onCreate: () -> Unit, onOpen: (String?) -> Unit, onExit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    uploads: NoteUploadsState = NoteUploadsState(),
    currentTarget: NoteUploadTarget? = null,
    autoSubmitAuthorization: NoteAutoSubmitAuthorization? = null,
    onEnableAutoSubmit: (NoteUploadTarget) -> Unit = {}, onRevokeAutoSubmit: () -> Unit = {},
    onResumeUpload: (String) -> Unit = {}, onPauseUpload: (String) -> Unit = {},
    asrTaskCount: Int = 0, onAsr: () -> Unit = {}, onImport: () -> Unit = {},
    onLegacyQuickNotes: () -> Unit = {},
    cloudNotes: CloudNotesState = CloudNotesState(), onRefreshCloudNotes: () -> Unit = {},
    onOpenCloudNote: (String) -> Unit = {},
    onRetryLoad: () -> Unit = {},
) {
    val d = PimTheme.dimensions
    var search by rememberSaveable { mutableStateOf("") }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showTopMore by rememberSaveable { mutableStateOf(false) }
    var showUploadHistory by rememberSaveable { mutableStateOf(false) }
    var uploadHistoryNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudBusyId by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudRequestStarted by remember { mutableStateOf(false) }
    LaunchedEffect(busy, cloudBusyId) {
        if (cloudBusyId == null) cloudRequestStarted = false
        else if (busy) cloudRequestStarted = true
        else if (cloudRequestStarted) cloudBusyId = null
    }
    val entries = state.entries.filter { entry -> search.isBlank() || entry.title.contains(search, true) ||
        state.documents[entry.id]?.blocks?.any { it.string("kind") == "markdown" && it.string("text").contains(search, true) } == true }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = d.noteDocumentMaxWidth).weight(1f).fillMaxWidth().testTag("notes-library"),
            contentPadding = PaddingValues(start = d.spaceLarge, end = d.spaceLarge, bottom = d.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(d.spaceMedium)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onExit != null) {
                        IconButton(onClick = onExit) { Icon(Icons.Default.ArrowBack, "返回应用") }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(
                            onClick = { showTopMore = true },
                            modifier = Modifier.testTag("notes-top-more"),
                        ) { Icon(Icons.Default.MoreHoriz, "更多") }
                        DropdownMenu(expanded = showTopMore, onDismissRequest = { showTopMore = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when {
                                            uploads.loadFailed -> "上传记录 · 读取失败"
                                            uploads.jobs.isNotEmpty() -> "上传记录 · ${uploads.jobs.size} 个"
                                            else -> "上传记录"
                                        },
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                                modifier = Modifier.testTag("notes-upload-history-open"),
                                onClick = {
                                    showTopMore = false
                                    uploadHistoryNoteId = null
                                    showUploadHistory = true
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(d.spaceSmall))
                if (state.importQuarantine.retainedCount > 0) {
                    val retainedMiB = (state.importQuarantine.retainedBytes + 1024 * 1024 - 1) / (1024 * 1024)
                    PimStatusBanner(
                        "失败导入包保留 ${state.importQuarantine.retainedCount} 个，共 $retainedMiB MiB。" +
                            "失败包最多保留 ${NoteFileStore.MAX_QUARANTINE_ENTRIES} 个、" +
                            "${NoteFileStore.MAX_QUARANTINE_BYTES / (1024 * 1024)} MiB；" +
                            "超限时只回收最旧的失败导入包，不删除任何已保存笔记。",
                        PimStatusTone.Warning,
                    )
                }
                Spacer(Modifier.height(d.spaceMedium))
                OutlinedTextField(search, { search = it }, label = { Text("搜索本机笔记") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("notes-search"))
            }
            if (state.loadFailed) item {
                Column(Modifier.testTag("notes-load-error"), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    PimStatusBanner(
                        "本地记录读取失败：${state.loadError ?: "未知原因"}。原文件保留，可以重试读取，不会用空白文档覆盖已有记录。",
                        PimStatusTone.Error,
                    )
                    TextButton(onClick = onRetryLoad, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("notes-load-retry")) { Text("重试读取本机记录") }
                }
            }
            if (state.loaded && state.refreshing) item {
                Row(Modifier.fillMaxWidth().testTag("notes-refreshing"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    CircularProgressIndicator(Modifier.size(d.iconSize), strokeWidth = 2.dp)
                    Text("正在更新本机记录…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loaded && state.refreshFailed) item {
                Column(Modifier.testTag("notes-refresh-error"), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    PimStatusBanner(
                        "列表更新失败：${state.refreshError ?: "未知原因"}。已有记录和当前浏览位置已保留，可以原处重试。",
                        PimStatusTone.Warning,
                    )
                    TextButton(onClick = onRetryLoad, enabled = !busy && !state.refreshing,
                        modifier = Modifier.fillMaxWidth().testTag("notes-refresh-retry")) { Text("重试更新列表") }
                }
            }
            if (selectedId != null) item { PimStatusBanner("该记录仍在加载或无法读取，不会用空白文档覆盖它。", PimStatusTone.Warning) }
            recording.session?.takeIf { it.active && it.noteId != null }?.let { session ->
                val title = session.noteId?.let { noteDisplayTitle(state, it) } ?: "未命名记录"
                item {
                    OutlinedCard(onClick = { onOpen(session.noteId) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(d.spaceLarge), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
                            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("正在录音：$title", style = MaterialTheme.typography.titleSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text("点按返回原笔记，或继续在列表中浏览；离开编辑页不会结束录音",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
            if (!state.loaded && !state.loadFailed) item {
                Column(Modifier.fillMaxWidth().testTag("notes-loading"), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    Text("正在读取本机记录…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (state.loaded && state.entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = d.noteHeaderClearance).testTag("notes-empty"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(Icons.Default.EditNote, null, Modifier.padding(d.spaceExtraLarge).size(d.minimumTouchTarget),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(d.spaceExtraLarge))
                    Text("还没有笔记", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(d.spaceSmall))
                    Text("从一页空白开始：写笔记、拍照片，也可以随手写下来。\n需要时再开启录音，全程可以离线。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loaded && entries.isEmpty() && state.entries.isNotEmpty()) item {
                Text("没有匹配的笔记", Modifier.testTag("notes-no-results"), style = MaterialTheme.typography.bodyMedium)
            }
            items(entries, key = { it.id }) { entry ->
                val note = state.documents[entry.id]
                val uploadNotice = noteUploadNotice(latestUploadJobForNote(uploads.jobs, entry.id))
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
                        if (uploadNotice != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    uploadNotice.label,
                                    Modifier.weight(1f).testTag("note-upload-notice-${entry.id}"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (uploadNotice.tone) {
                                        NoteUploadNoticeTone.Error -> MaterialTheme.colorScheme.error
                                        NoteUploadNoticeTone.Warning -> PimTheme.statusColors.onWarningContainer
                                        NoteUploadNoticeTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = {
                                        uploadHistoryNoteId = entry.id
                                        showUploadHistory = true
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.testTag("note-upload-issue-${entry.id}"),
                                ) { Text("查看上传记录") }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(d.spaceMedium))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("云端笔记", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onRefreshCloudNotes, enabled = !cloudNotes.loading && !busy) {
                        Text(if (cloudNotes.loading) "正在刷新" else "刷新")
                    }
                }
                Text("列表会自动查找云端副本；点选后才下载并校验到本机。本机已有同一笔记时不会自动覆盖。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cloudNotes.problem?.let { problem ->
                    PimStatusBanner(problem, if (cloudNotes.unsupported) PimStatusTone.Info else PimStatusTone.Warning)
                }
                if (cloudNotes.loading && cloudNotes.entries.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                cloudNotes.entries.forEach { cloud ->
                    OutlinedCard(
                        onClick = {
                            cloudRequestStarted = false
                            cloudBusyId = cloud.summary.noteId
                            onOpenCloudNote(cloud.summary.noteId)
                        },
                        enabled = !busy && cloudBusyId == null && cloud.problem == null,
                        modifier = Modifier.fillMaxWidth().testTag("cloud-note-open-${cloud.summary.noteId}"),
                    ) {
                        Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                            Text(cloud.summary.title.ifBlank { "未命名云端笔记" }, style = MaterialTheme.typography.titleMedium)
                            Text(cloud.problem ?: if (cloudBusyId == cloud.summary.noteId) "正在安全下载并校验材料…" else "点选后下载到本机笔记库",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (cloudNotes.loaded && cloudNotes.entries.isEmpty() && cloudNotes.problem == null) {
                    Text("暂无云端笔记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NoteAutoSubmitControl(currentTarget, autoSubmitAuthorization, onEnableAutoSubmit, onRevokeAutoSubmit)
                TextButton(onClick = { showMore = !showMore }, modifier = Modifier.fillMaxWidth().testTag("notes-more-toggle")) {
                    Text(if (showMore) "收起更多功能" else "更多功能")
                }
                if (showMore) {
                    OutlinedButton(onClick = onAsr, modifier = Modifier.fillMaxWidth().testTag("note-asr-open")) {
                        Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(d.spaceSmall))
                        Text(if (asrTaskCount > 0) "逐段转写任务 · $asrTaskCount 个" else "逐段转写任务")
                    }
                    Spacer(Modifier.height(d.spaceSmall))
                    OutlinedButton(onClick = onLegacyQuickNotes,
                        modifier = Modifier.fillMaxWidth().testTag("legacy-quicknote-migration-open")) {
                        Icon(Icons.Default.MoveToInbox, null); Spacer(Modifier.width(d.spaceSmall))
                        Text("迁移旧快速记录到本机笔记")
                    }
                    Spacer(Modifier.height(d.spaceSmall))
                    Text("离线文档包仅用于文件交换、备份或恢复旧包，不是日常云端笔记列表的同步步骤。包内可能含录音；网页端导入不支持录音，不能作为通用互导格式。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(d.spaceSmall))
                    OutlinedButton(onClick = { if (!busy) onImport() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("note-import-package")) {
                        Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(d.spaceSmall))
                        Text("导入可编辑文档包")
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (state.loaded && !busy) onCreate() },
            modifier = Modifier.align(Alignment.End).padding(horizontal = d.spaceLarge, vertical = d.spaceSmall).testTag("notes-create")
                .semantics { if (!state.loaded || busy) disabled() },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text(if (busy) "处理中" else "新建记录") },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary
        )
    }
    if (showUploadHistory) {
        NoteUploadHistoryDialog(
            state = uploads,
            target = currentTarget,
            focusNoteId = uploadHistoryNoteId,
            focusTitle = uploadHistoryNoteId?.let { noteDisplayTitle(state, it) },
            onResume = onResumeUpload,
            onPause = onPauseUpload,
            onShowAll = { uploadHistoryNoteId = null },
            onDismiss = { showUploadHistory = false },
        )
    }
}

internal fun noteDate(note: NoteDocument): String = (note.source["createdAtMillis"] as? JsonPrimitive)?.longOrNull?.let {
    SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(it))
}.orEmpty()

internal fun noteDisplayTitle(state: NotesState, id: String): String =
    state.documents[id]?.title?.takeIf { it.isNotBlank() }
        ?: state.entries.firstOrNull { it.id == id }?.title?.takeIf { it.isNotBlank() }
        ?: "未命名记录"

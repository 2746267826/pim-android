package com.pim.app.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SouthEast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pim.app.recording.*
import com.pim.app.ui.components.PimButton
import com.pim.app.ui.components.PimRecordingPill
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeNotesScreen(
    state: NotesState, selectedId: String?, recording: RecordingState, busy: Boolean, message: String?, playing: String?, notificationsVisible: Boolean,
    onCreate: () -> Unit, onOpen: (String?) -> Unit,
    onChange: (String, (NoteDocument) -> NoteDocument) -> Unit, onRetry: (String) -> Unit,
    photoFile: (String, String) -> File,
    onInsertPhoto: (String, NoteInsertion, Boolean) -> Unit, onRecord: (String) -> Unit,
    onPauseResume: () -> Unit, onFinish: (String) -> Unit,
    onPlay: (RecordingSession, Int) -> Unit, onExport: (NoteDocument) -> Unit
) {
    val d = PimTheme.dimensions
    val note = state.documents[selectedId]
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (note == null) {
            LazyColumn(Modifier.fillMaxSize().padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本机记录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        PimButton(onCreate, enabled = state.loaded && !busy, modifier = Modifier.testTag("notes-create")) { Text("新建") }
                    }
                    Text("本地预览版 · 离线编辑与录音 · 尚未接入上传、转写和分析", style = MaterialTheme.typography.bodySmall)
                    if (state.loadFailed) Text("本地材料读取失败，原文件保留；请重新打开页面", color = MaterialTheme.colorScheme.error)
                    if (selectedId != null) Text("该记录不可读取或仍在加载，不会用空白文档覆盖它")
                    if (recording.active) Text("录音仍在后台进行，离开页面不会结束录音")
                }
                items(state.entries, key = { it.id }) { entry ->
                    OutlinedButton(onClick = { onOpen(entry.id) }, enabled = !entry.damaged && !busy, modifier = Modifier.fillMaxWidth().testTag("note-open-${entry.id}")) {
                        Text(entry.title, maxLines = 2)
                    }
                    if (entry.damaged) Text("无法读取，原材料保留；未进行自动修复或删除", color = MaterialTheme.colorScheme.error)
                }
                if (state.loaded && state.entries.isEmpty()) item { Text("新建一条记录，写下正文，再插入照片或手写卡片。录音需要单独点击开始。") }
            }
        } else {
            key(note.id) {
                var preview by rememberSaveable { mutableStateOf(false) }
                var selectedBlock by rememberSaveable { mutableStateOf<String?>(null) }
                var selectedOffset by rememberSaveable { mutableStateOf(0) }
                var menu by remember { mutableStateOf(false) }
                val saved = state.savedRevisions[note.id] == note.revision
                val ownSession = recording.session?.takeIf { it.noteId == note.id }
                val ownActive = ownSession?.active == true
                val lastMarkdown = note.blocks.lastOrNull { it.string("kind") == "markdown" }
                val anchor = NoteInsertion(selectedBlock ?: lastMarkdown?.string("id").orEmpty(), if (selectedBlock == null) lastMarkdown?.string("text")?.length ?: 0 else selectedOffset)
                Box(Modifier.fillMaxSize().imePadding()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = d.spaceLarge)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = d.spaceSmall), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onOpen(null) }, enabled = !busy) { Icon(Icons.Filled.ArrowBack, "返回记录列表") }
                            OutlinedTextField(note.title, { text -> onChange(note.id) { it.withTitle(text.take(500)) } },
                                modifier = Modifier.weight(1f).testTag("note-title"), singleLine = true, enabled = !busy,
                                textStyle = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(d.spaceSmall))
                            PimButton(onClick = { onFinish(note.id) }, enabled = !busy, modifier = Modifier.testTag("note-finish")) { Text("结束") }
                        }
                        Text(when {
                            note.id in state.errors -> "保存失败，当前更改尚未可靠落盘"
                            saved -> "已保存本机 · 尚未接入云处理"
                            else -> "正在保存本机，请勿强制停止应用"
                        }, color = if (note.id in state.errors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("note-save-status"))
                        if (note.id in state.errors) TextButton(onClick = { onRetry(note.id) }) { Text("重试本地保存") }
                        if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("note-message"))
                        recording.problem?.takeIf { ownSession != null }?.let { PimStatusBanner(it.message, PimStatusTone.Error) }
                        if (ownActive && !notificationsVisible) Text("系统通知权限或录音渠道已关闭，后台状态可能不可见", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        if (recording.active && !ownActive) Text("另一个条目正在录音；本页编辑不会改变其归属", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onInsertPhoto(note.id, anchor, true) }, enabled = !busy && !preview && lastMarkdown != null) { Text("拍照") }
                            TextButton(onClick = { onInsertPhoto(note.id, anchor, false) }, enabled = !busy && !preview && lastMarkdown != null,
                                modifier = Modifier.testTag("note-insert-photo")) { Text("照片") }
                            TextButton(onClick = { onChange(note.id) { it.insert(anchor, inkBlock(320f)) } },
                                enabled = !busy && !preview && lastMarkdown != null, modifier = Modifier.testTag("note-insert-ink")) { Text("手写") }
                            TextButton(onClick = { preview = !preview }, modifier = Modifier.testTag("note-preview")) { Text(if (preview) "编辑" else "预览") }
                            if (!ownActive) TextButton(onClick = { onRecord(note.id) }, enabled = saved && !busy && !recording.active && !recording.preparing,
                                modifier = Modifier.testTag("note-start-recording")) { Text("录音") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多文档操作") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("导出文档包（不含录音）") }, enabled = !busy && saved,
                                        onClick = { menu = false; onExport(note) })
                                }
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize().testTag("note-document"), verticalArrangement = Arrangement.spacedBy(d.spaceMedium),
                            contentPadding = PaddingValues(bottom = d.minimumTouchTarget + d.spaceExtraLarge)) {
                            items(note.blocks, key = { it.string("id") }) { block ->
                                when (block.string("kind")) {
                                    "markdown" -> MarkdownBlockView(block, preview, !busy,
                                        onText = { text -> onChange(note.id) { doc -> doc.replaceBlock(block.string("id")) { JsonObject(it + ("text" to JsonPrimitive(text))) } } },
                                        onSelection = { offset -> selectedBlock = block.string("id"); selectedOffset = offset })
                                    "photo" -> PhotoBlockView(photoFile(note.id, block.string("assetId")))
                                    "ink" -> InkCard(block.string("id"), inkOf(block), preview || busy) { ink ->
                                        onChange(note.id) { doc -> doc.replaceBlock(block.string("id")) { withInk(it, ink) } }
                                    }
                                    else -> PimStatusBanner("未知扩展块已保留，当前版本不能编辑它", PimStatusTone.Warning)
                                }
                            }
                            val sessions = (recording.archives.mapNotNull { it.session } + listOfNotNull(ownSession)).distinctBy { it.id }
                                .filter { it.noteId == note.id }.sortedBy { it.createdAtMillis }
                            if (sessions.isNotEmpty()) item {
                                Text("本篇录音 · 正式转写尚未接入", style = MaterialTheme.typography.titleSmall)
                                sessions.forEachIndexed { index, session ->
                                    Text("录音 ${index + 1} · ${recordingDuration(session.durationMillis)} · ${when (session.phase) {
                                        RecordingPhase.Recording -> "录音中"; RecordingPhase.Paused -> "暂停中"
                                        RecordingPhase.Completed -> "已保存本机"; RecordingPhase.Interrupted -> "已中断，原材料保留"
                                    }}", style = MaterialTheme.typography.bodySmall)
                                    session.chunks.map { it.segment }.distinct().forEach { segment ->
                                        TextButton(onClick = { onPlay(session, segment) }, enabled = !recording.active && session.problem != RecordingProblem.Damaged) {
                                            Text(if (playing == "${session.id}:$segment") "停止回放" else "录音 ${index + 1} · 回放第 $segment 段")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (ownActive) PimRecordingPill(recording, onPauseResume,
                        Modifier.align(Alignment.BottomStart).padding(d.spaceLarge))
                }
            }
        }
    }
}

@Composable
private fun PhotoBlockView(file: File) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { NoteFileStore.photoPreview(file) }.getOrNull() }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), "笔记照片", Modifier.fillMaxWidth().heightIn(max = PimTheme.dimensions.notePhotoMaxHeight), contentScale = ContentScale.Fit)
    else Text("照片读取中或原文件不可读取；不会删除原材料", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun InkCard(id: String, ink: InkData, readOnly: Boolean, onChange: (InkData) -> Unit) {
    val d = PimTheme.dimensions
    var draw by rememberSaveable(id) { mutableStateOf(false) }
    var erase by rememberSaveable(id) { mutableStateOf(false) }
    var stylusOnly by rememberSaveable(id) { mutableStateOf(false) }
    var history by remember(id) { mutableStateOf(emptyList<InkData>()) }
    var resizing by remember { mutableStateOf<InkData?>(null) }
    val shown = resizing ?: ink
    val inkColor = MaterialTheme.colorScheme.primary.toArgb()
    val density = LocalDensity.current.density
    val commit: (InkData) -> Unit = { next ->
        if (next != ink) { history = (history + ink).takeLast(100); onChange(next) }
    }
    Column(Modifier.fillMaxWidth().testTag("ink-card-$id")) {
        if (!readOnly) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("手写", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { draw = !draw }, modifier = Modifier.testTag("ink-draw-$id")) { Text(if (draw) "完成书写" else "书写") }
            TextButton(onClick = { erase = !erase; draw = true }) { Text(if (erase) "改用笔" else "整笔擦除") }
            TextButton(onClick = { stylusOnly = !stylusOnly }) { Text(if (stylusOnly) "仅手写笔" else "手指和笔") }
            TextButton(onClick = {
                if (history.isNotEmpty()) { val previous = history.last(); history = history.dropLast(1); onChange(previous) }
                else onChange(ink.copy(strokes = ink.strokes.dropLast(1)))
            }, enabled = history.isNotEmpty() || ink.strokes.isNotEmpty(),
                modifier = Modifier.testTag("ink-undo-$id")) { Text(if (history.isNotEmpty()) "撤销" else "移除末笔") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = maxWidth.value
            val scale = minOf(1f, available / shown.width)
            Box(Modifier.width((shown.width * scale).dp).height((shown.height * scale).dp)
                .border(d.borderWidth, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)) {
                AndroidView(factory = { context -> InkInputView(context) }, modifier = Modifier.fillMaxSize().testTag("ink-canvas-$id"), update = {
                    it.ink = shown; it.drawingEnabled = draw && !readOnly && resizing == null; it.eraseEnabled = erase
                    it.stylusOnly = stylusOnly
                    it.inkColor = inkColor; it.onInkChanged = commit
                })
                if (!readOnly) Box(Modifier.align(Alignment.BottomEnd).size(d.minimumTouchTarget)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                    .testTag("ink-resize-$id")
                    .pointerInput(id, ink, available) {
                        detectDragGestures(onDragStart = { resizing = ink }, onDragCancel = { resizing = null },
                            onDragEnd = { resizing?.let(commit); resizing = null }) { change, delta ->
                            change.consume()
                            val current = resizing ?: ink
                            val currentScale = minOf(1f, available / current.width)
                            resizing = current.resized(current.width + delta.x / density / currentScale,
                                current.height + delta.y / density / currentScale, available)
                        }
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.SouthEast, "拖动右下角调整手写卡片宽高", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        if (draw && !readOnly) Text("书写时画布内不滚动；点击“完成书写”恢复滚动。缩小不会裁掉已有笔迹。", style = MaterialTheme.typography.bodySmall)
    }
}

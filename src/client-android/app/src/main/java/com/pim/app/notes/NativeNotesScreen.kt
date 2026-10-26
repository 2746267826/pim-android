package com.pim.app.notes

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pim.app.recording.*
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NativeNotesScreen(
    state: NotesState, selectedId: String?, recording: RecordingState, busy: Boolean, message: String?, playing: String?, notificationsVisible: Boolean,
    materials: NoteMaterialsState,
    onCreate: () -> Unit, onOpen: (String?) -> Unit,
    readDocument: (String) -> NoteDocument?, onProblem: (String) -> Unit,
    onChange: (String, (NoteDocument) -> NoteDocument) -> Unit, onRetry: (String) -> Unit,
    photoFile: (String, String) -> File,
    onInsertPhoto: (String, NoteInsertion, Boolean) -> Unit, onRecord: (String) -> Unit,
    onPauseResume: () -> Unit, onFinish: (String) -> Unit,
    onPlay: (RecordingSession, Int) -> Unit, onExport: (NoteDocument) -> Unit,
    onRetryMaterial: (String) -> Unit, onExportMaterial: (String) -> Unit,
    onExit: () -> Unit = {}, onStopRecording: () -> Unit = {},
    completion: NoteCompletion? = null, onDismissCompletion: () -> Unit = {}, onDismissMessage: (String) -> Unit = {}
) {
    val d = PimTheme.dimensions
    val note = state.documents[selectedId]
    BackHandler(busy && note == null) { onProblem("正在处理本地材料，请稍候") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = if (it.length > 60) SnackbarDuration.Long else SnackbarDuration.Short)
            onDismissMessage(it)
        }
    }
    Surface(Modifier.fillMaxSize(), color = if (note == null) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().imePadding()) {
            if (note == null) {
                NoteLibraryScreen(state, selectedId, recording, busy, onCreate, onOpen, onExit)
            } else key(note.id) {
                var preview by rememberSaveable { mutableStateOf(false) }
                val editor = remember { NoteEditorCoordinator() }
                var committing by remember { mutableStateOf(false) }
                var showMaterials by rememberSaveable { mutableStateOf(false) }
                var showRecordings by rememberSaveable { mutableStateOf(false) }
                var confirmFinish by rememberSaveable { mutableStateOf(false) }
                var showAbout by rememberSaveable { mutableStateOf(false) }
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val list = rememberLazyListState()
                var viewportSize by remember { mutableStateOf(IntSize.Zero) }
                val scope = rememberCoroutineScope()
                val saved = state.savedRevisions[note.id] == note.revision && !editor.pending && !committing
                val ownSession = recording.session?.takeIf { it.noteId == note.id }
                val ownActive = ownSession?.active == true
                val sessions = (listOfNotNull(ownSession) + recording.archives.mapNotNull { it.session }).distinctBy { it.id }
                    .filter { it.noteId == note.id }.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
                val latest = materials.entries.firstOrNull { it.noteId == note.id }
                val lastMarkdown = note.blocks.lastOrNull { it.string("kind") == "markdown" }
                val showTextTools = editor.activeRichBlock != null && !preview && !busy && !committing
                val dockClearance = d.noteDockClearance + if (showTextTools) d.minimumTouchTarget + d.spaceSmall else 0.dp
                fun leaveInput() { keyboard?.hide(); focus.clearFocus() }
                fun afterInput(action: (NoteDocument) -> Unit) {
                    if (busy || committing) return
                    leaveInput(); committing = true
                    scope.launch {
                        try { editor.flush(); action(checkNotNull(readDocument(note.id))) }
                        catch (_: Exception) { onProblem("尚未确认正文输入或操作完成，原材料保留；请核对内容后重试。") }
                        finally { editor.resume(); committing = false }
                    }
                }
                BackHandler {
                    if (busy || committing) onProblem("正在保存正文或处理本地材料，请稍候")
                    else afterInput { onOpen(null) }
                }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= d.noteWideBreakpoint
                    // Constrain the viewport too: content padding alone lets the caret scroll behind the dock.
                    Row(Modifier.fillMaxSize().padding(top = d.noteHeaderClearance, bottom = dockClearance)) {
                        if (wide) NoteMaterialsRail(note, sessions, { leaveInput(); showRecordings = true }, { leaveInput(); showMaterials = true }) { id ->
                            val index = note.blocks.indexOfFirst { it.string("id") == id }
                            if (index >= 0) scope.launch { list.animateScrollToItem(index + 2) }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            LazyColumn(
                                Modifier.widthIn(max = d.noteDocumentMaxWidth).fillMaxSize().onSizeChanged { viewportSize = it }.testTag("note-document"), state = list,
                                contentPadding = PaddingValues(start = d.spaceLarge, end = d.spaceLarge, bottom = d.spaceLarge),
                                verticalArrangement = Arrangement.spacedBy(d.spaceMedium)
                            ) {
                                item(key = "heading") {
                                    NoteDocumentHeading(note, !busy && !committing, preview, latest, latest?.matches(note, sessions) == false, sessions.size,
                                        onTitle = { title -> onChange(note.id) { it.withTitle(title) } },
                                        onMaterials = { leaveInput(); showMaterials = true }, onRecordings = { leaveInput(); showRecordings = true })
                                }
                                item(key = "alerts") {
                                    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                                        if (note.id in state.errors) {
                                            PimStatusBanner("保存失败，当前更改尚未可靠落盘。请检查存储空间后重试。", PimStatusTone.Error)
                                            TextButton(onClick = { onRetry(note.id) }) { Text("重试本地保存") }
                                        }
                                        recording.problem?.takeIf { ownSession != null }?.let { PimStatusBanner(it.message, PimStatusTone.Error) }
                                        if (ownActive && !notificationsVisible) PimStatusBanner("系统通知权限或录音渠道已关闭，后台录音状态可能不可见。", PimStatusTone.Warning)
                                        if (recording.active && !ownActive) PimStatusBanner("另一篇笔记正在录音，本页编辑不会改变录音归属。", PimStatusTone.Info)
                                        if (materials.loadFailed) PimStatusBanner("材料版本暂时无法读取，原文件保留；请重新打开页面。", PimStatusTone.Warning)
                                    }
                                }
                                items(note.blocks, key = { it.string("id") }) { block ->
                                    when (block.string("kind")) {
                                        "markdown" -> MarkdownBlockView(block, preview, !busy && !committing, editor,
                                            readCurrentText = { readDocument(note.id)?.blocks?.firstOrNull { it.string("id") == block.string("id") }?.string("text") },
                                            onText = { text -> onChange(note.id) { doc -> doc.replaceBlock(block.string("id")) { JsonObject(it + ("text" to JsonPrimitive(text))) } } },
                                            onProblem = onProblem,
                                            minimumHeight = if (note.blocks.size == 1) d.noteEditorMinHeight else d.minimumTouchTarget,
                                            viewportSize = viewportSize)
                                        "photo" -> PhotoBlockView(photoFile(note.id, block.string("assetId")))
                                        "ink" -> InkCard(block.string("id"), inkOf(block), preview || busy || committing) { ink ->
                                            onChange(note.id) { doc -> doc.replaceBlock(block.string("id")) { withInk(it, ink) } }
                                        }
                                        else -> PimStatusBanner("未知扩展块已保留，当前版本不能编辑它。", PimStatusTone.Warning)
                                    }
                                }
                                item(key = "document-end") {
                                    Text(if (preview) "原始笔记 · 预览" else "正文支持 Markdown · 右下角插入照片与手写",
                                        Modifier.padding(vertical = d.spaceLarge), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    NoteEditorTopBar(saved, note.id in state.errors, busy || committing, preview,
                        onBack = { afterInput { onOpen(null) } },
                        onSaveStatus = { if (note.id in state.errors) onRetry(note.id) else { leaveInput(); showAbout = true } },
                        onPreview = { afterInput { preview = !preview } }, onFinish = { afterInput { confirmFinish = true } },
                        onMaterials = { leaveInput(); showMaterials = true }, onRecordings = { leaveInput(); showRecordings = true },
                        onExport = { afterInput(onExport) }, onAbout = { leaveInput(); showAbout = true })
                    NoteEditorDock(recording, ownActive, saved && !busy && !committing && !recording.active && !recording.preparing,
                        !busy && !committing && !preview && lastMarkdown != null,
                        onRecord = { afterInput { onRecord(it.id) } }, onPauseResume = onPauseResume,
                        onRecordings = { leaveInput(); showRecordings = true },
                        onPhoto = { capture -> afterInput { current -> onInsertPhoto(current.id, editor.insertion(current), capture) } },
                        onInk = { afterInput { current -> onChange(current.id) { it.insert(editor.insertion(current), inkBlock(d.noteDefaultInkWidth.value)) } } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(d.spaceLarge))
                    if (showTextTools) NoteTextToolbar(editor, Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = d.spaceLarge).padding(bottom = d.noteDockClearance))
                }
                if (confirmFinish) AlertDialog(onDismissRequest = { confirmFinish = false },
                    title = { Text("结束这次记录？") },
                    text = { Text("本篇录音会停止，笔记与全部录音会固定为一个本机版本。之后仍可继续编辑和补录。\n\n此预览版尚不能上传、转写或生成分析。") },
                    confirmButton = { TextButton(onClick = { confirmFinish = false; afterInput { onFinish(it.id) } }, modifier = Modifier.testTag("note-confirm-finish")) { Text("结束并保存") } },
                    dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("继续记录") } })
                if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("本机笔记 · 预览版") },
                    text = { Text("支持离线编辑 Markdown、拍照、手写和后台录音。内容仅保存在这台设备，未上传。\n\n上传、逐段转写、完整记录和独立分析仍在完整首版路线图中，当前尚未接入。") },
                    confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } })
                if (showMaterials) NoteMaterialsSheet(note, sessions, materials, busy, { showMaterials = false }, onRetryMaterial, onExportMaterial)
                if (showRecordings) NoteRecordingsSheet(sessions, recording, playing, { showRecordings = false }, onPlay, onPauseResume, onStopRecording)
                completion?.takeIf { it.noteId == note.id }?.let { finished ->
                    NoteCompletionSheet(materials.entries.firstOrNull { it.id == finished.versionId }, onDismissCompletion,
                        onMaterials = { onDismissCompletion(); showMaterials = true },
                        onLibrary = { onDismissCompletion(); onOpen(null) })
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = d.spaceLarge)
                .padding(bottom = if (note != null) d.noteDockClearance else d.spaceLarge).testTag("note-message"))
        }
    }
}

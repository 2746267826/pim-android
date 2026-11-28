package com.pim.app.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
    state: NotesState, selectedId: String?, recording: RecordingState, busy: Boolean, message: String?, playing: String?,
    recordingPermissions: RecordingPermissions,
    materials: NoteMaterialsState,
    onCreate: () -> Unit, onOpen: (String?) -> Unit,
    readDocument: (String) -> NoteDocument?, onProblem: (String) -> Unit,
    onChange: (String, (NoteDocument) -> NoteDocument) -> Unit, onRetry: (String) -> Unit,
    onRetryLoad: () -> Unit = {},
    photoFile: (String, String) -> File,
    onInsertPhoto: (String, NoteInsertion, Boolean) -> Unit, onRecord: (String) -> Unit,
    onPauseResume: () -> Unit, onFinish: (String) -> Unit,
    onPlay: (RecordingSession, Int) -> Unit, onExport: (NoteDocument) -> Unit,
    onRetryMaterial: (String) -> Unit, onExportMaterial: (String) -> Unit,
    onImport: () -> Unit = {},
    onUndo: (String) -> Unit = {}, onRedo: (String) -> Unit = {},
    onExit: () -> Unit = {}, onStopRecording: () -> Unit = {},
    completion: NoteCompletion? = null, onDismissCompletion: () -> Unit = {}, onDismissMessage: (String) -> Unit = {},
    uploads: NoteUploadsState = NoteUploadsState(), uploadTarget: NoteUploadTarget? = null,
    autoSubmitAuthorization: NoteAutoSubmitAuthorization? = null,
    onAuthorizeAutoSubmit: (NoteUploadTarget) -> Unit = {}, onRevokeAutoSubmit: () -> Unit = {},
    onEnableAutoSubmit: (String, NoteUploadTarget) -> Unit = { _, _ -> },
    onUpload: (String, NoteUploadTarget) -> Unit = { _, _ -> }, onResumeUpload: (String) -> Unit = {}, onPauseUpload: (String) -> Unit = {},
    onRefreshUploadReceipt: (String) -> Unit = {}, onPromoteUploadConflict: (String, String) -> Unit = { _, _ -> },
    asr: NoteAsrRepositoryState = NoteAsrRepositoryState(), asrActions: NoteAsrActions = NoteAsrActions(),
    artifacts: NoteArtifactRepositoryState = NoteArtifactRepositoryState(),
    processing: NoteProcessingRepositoryState = NoteProcessingRepositoryState(),
    onRefreshProcessing: (String, String, NoteUploadTarget) -> Unit = { _, _, _ -> },
    legacyMigration: LegacyQuickNoteMigrationState = LegacyQuickNoteMigrationState(),
    legacyMigrationActions: LegacyQuickNoteMigrationActions = LegacyQuickNoteMigrationActions(),
    initialAsr: Boolean = false,
    onRequestRecordingPermissions: () -> Unit = {},
    onOpenRecordingSettings: (RecordingPermissionSetting) -> Unit = {},
    artifactOverlay: @Composable () -> Unit = {},
) {
    val d = PimTheme.dimensions
    val note = state.documents[selectedId]
    var uploadVersionId by rememberSaveable { mutableStateOf<String?>(null) }
    var asrVersionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAsr by rememberSaveable { mutableStateOf(initialAsr) }
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
                NoteLibraryScreen(state, selectedId, recording, busy, onCreate, onOpen, onExit,
                    uploads = uploads, currentTarget = uploadTarget, autoSubmitAuthorization = autoSubmitAuthorization,
                    onEnableAutoSubmit = onAuthorizeAutoSubmit, onRevokeAutoSubmit = onRevokeAutoSubmit,
                    onResumeUpload = onResumeUpload, onPauseUpload = onPauseUpload,
                    asrTaskCount = asr.tasks.size, onAsr = { showAsr = true }, onImport = onImport,
                    onLegacyQuickNotes = legacyMigrationActions.open,
                    onRetryLoad = onRetryLoad)
            } else key(note.id) {
                var preview by rememberSaveable { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val editor = rememberSaveable(saver = remember(scope) { NoteEditorCoordinator.saver(scope) }) { NoteEditorCoordinator(scope) }
                var committing by remember { mutableStateOf(false) }
                var showMaterials by rememberSaveable { mutableStateOf(false) }
                var showRecordings by rememberSaveable { mutableStateOf(false) }
                var confirmFinish by rememberSaveable { mutableStateOf(false) }
                var showAbout by rememberSaveable { mutableStateOf(false) }
                var showSaveStatus by rememberSaveable { mutableStateOf(false) }
                var showRecordingConflict by remember(note.id) { mutableStateOf(false) }
                var sourceModeManual by rememberSaveable(note.id) { mutableStateOf(false) }
                var sourceFallbackReason by rememberSaveable(note.id) { mutableStateOf<String?>(null) }
                var materialSheetId by rememberSaveable(note.id) { mutableStateOf<String?>(null) }
                var materialScrollTarget by remember(note.id) { mutableStateOf<String?>(null) }
                var sourceInsertion by remember(note.id) { mutableStateOf<NoteInsertion?>(null) }
                val sourceMode = sourceModeManual || sourceFallbackReason != null
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val list = rememberLazyListState()
                var viewportBounds by remember { mutableStateOf<Rect?>(null) }
                val saved = state.savedRevisions[note.id] == note.revision && !editor.pending && !committing
                val ownSession = recording.session?.takeIf { it.noteId == note.id }
                val ownActive = ownSession?.active == true
                val activeOtherSession = recording.session?.takeIf { it.active && it.noteId != note.id }
                val activeOtherNoteId = activeOtherSession?.noteId
                val activeOtherTitle = activeOtherSession?.let { session ->
                    session.noteId?.let { noteDisplayTitle(state, it) } ?: "另一篇笔记"
                }
                LaunchedEffect(activeOtherSession?.id) {
                    showRecordingConflict = false
                }
                val sessions = (listOfNotNull(ownSession) + recording.archives.mapNotNull { it.session }).distinctBy { it.id }
                    .filter { it.noteId == note.id }.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
                val latest = materials.entries.firstOrNull { it.noteId == note.id }
                val latestUpload = latestUploadJobForNote(uploads.jobs, note.id)
                val saveProblem = state.saveProblems[note.id]
                val firstMarkdown = note.blocks.firstOrNull { it.string("kind") == "markdown" }
                val documentReviewId = firstMarkdown?.string("id") ?: note.id
                val showTextTools = !sourceMode && editor.activeRichBlock != null && !preview && !busy && !committing
                val dockClearance = d.noteDockClearance + if (showTextTools) d.minimumTouchTarget + d.spaceSmall else 0.dp
                fun leaveInput() { keyboard?.hide(); focus.clearFocus() }
                fun fallbackInsertion(current: NoteDocument): NoteInsertion? = current.blocks.lastOrNull { it.string("kind") == "markdown" }
                    ?.let { NoteInsertion(it.string("id"), it.string("text").length) }
                fun insertionFor(current: NoteDocument): NoteInsertion? {
                    if (!sourceMode) return editor.insertion(current)
                    val selected = sourceInsertion?.takeIf { anchor ->
                        current.blocks.any { it.string("id") == anchor.blockId && it.string("kind") == "markdown" }
                    }
                    return selected ?: fallbackInsertion(current)
                }
                fun afterInput(action: (NoteDocument) -> Unit) {
                    if (busy || committing) return
                    committing = true
                    scope.launch {
                        try { editor.withCommitted(before = ::leaveInput) { action(checkNotNull(readDocument(note.id))) } }
                        catch (_: Exception) { onProblem("尚未确认正文输入或操作完成，原材料保留；请核对内容后重试。") }
                        finally { committing = false }
                    }
                }
                val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle, editor) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) scope.launch {
                            try { editor.withCommitted {} }
                            catch (_: Exception) { onProblem("切换应用时未能确认正文输入完成，请返回核对；已收到的原文保留。") }
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
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
                            materialScrollTarget = id
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            LazyColumn(
                                Modifier.widthIn(max = d.noteDocumentMaxWidth).fillMaxSize().onGloballyPositioned { viewportBounds = it.boundsInRoot() }.testTag("note-document"), state = list,
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
                                            PimStatusBanner(
                                                "保存失败：${saveProblem ?: "本机文件写入失败"}。" +
                                                    "当前更改尚未可靠落盘；上次成功保存的内容仍保留。",
                                                PimStatusTone.Error,
                                            )
                                            TextButton(onClick = { onRetry(note.id) }) { Text("重试本地保存") }
                                        }
                                        when (state.historyUnavailable[note.id]) {
                                            NoteHistoryUnavailableReason.SnapshotTooLarge -> PimStatusBanner(
                                                "本篇超过整篇撤销存储预算（源快照最多 ${NoteEditHistory.MAX_SNAPSHOT_BYTES / (1024 * 1024)} MiB、journal 最多 ${NoteEditHistory.MAX_ENCODED_BYTES / (1024 * 1024)} MiB），仍会正常保存；为避免内存和磁盘无界，本篇不提供整篇撤销记录。",
                                                PimStatusTone.Warning,
                                            )
                                            NoteHistoryUnavailableReason.DamagedJournal -> PimStatusBanner(
                                                "本篇的撤销记录无法通过完整性校验，已暂停使用且保留原文件；笔记正文正常读写。",
                                                PimStatusTone.Warning,
                                            )
                                            null -> Unit
                                        }
                                        recording.problem?.takeIf { ownSession != null }?.let { PimStatusBanner(it.message, PimStatusTone.Error) }
                                        if (ownActive && !recordingPermissions.notificationsReady) {
                                            PimStatusBanner("系统通知权限、总开关或录音渠道已关闭，后台录音状态可能不可见。", PimStatusTone.Warning)
                                        }
                                        if (activeOtherSession != null) {
                                            PimStatusBanner("《${activeOtherTitle ?: "另一篇笔记"}》正在录音。本页编辑不会改变录音归属，也不会开始第二段录音。", PimStatusTone.Info)
                                        }
                                        if (!ownActive && !recording.active && !recordingPermissions.canStart) {
                                            RecordingPermissionNotice(
                                                permissions = recordingPermissions,
                                                requestEnabled = saved,
                                                onRequest = { afterInput { onRequestRecordingPermissions() } },
                                                onOpenSettings = onOpenRecordingSettings,
                                            )
                                        }
                                        if (materials.loadFailed) PimStatusBanner("材料版本暂时无法读取，原文件保留；请重新打开页面。", PimStatusTone.Warning)
                                         if (editor.reviewIds.isNotEmpty()) {
                                             PimStatusBanner("有正文需要核对，确认后才能结束、导出或离开本篇。录音仍独立进行。", PimStatusTone.Warning)
                                             TextButton(modifier = Modifier.testTag("note-review-pending"), onClick = {
                                                 scope.launch { list.animateScrollToItem(2) }
                                             }) { Text("查看待核对正文") }
                                         }
                                    }
                                }
                                item(key = "document-body") {
                                    if (sourceMode || firstMarkdown == null) {
                                        ContinuousSourceDocument(
                                            note = note,
                                            preview = preview,
                                            enabled = !busy && !committing,
                                            reason = sourceFallbackReason,
                                            reviewPending = documentReviewId in editor.reviewIds,
                                            canAcknowledgeReview = editor.canAcknowledge(documentReviewId),
                                            onAcknowledgeReview = { editor.acknowledge(documentReviewId) },
                                            onChangeBlock = { id, text ->
                                                onChange(note.id) { doc -> doc.replaceBlock(id) { JsonObject(it + ("text" to JsonPrimitive(text))) } }
                                            },
                                            onInkChange = { id, ink ->
                                                onChange(note.id) { doc -> doc.replaceBlock(id) { withInk(it, ink) } }
                                            },
                                            onSelection = { anchor -> sourceInsertion = anchor },
                                            onProblem = onProblem,
                                            photoFile = photoFile,
                                            onMaterial = { materialSheetId = it },
                                        )
                                    } else {
                                        ContinuousMarkdownDocument(
                                            note = note,
                                            preview = preview,
                                            enabled = !busy && !committing,
                                            coordinator = editor,
                                            readCurrentBlocks = { readDocument(note.id)?.blocks },
                                            onBlocks = { blocks -> onChange(note.id) { doc -> doc.withDocumentBlocks(blocks) } },
                                            onMaterial = { materialSheetId = it },
                                            onProblem = onProblem,
                                            onFallback = { reason -> sourceFallbackReason = reason },
                                            photoFile = photoFile,
                                            minimumHeight = d.noteEditorMinHeight,
                                            viewportBounds = viewportBounds,
                                            onRevealCaret = { caret ->
                                                viewportBounds?.let { viewport ->
                                                    val distance = when {
                                                        caret.height >= viewport.height -> (caret.bottom - viewport.bottom).coerceAtLeast(0f)
                                                        caret.bottom > viewport.bottom -> caret.bottom - viewport.bottom
                                                        caret.top < viewport.top -> caret.top - viewport.top
                                                        else -> 0f
                                                    }
                                                    if (kotlin.math.abs(distance) > 1f) list.scrollBy(distance)
                                                }
                                            },
                                            materialScrollTarget = materialScrollTarget,
                                        )
                                    }
                                }
                                item(key = "document-end") {
                                    NoteDocumentEnd(preview)
                                }
                            }
                        }
                    }
                    NoteEditorTopBar(saved, note.id in state.errors, busy || committing, preview, note.title, sourceMode,
                        onBack = { afterInput { onOpen(null) } },
                        onSaveStatus = {
                            leaveInput()
                            showSaveStatus = true
                        },
                        onPreview = { afterInput { preview = !preview } }, onFinish = { afterInput { confirmFinish = true } },
                        onToggleSource = {
                            afterInput {
                                if (sourceMode) { sourceModeManual = false; sourceFallbackReason = null }
                                else sourceModeManual = true
                            }
                        },
                        onMaterials = { leaveInput(); showMaterials = true }, onRecordings = { leaveInput(); showRecordings = true },
                        onExport = { afterInput(onExport) }, onImport = onImport, onAbout = { leaveInput(); showAbout = true },
                        needsReview = editor.reviewIds.isNotEmpty(),
                        canUndo = state.editHistory[note.id]?.canUndo == true,
                        canRedo = state.editHistory[note.id]?.canRedo == true,
                        onUndo = { afterInput { onUndo(note.id) } },
                        onRedo = { afterInput { onRedo(note.id) } })
                    if (showSaveStatus) NoteSaveStatusDialog(
                        state = noteSaveState(
                            saved = saved,
                            saveFailed = note.id in state.errors,
                            needsReview = editor.reviewIds.isNotEmpty(),
                        ),
                        saveProblem = saveProblem,
                        latestUpload = latestUpload,
                        onRetrySave = {
                            showSaveStatus = false
                            onRetry(note.id)
                        },
                        onReview = {
                            showSaveStatus = false
                            scope.launch { list.scrollToItem(2) }
                        },
                        onOpenMaterials = {
                            showSaveStatus = false
                            leaveInput()
                            showMaterials = true
                        },
                        onDismiss = { showSaveStatus = false },
                    )
                    NoteEditorDock(recording, ownActive, activeOtherTitle, saved && !busy && !committing && !recording.preparing,
                        !busy && !committing && !preview && firstMarkdown != null,
                        onRecord = {
                            if (activeOtherSession != null) showRecordingConflict = true
                            else afterInput { onRecord(it.id) }
                        }, onPauseResume = onPauseResume,
                        onRecordings = { leaveInput(); showRecordings = true },
                        onPhoto = { capture -> afterInput { current ->
                            insertionFor(current)?.let { onInsertPhoto(current.id, it, capture) }
                        } },
                        onInk = { afterInput { current ->
                            insertionFor(current)?.let { anchor ->
                                onChange(current.id) { it.insert(anchor, inkBlock(d.noteDefaultInkWidth.value)) }
                            }
                        } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(d.spaceLarge))
                    if (showTextTools) NoteTextToolbar(editor, Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = d.spaceLarge).padding(bottom = d.noteDockClearance))
                }
                if (showRecordingConflict && activeOtherSession != null) ActiveRecordingConflictDialog(
                    title = activeOtherTitle ?: "另一篇笔记",
                    canOpenRecording = activeOtherNoteId != null,
                    onOpenRecording = {
                        showRecordingConflict = false
                        activeOtherNoteId?.let { id -> afterInput { onOpen(id) } }
                    },
                    onContinueEditing = { showRecordingConflict = false },
                )
                if (confirmFinish) AlertDialog(onDismissRequest = { confirmFinish = false },
                    title = { Text("结束这次记录？") },
                    text = { Text("本篇录音会停止，笔记与全部录音会固定为一个本机版本。之后仍可继续编辑和补录。\n\n如已对当前账号和服务器开启持续授权，完整材料会自动上传；否则只保存在本机。不会自动发起付费转写或 AI 分析。") },
                    confirmButton = { TextButton(onClick = { confirmFinish = false; afterInput { onFinish(it.id) } }, modifier = Modifier.testTag("note-confirm-finish")) { Text("结束并保存") } },
                    dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("继续记录") } })
                if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("笔记与原始材料") },
                    text = { Text("支持离线编辑 Markdown、拍照、手写和后台录音。材料版本中的服务器状态单独显示；本机保存成功不等于已上传。\n\n笔记库可开启或撤销持续上传授权。上传只发送完整原始材料；逐段转写、整理和 AI 分析仍分别需要你确认。") },
                    confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } })
                if (showMaterials) NoteMaterialsSheet(note, sessions, materials, busy, { showMaterials = false }, onRetryMaterial, onExportMaterial,
                    onUpload = { uploadVersionId = it; showMaterials = false }, uploads = uploads, target = uploadTarget,
                    onResumeUpload = onResumeUpload, onPauseUpload = onPauseUpload,
                    asr = asr, artifacts = artifacts,
                    processing = processing,
                    onRefreshUploadReceipt = onRefreshUploadReceipt,
                    onRefreshProcessing = onRefreshProcessing,
                    onPromoteConflict = onPromoteUploadConflict,
                    onAsr = { asrVersionId = it; showAsr = true })
                materialSheetId?.let { id ->
                    NoteMaterialSheet(
                        note = note,
                        blockId = id,
                        readOnly = preview || busy || committing,
                        photoFile = photoFile,
                        onInk = { ink -> onChange(note.id) { doc -> doc.replaceBlock(id) { withInk(it, ink) } } },
                        onDismiss = { materialSheetId = null },
                    )
                }
                if (showRecordings) NoteRecordingsSheet(sessions, recording, playing, { showRecordings = false }, onPlay, onPauseResume, onStopRecording)
                completion?.takeIf { it.noteId == note.id }?.let { finished ->
                    NoteCompletionSheet(materials.entries.firstOrNull { it.id == finished.versionId }, onDismissCompletion,
                        autoSubmitTarget = uploadTarget,
                        autoSubmitEnabled = autoSubmitAuthorization?.let { auth -> uploadTarget?.binding?.let { auth.serverIdentity == it.serverIdentity && auth.ownerId == it.ownerId } } == true,
                        onEnableAutoSubmit = { target -> onDismissCompletion(); onEnableAutoSubmit(finished.versionId, target) },
                        onRevokeAutoSubmit = { onDismissCompletion(); onRevokeAutoSubmit() },
                        onMaterials = { onDismissCompletion(); showMaterials = true },
                        onLibrary = { onDismissCompletion(); onOpen(null) })
                }
            }
            materials.entries.firstOrNull { it.id == uploadVersionId }?.let { version ->
                NoteUploadConsentSheet(version, uploadTarget, { uploadVersionId = null }, onUpload)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = d.spaceLarge)
                .padding(bottom = if (note != null) d.noteDockClearance else d.spaceLarge).testTag("note-message"))
            if (showAsr) NoteAsrSurface(
                state = asr,
                currentTarget = uploadTarget,
                initialVersionId = asrVersionId,
                onDismiss = { showAsr = false },
                actions = asrActions,
            )
            artifactOverlay()
            if (legacyMigration.visible) LegacyQuickNoteMigrationScreen(
                state = legacyMigration,
                onRefresh = legacyMigrationActions.refresh,
                onPage = legacyMigrationActions.page,
                onSelect = legacyMigrationActions.select,
                onConfirm = legacyMigrationActions.confirm,
                onRevoke = legacyMigrationActions.revoke,
                onDismissPreview = legacyMigrationActions.dismissPreview,
                onOpenNote = legacyMigrationActions.openNote,
                onClose = legacyMigrationActions.close,
            )
        }
    }
}

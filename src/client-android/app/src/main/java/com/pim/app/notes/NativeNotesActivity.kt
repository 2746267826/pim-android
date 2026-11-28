package com.pim.app.notes

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pim.app.recording.*
import com.pim.app.ui.theme.PimTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NativeNotesActivity : ComponentActivity() {
    internal val model: NotesViewModel by viewModels()
    private val recordingRequests = RecordingRequestGate()
    private var restoredPermissionWait = false
    private var recordingPermissions by mutableStateOf(RecordingPermissions())
    private var exportSnapshot: NoteDocument? = null
    private var player: MediaPlayer? = null
    private var playing by mutableStateOf<String?>(null)
    private var cameraUri: Uri? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshRecordingPermissions()
        if (recordingRequests.onPermissionResult() == null) return@registerForActivityResult
        attemptPendingRecording()
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) { model.cancelMaterial(); model.tell("已取消插入照片") }
        else model.importPhoto({ contentResolver.openInputStream(uri) ?: error("Cannot read photo") })
    }
    private val camera = registerForActivityResult(object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri("camera-output", input)
        }
    }) { success ->
        val file = model.cameraToken()?.let(::cameraFile)
        val uri = cameraUri ?: file?.let { FileProvider.getUriForFile(this, "$packageName.note-camera", it) }
        uri?.let { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        cameraUri = null
        if (success && file != null) model.importPhoto({ file.inputStream() }, onImported = { file.delete() })
        else {
            file?.delete(); model.cancelMaterial(); model.tell("拍照已取消，没有插入照片")
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot = exportSnapshot
        exportSnapshot = null
        if (uri != null && snapshot != null) model.export(snapshot) { contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write export") }
        else if (uri != null) model.tell("页面已重建，导出未开始；请重新导出（不会替换为另一份文档）")
    }
    private val exportMaterials = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = model.consumeMaterialExport()
        if (uri != null && id != null) model.exportMaterial(id) { contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write material export") }
        else if (uri != null) model.tell("无法确认固定版本，未导出；不会改用当前新稿，请重新选择版本")
    }
    private val importPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) model.tell("已取消导入文档包")
        else model.importDocument(
            open = { contentResolver.openInputStream(uri) ?: error("Cannot read document package") },
            onImported = model::open,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePendingRecordingRequest(savedInstanceState)
        refreshRecordingPermissions()
        observeNotesForPendingRecording()
        if (savedInstanceState == null) openFromIntent(intent)
        setContent {
            val notes by model.notes.state.collectAsStateWithLifecycle()
            val recording by model.recordings.state.collectAsStateWithLifecycle()
            val selected by model.selectedId.collectAsStateWithLifecycle()
            val message by model.message.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            val materials by model.materials.state.collectAsStateWithLifecycle()
            val completion by model.completion.collectAsStateWithLifecycle()
            val uploads by model.uploads.state.collectAsStateWithLifecycle()
            val autoSubmitAuthorization by model.uploads.autoSubmitAuthorization.collectAsStateWithLifecycle()
            val asr by model.asr.state.collectAsStateWithLifecycle()
            val artifacts by model.artifacts.state.collectAsStateWithLifecycle()
            val processing by model.processing.state.collectAsStateWithLifecycle()
            val artifactEntry by model.artifactEntry.collectAsStateWithLifecycle()
            val artifactConsentDone by model.artifactConsentDone.collectAsStateWithLifecycle()
            val artifactStarting by model.artifactStarting.collectAsStateWithLifecycle()
            val artifactKind by model.artifactKind.collectAsStateWithLifecycle()
            val artifactPendingSelections by model.artifactPendingSelections.collectAsStateWithLifecycle()
            val artifactViews by model.artifactViews.collectAsStateWithLifecycle()
            val artifactContentViews by model.artifactContentViews.collectAsStateWithLifecycle()
            val taskSuggestions by model.taskSuggestions.state.collectAsStateWithLifecycle()
            val legacyMigration by model.legacyQuickNoteMigrationState.collectAsStateWithLifecycle()
            val legacyMigrationActions = remember(model) {
                legacyQuickNoteMigrationActions(model, model::open)
            }
            val uploadTarget = rememberUploadTarget(model.uploads)
            val asrActions = remember(model) {
                NoteAsrActions(
                    refresh = model::refreshAsr,
                    refreshTask = model::refreshAsrTask,
                    loadSegments = model::loadAsrSegments,
                    stop = model::stopAsr,
                    retry = model::retryAsr,
                    start = model::startAsr,
                    replayStart = model::replayAsrStart,
                    replayRetry = model::replayAsrRetry,
                    openDerived = { versionId, noteId, asrTaskId ->
                        model.openArtifacts(versionId, noteId, asrTaskId, "")
                    },
                )
            }
            PimTheme {
                val barColor = (if (selected == null) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface).toArgb()
                SideEffect {
                    window.statusBarColor = barColor
                    window.navigationBarColor = barColor
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = true
                        isAppearanceLightNavigationBars = true
                    }
                }
                NativeNotesScreen(notes, selected, recording, busy, message, playing, recordingPermissions, materials,
                    onCreate = { model.create() }, onOpen = { id ->
                        if (id == null && intent.getBooleanExtra(FROM_LIBRARY, false)) finish() else model.open(id)
                    },
                    readDocument = { model.notes.state.value.documents[it] }, onProblem = model::tell,
                    onChange = { id, change -> model.notes.update(id, change) },
                    onRetry = model.notes::retry, photoFile = model.notes::photoFile,
                    onInsertPhoto = { id, anchor, capture ->
                        if (capture) takePhoto(id, anchor) else {
                            try { model.prepareMaterial(id, anchor); gallery.launch("image/*") }
                            catch (_: Exception) { model.cancelMaterial(); model.tell("无法保存插入位置或没有可用的照片选择器，原材料保留") }
                        }
                    },
                    onRecord = { id -> stopPlayback(); requestRecording(id) },
                    onPauseResume = {
                        recording.session?.let {
                            val error = if (it.phase == RecordingPhase.Paused) RecordingController.resume(this, it.id)
                                else RecordingController.pause(this, it.id)
                            error?.let { problem -> model.tell(problem.message) }
                        }
                    },
                    onFinish = { id ->
                        val session = recording.session?.takeIf { it.noteId == id && it.active }
                        val error = session?.let { RecordingController.finish(this, it.id) }
                        if (error != null) model.tell(error.message) else model.finishLocal(id, session?.id)
                    },
                    onPlay = ::playSegment,
                    onExport = { note ->
                        exportSnapshot = note
                        try { export.launch("PIM-document.zip") } catch (_: Exception) { exportSnapshot = null; model.tell("没有可用的文档导出位置") }
                    },
                    onImport = {
                        try { importPackage.launch(arrayOf("application/zip", "application/octet-stream")) }
                        catch (_: Exception) { model.tell("没有可用的文档包选择器") }
                    }, onRetryLoad = model::retryLoad, onRetryMaterial = model.materials::retry,
                    onExportMaterial = { id ->
                        model.prepareMaterialExport(id)
                        try { exportMaterials.launch("PIM-material-${id.take(8)}.zip") }
                        catch (_: Exception) { model.consumeMaterialExport(); model.tell("没有可用的材料包导出位置") }
                    }, onUndo = model.notes::undo, onRedo = model.notes::redo, onExit = ::finish,
                    onStopRecording = {
                        recording.session?.takeIf { it.active && it.noteId == selected }?.let { session ->
                            RecordingController.finish(this, session.id)?.let { model.tell(it.message) }
                        }
                    }, completion = completion, onDismissCompletion = model::dismissCompletion, onDismissMessage = model::dismissMessage,
                    uploads = uploads, uploadTarget = uploadTarget, autoSubmitAuthorization = autoSubmitAuthorization,
                    onAuthorizeAutoSubmit = model::enableAutoSubmit, onRevokeAutoSubmit = model::revokeAutoSubmit,
                    onEnableAutoSubmit = model::enableAutoSubmit, onUpload = model::submitUpload,
                    onResumeUpload = model::resumeUpload, onPauseUpload = model::pauseUpload,
                    onRefreshUploadReceipt = model::refreshUploadReceipt,
                    onPromoteUploadConflict = model::promoteUploadConflict,
                    asr = asr, asrActions = asrActions, artifacts = artifacts,
                    processing = processing, onRefreshProcessing = model::refreshProcessing,
                    legacyMigration = legacyMigration,
                    legacyMigrationActions = legacyMigrationActions,
                    onRequestRecordingPermissions = { model.selectedId.value?.let(::requestRecording) },
                    onOpenRecordingSettings = ::openRecordingSettings,
                    artifactOverlay = {
                        artifactEntry?.let { entry ->
                            NoteArtifactOverlay(
                                state = artifacts,
                                entry = entry,
                                consentDone = artifactConsentDone,
                                starting = artifactStarting,
                                selectedKind = artifactKind,
                                pendingSelections = artifactPendingSelections,
                                viewedCandidates = artifactViews,
                                contentViews = artifactContentViews,
                                suggestionsState = taskSuggestions,
                                entryActions = noteArtifactEntryActions(model),
                                screenActions = noteArtifactScreenActions(model),
                                suggestionActions = noteTaskSuggestionsActions(model),
                            )
                        }
                    })
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openFromIntent(intent) }
    override fun onResume() {
        super.onResume()
        refreshRecordingPermissions()
        recoverRestoredPermissionWait()
        attemptPendingRecording()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val pending = recordingRequests.pending()
        outState.putString(PENDING_PERMISSION_NOTE, pending?.noteId)
        outState.putString(PENDING_PERMISSION_PHASE, pending?.phase?.name)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { stopPlayback(); super.onStop() }
    override fun onDestroy() {
        if (isFinishing) recordingRequests.cancel()
        super.onDestroy()
    }
    private fun openFromIntent(intent: Intent) { intent.getStringExtra(NOTE_ID)?.takeIf(::validNoteId)?.let(model::open) }

    private fun takePhoto(id: String, anchor: NoteInsertion) {
        try {
            val token = newNoteId()
            val file = cameraFile(token)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            check(file.createNewFile())
            model.prepareMaterial(id, anchor, token)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.note-camera", file)
            camera.launch(cameraUri!!)
        } catch (_: Exception) { model.cancelMaterial(); model.tell("无法打开相机，没有插入照片；原有材料不变") }
    }
    private fun cameraFile(token: String): File { require(validNoteId(token)); return File(cacheDir, "note-camera/$token.jpg") }

    private fun requestRecording(id: String) {
        if (model.notes.state.value.savedRevisions[id] != model.notes.state.value.documents[id]?.revision) {
            model.tell("请先等待本地保存完成，再开始录音")
            return
        }
        val currentRecording = model.recordings.state.value
        if (currentRecording.active || currentRecording.preparing) {
            model.tell("已有录音正在开启或进行中，请先返回原笔记或结束该段录音")
            return
        }
        refreshRecordingPermissions()
        val current = recordingPermissions
        if (current.canStart) {
            startRecording(id)
            return
        }
        if (!current.canRequest) {
            model.tell(current.message)
            return
        }
        if (!recordingRequests.begin(id)) return
        val runtimePermissions = current.runtimePermissionsToRequest(Build.VERSION.SDK_INT)
        if (runtimePermissions.isEmpty()) {
            recordingRequests.cancel()
            model.tell(current.message)
            return
        }
        try {
            permissions.launch(runtimePermissions)
        } catch (_: Exception) {
            recordingRequests.cancel()
            model.tell("系统没有打开权限请求；请从上方提示打开应用设置后重试")
        }
    }

    private fun startRecording(id: String) {
        if (model.selectedId.value != id) {
            model.tell("已切换到其他笔记，未开始此前请求的录音。")
            return
        }
        val notes = model.notes.state.value
        val note = notes.documents[id]
        if (note == null) {
            model.tell(
                if (notes.loadFailed) "本机笔记初始化失败，未开始录音；请重新打开笔记后重试。"
                else "笔记尚未完成初始化，未开始录音；请稍后重试。"
            )
            return
        }
        if (notes.savedRevisions[id] != note.revision) { model.tell("请先等待本地保存完成，再开始录音"); return }
        val currentRecording = model.recordings.state.value
        if (currentRecording.active || currentRecording.preparing) {
            model.tell("已有录音正在开启或进行中，请先返回原笔记或结束该段录音")
            return
        }
        val current = RecordingPermissionsInspector.inspect(this)
        recordingPermissions = current
        if (!current.canStart) { model.tell(current.message); return }
        RecordingController.start(this, id)?.let { model.tell(it.message) }
    }

    private fun restorePendingRecordingRequest(savedInstanceState: Bundle?) {
        val noteId = savedInstanceState?.getString(PENDING_PERMISSION_NOTE)?.takeIf(::validNoteId) ?: return
        val phase = savedInstanceState.getString(PENDING_PERMISSION_PHASE)
            ?.let { stored -> runCatching { RecordingRequestPhase.valueOf(stored) }.getOrNull() }
            ?: RecordingRequestPhase.AwaitingPermission
        recordingRequests.restore(PendingRecordingRequest(noteId, phase))
        // A retained non-configuration instance means this is a configuration change, where the
        // pending result is still delivered to the re-registered activity-result callback. Only
        // process recreation loses that delivery and needs the one-time recovery path.
        restoredPermissionWait =
            phase == RecordingRequestPhase.AwaitingPermission && lastNonConfigurationInstance == null
    }

    private fun recoverRestoredPermissionWait() {
        if (!restoredPermissionWait) return
        restoredPermissionWait = false
        when (val decision = RecordingRestorePolicy.decide(recordingRequests.pending(), recordingPermissions)) {
            RecordingRestoreDecision.NothingToRecover -> Unit
            RecordingRestoreDecision.RetryOnce -> {
                if (recordingRequests.claimRestoredPermissionWait() != null) attemptPendingRecording()
            }
            is RecordingRestoreDecision.Discard -> {
                recordingRequests.cancel()
                model.tell(decision.message)
            }
        }
    }

    private fun observeNotesForPendingRecording() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.notes.state.collect { attemptPendingRecording() }
            }
        }
    }

    private fun attemptPendingRecording() {
        val request = recordingRequests.pending()
            ?.takeIf { it.phase == RecordingRequestPhase.AwaitingDocument }
            ?: return
        refreshRecordingPermissions()
        val notes = model.notes.state.value
        val document = notes.documents[request.noteId]
        val decision = RecordingStartPolicy.decide(
            requestedNoteId = request.noteId,
            selectedNoteId = model.selectedId.value,
            notesLoaded = notes.loaded,
            notesLoadFailed = notes.loadFailed,
            documentLoaded = document != null,
            noteSaved = document != null && notes.savedRevisions[request.noteId] == document.revision,
            recordingBusy = model.recordings.state.value.let { it.active || it.preparing },
            permissions = recordingPermissions,
        )
        when (decision) {
            RecordingStartDecision.WaitForDocument -> Unit
            is RecordingStartDecision.Start ->
                if (recordingRequests.complete(request.noteId)) startRecording(decision.noteId)
            is RecordingStartDecision.Stop ->
                if (recordingRequests.complete(request.noteId)) model.tell(decision.message)
        }
    }

    private fun refreshRecordingPermissions() {
        recordingPermissions = RecordingPermissionsInspector.inspect(this)
    }

    private fun openRecordingSettings(setting: RecordingPermissionSetting) {
        try { startActivity(setting.createIntent(this)) }
        catch (_: Exception) { model.tell("没有可用的系统设置页面；请从系统设置打开 PIM 的权限和通知") }
    }

    private fun playSegment(session: RecordingSession, segment: Int) {
        val key = "${session.id}:$segment"
        if (playing == key) { stopPlayback(); return }
        stopPlayback()
        val files = model.recordings.audioFiles(session).filter { file -> session.chunks.any { it.segment == segment && it.fileName == file.name } }
        playing = key
        fun next(index: Int) {
            if (playing != key || index >= files.size) { stopPlayback(); return }
            try {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(files[index].absolutePath)
                    setOnPreparedListener { it.start() }; setOnCompletionListener { next(index + 1) }
                    setOnErrorListener { _, _, _ -> model.tell("录音回放失败，原材料保留"); stopPlayback(); true }
                    prepareAsync()
                }
            } catch (_: Exception) { stopPlayback(); model.tell("录音回放失败，原材料保留") }
        }
        next(0)
    }
    private fun stopPlayback() { player?.release(); player = null; playing = null }

    companion object {
        private const val NOTE_ID = "native_note_id"
        private const val FROM_LIBRARY = "native_note_from_library"
        private const val PENDING_PERMISSION_NOTE = "native_note_pending_permission"
        private const val PENDING_PERMISSION_PHASE = "native_note_pending_permission_phase"
        fun intent(context: Context, id: String? = null, fromLibrary: Boolean = false) = Intent(context, NativeNotesActivity::class.java)
            .putExtra(NOTE_ID, id).putExtra(FROM_LIBRARY, fromLibrary).apply {
            if (id != null) data = Uri.Builder().scheme("pim-note").authority("local").appendPath(id).build()
        }
    }
}

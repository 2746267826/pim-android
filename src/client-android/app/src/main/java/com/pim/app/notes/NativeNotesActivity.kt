package com.pim.app.notes

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pim.app.recording.*
import com.pim.app.ui.theme.PimTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class NativeNotesActivity : ComponentActivity() {
    internal val model: NotesViewModel by viewModels()
    private var permissionNote: String? = null
    private var exportSnapshot: NoteDocument? = null
    private var player: MediaPlayer? = null
    private var playing by mutableStateOf<String?>(null)
    private var cameraUri: Uri? = null
    private var notificationsVisible by mutableStateOf(true)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val id = permissionNote
        permissionNote = null
        if (id != null && model.selectedId.value == id) startRecording(id)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val asr by model.asr.state.collectAsStateWithLifecycle()
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
                NativeNotesScreen(notes, selected, recording, busy, message, playing, notificationsVisible, materials,
                    onCreate = model::create, onOpen = { id ->
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
                    onRecord = { id ->
                        stopPlayback()
                        permissionNote = id
                        permissions.launch(if (Build.VERSION.SDK_INT >= 33)
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
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
                    }, onRetryMaterial = model.materials::retry,
                    onExportMaterial = { id ->
                        model.prepareMaterialExport(id)
                        try { exportMaterials.launch("PIM-material-${id.take(8)}.zip") }
                        catch (_: Exception) { model.consumeMaterialExport(); model.tell("没有可用的材料包导出位置") }
                    }, onExit = ::finish,
                    onStopRecording = {
                        recording.session?.takeIf { it.active && it.noteId == selected }?.let { session ->
                            RecordingController.finish(this, session.id)?.let { model.tell(it.message) }
                        }
                    }, completion = completion, onDismissCompletion = model::dismissCompletion, onDismissMessage = model::dismissMessage,
                    uploads = uploads, uploadTarget = uploadTarget, onUpload = model::submitUpload,
                    onResumeUpload = model::resumeUpload, onPauseUpload = model::pauseUpload,
                    asr = asr, asrActions = asrActions)
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openFromIntent(intent) }
    override fun onResume() {
        super.onResume()
        val manager = getSystemService(android.app.NotificationManager::class.java)
        notificationsVisible = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            manager.getNotificationChannel("pim_recording_v1")?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }
    override fun onStop() { stopPlayback(); super.onStop() }
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

    private fun startRecording(id: String) {
        val note = model.notes.state.value.documents[id] ?: return
        if (model.notes.state.value.savedRevisions[id] != note.revision) { model.tell("请先等待本地保存完成，再开始录音"); return }
        if (model.recordings.state.value.active) { model.tell("已有录音正在进行，请先结束该段"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            model.tell(RecordingProblem.Permission.message); return
        }
        RecordingController.start(this, id)?.let { model.tell(it.message) }
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
        fun intent(context: Context, id: String? = null, fromLibrary: Boolean = false) = Intent(context, NativeNotesActivity::class.java)
            .putExtra(NOTE_ID, id).putExtra(FROM_LIBRARY, fromLibrary).apply {
            if (id != null) data = Uri.Builder().scheme("pim-note").authority("local").appendPath(id).build()
        }
    }
}

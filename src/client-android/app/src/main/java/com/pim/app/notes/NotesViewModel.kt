package com.pim.app.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pim.app.recording.RecordingRepository
import com.pim.core.contract.NoteAsrDestinationDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class NoteCompletion(val noteId: String, val versionId: String)

@HiltViewModel
class NotesViewModel @Inject constructor(
    val notes: NoteRepository,
    val recordings: RecordingRepository,
    val materials: NoteMaterialRepository,
    private val saved: SavedStateHandle,
    val uploads: NoteUploadRepository,
    val asr: NoteAsrRepository,
    val artifacts: NoteArtifactRepository,
) : ViewModel() {
    val selectedId = saved.getStateFlow<String?>("noteId", null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableCompletion = MutableStateFlow<NoteCompletion?>(null)
    val completion = mutableCompletion.asStateFlow()
    private val mutableArtifactEntry = MutableStateFlow<NoteArtifactEntry?>(null)
    val artifactEntry = mutableArtifactEntry.asStateFlow()
    private val mutableArtifactConsentDone = MutableStateFlow(false)
    val artifactConsentDone = mutableArtifactConsentDone.asStateFlow()
    private val mutableArtifactStarting = MutableStateFlow(false)
    val artifactStarting = mutableArtifactStarting.asStateFlow()
    private val mutableArtifactKind = MutableStateFlow(NoteArtifactKind.FullRecord)
    val artifactKind = mutableArtifactKind.asStateFlow()
    private val mutableArtifactSelections = MutableStateFlow<Map<NoteArtifactKind, String?>>(emptyMap())
    val artifactSelections = mutableArtifactSelections.asStateFlow()
    private val mutableArtifactViews = MutableStateFlow<Map<NoteArtifactKind, String?>>(emptyMap())
    val artifactViews = mutableArtifactViews.asStateFlow()
    private val mutableArtifactContentViews =
        MutableStateFlow<Map<NoteArtifactKind, NoteArtifactContentView>>(emptyMap())
    val artifactContentViews = mutableArtifactContentViews.asStateFlow()

    init {
        viewModelScope.launch {
            notes.initialize()
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recordings.initialize() } }
            catch (_: Exception) { tell("录音材料暂时无法读取，原始文件保留") }
            materials.initialize()
            uploads.initialize()
            asr.initialize()
            artifacts.initialize()
        }
    }

    fun open(id: String?) { if (!busy.value) { saved["noteId"] = id; mutableMessage.value = null; dismissCompletion() } }
    fun create() { open(notes.create()) }
    fun tell(text: String) { mutableMessage.value = text }
    fun dismissMessage(text: String) { if (mutableMessage.value == text) mutableMessage.value = null }
    fun dismissCompletion() { mutableCompletion.value = null }

    fun submitUpload(versionId: String, target: NoteUploadTarget) {
        viewModelScope.launch {
            try {
                val id = uploads.submit(versionId, target)
                tell(when (uploads.state.value.jobs.firstOrNull { it.id == id }?.phase) {
                    NoteUploadPhase.Uploaded -> "服务器已接收该版本，本次没有创建重复上传"
                    NoteUploadPhase.Conflict -> "该版本已保存服务器但存在冲突，双方材料保留"
                    else -> "固定材料已加入上传队列；编辑或补录不会改变此版本"
                })
            }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("未能确认上传队列。请核对账号、服务器、材料和空间；未改用当前新稿，请重新打开上传确认。") }
        }
    }

    fun resumeUpload(id: String) { viewModelScope.launch {
        try { uploads.resume(id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { tell("请登录任务原绑定的账号与服务器后继续；原材料与已传分块保留") }
    } }

    fun pauseUpload(id: String) { viewModelScope.launch {
        try { uploads.pause(id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { tell("未确认暂停成功，请重新核对上传状态") }
    } }

    fun refreshAsr() { viewModelScope.launch { asr.refresh() } }
    fun refreshAsrTask(id: String) { viewModelScope.launch { asr.refreshTask(id) } }
    fun loadAsrSegments(id: String) { viewModelScope.launch { asr.loadSegments(id) } }
    fun stopAsr(id: String) { viewModelScope.launch { asr.stop(id) } }
    fun retryAsr(id: String, indexes: List<Int>) { viewModelScope.launch { asr.retry(id, indexes, true) } }
    fun startAsr(versionId: String, destination: NoteAsrDestinationDto, language: String) {
        viewModelScope.launch {
            try {
                asr.start(versionId, destination, freshlyConfirmed = true, language = language)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                tell(error.message ?: "未能确认 ASR 目标或任务；固定材料保留")
            }
        }
    }
    fun replayAsrStart(id: String) { viewModelScope.launch { asr.replayStart(id, true) } }
    fun replayAsrRetry(id: String) { viewModelScope.launch { asr.replayRetry(id, true) } }

    fun openArtifacts(versionId: String, noteId: String, asrTaskId: String?, title: String) {
        mutableArtifactEntry.value = NoteArtifactEntry(noteId, versionId, asrTaskId, title)
        mutableArtifactSelections.value = emptyMap()
        mutableArtifactViews.value = emptyMap()
        mutableArtifactContentViews.value = emptyMap()
        viewModelScope.launch {
            artifacts.refresh(noteId, versionId)
            mutableArtifactConsentDone.value = artifacts.state.value.tasks.any {
                it.materialVersionId == versionId && it.noteId == noteId
            }
        }
    }

    fun closeArtifacts() {
        mutableArtifactEntry.value = null
        mutableArtifactConsentDone.value = false
        mutableArtifactStarting.value = false
        artifacts.clearMessage()
    }

    fun refreshArtifacts() {
        val entry = mutableArtifactEntry.value ?: return
        viewModelScope.launch {
            artifacts.refresh(
                noteId = entry.noteId,
                materialVersionId = entry.materialVersionId,
                selectedTaskId = artifacts.state.value.selectedTaskId,
            )
        }
    }

    fun confirmArtifactStart(destination: NoteArtifactDestination, kinds: Set<NoteArtifactKind>) {
        val entry = mutableArtifactEntry.value ?: return
        if (kinds.isEmpty() || mutableArtifactStarting.value) return
        mutableArtifactStarting.value = true
        viewModelScope.launch {
            try {
                kinds.sortedBy { it.ordinal }.forEach { kind ->
                    artifacts.start(
                        noteId = entry.noteId,
                        materialVersionId = entry.materialVersionId,
                        asrTaskId = entry.asrTaskId,
                        kind = kind,
                        destination = destination,
                        freshlyConfirmed = true,
                    )
                }
                mutableArtifactConsentDone.value = true
                artifacts.refresh(entry.noteId, entry.materialVersionId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                tell(error.message ?: "未能确认派生任务；固定材料与本机稿保留")
            } finally {
                mutableArtifactStarting.value = false
            }
        }
    }

    fun selectArtifactKind(kind: NoteArtifactKind) {
        mutableArtifactKind.value = kind
    }

    fun stageArtifactText(request: NoteArtifactDraftEditRequest) {
        viewModelScope.launch {
            artifacts.stageText(request.localDraftId, request.expectedLocalRevision, request.text)
        }
    }

    fun saveArtifactLocalDraft(request: NoteArtifactDraftSaveRequest) {
        viewModelScope.launch {
            artifacts.saveLocalDraft(request.localDraftId, request.expectedLocalRevision, request.text)
        }
    }

    fun saveArtifactServerRevision(request: NoteArtifactDraftSaveRequest) {
        val entry = mutableArtifactEntry.value ?: return
        val draft = artifacts.state.value.drafts.firstOrNull {
            it.localDraftId == request.localDraftId && it.kind == request.kind
        } ?: return
        val artifact = artifacts.state.value.artifactRefs[request.kind] ?: run {
            tell("尚无可保存的服务端结果；本机稿继续保留。")
            return
        }
        val expectedHeadRevision = artifact.revision ?: run {
            tell("尚未读取到服务端当前版本基线；请刷新后再保存，本机稿保留。")
            return
        }
        if (draft.dirty) {
            tell("请先保存本机稿，再同步服务端；未保存的输入不会被发送。")
            return
        }
        viewModelScope.launch {
            artifacts.saveManualRevisionToServer(
                kind = request.kind,
                artifactId = artifact.artifactId,
                localDraftId = draft.localDraftId,
                expectedLocalRevision = draft.localRevision,
                expectedHeadRevision = expectedHeadRevision,
                expectedHeadVersionId = artifact.headVersionId,
                contentMarkdown = draft.text,
            )
            if (artifacts.state.value.noteId == null) artifacts.refresh(entry.noteId, entry.materialVersionId)
        }
    }

    fun viewArtifactCandidate(kind: NoteArtifactKind, candidateId: String) {
        mutableArtifactViews.value = mutableArtifactViews.value + (kind to candidateId)
        mutableArtifactContentViews.value =
            mutableArtifactContentViews.value + (kind to NoteArtifactContentView.ServerOriginal)
    }

    fun changeArtifactContentView(kind: NoteArtifactKind, view: NoteArtifactContentView) {
        mutableArtifactContentViews.value = mutableArtifactContentViews.value + (kind to view)
    }

    fun selectArtifactCandidate(kind: NoteArtifactKind, candidateId: String) {
        val artifact = artifacts.state.value.artifactRefs[kind] ?: run {
            tell("尚无可选的服务端结果或完整元数据；请刷新后重试，本机稿保留。")
            return
        }
        val revision = artifact.revision ?: run {
            tell("尚未读取到服务端当前版本基线；请刷新后重试，本机稿保留。")
            return
        }
        mutableArtifactSelections.value = mutableArtifactSelections.value + (kind to candidateId)
        viewModelScope.launch {
            artifacts.selectCandidate(artifact.artifactId, revision, candidateId)
        }
    }

    fun regenerateArtifact(kind: NoteArtifactKind) {
        viewModelScope.launch { artifacts.regenerate(kind) }
    }

    fun stopArtifact(kind: NoteArtifactKind) {
        latestArtifactTask(kind)?.let { task ->
            viewModelScope.launch { artifacts.stop(task.taskId) }
        }
    }

    fun recoverArtifact(kind: NoteArtifactKind) {
        val task = latestArtifactTask(kind) ?: return
        viewModelScope.launch {
            when {
                task.pendingRetry?.requiresExplicitReplay() == true ->
                    artifacts.replayRetry(task.taskId, freshlyConfirmed = true)
                task.remote == null && task.localPhase in setOf(
                    NoteArtifactIntentPhase.Prepared,
                    NoteArtifactIntentPhase.Submitting,
                    NoteArtifactIntentPhase.VerificationPending,
                ) -> artifacts.replayStart(task.taskId, freshlyConfirmed = true)
                else -> artifacts.verifyTask(task.taskId)
            }
        }
    }

    private fun latestArtifactTask(kind: NoteArtifactKind): NoteArtifactTaskRecord? {
        val entry = mutableArtifactEntry.value ?: return null
        return artifacts.state.value.tasks
            .filter { it.kind == kind && it.noteId == entry.noteId && it.materialVersionId == entry.materialVersionId }
            .maxByOrNull { it.updatedAtMillis }
    }

    fun prepareMaterial(noteId: String, anchor: NoteInsertion, cameraToken: String? = null) {
        cancelMaterial()
        // A formatted caret may need two complete Markdown fragments. Keep them out of the Activity Bundle.
        val token = notes.saveInsertion(anchor)
        saved["materialNote"] = noteId; saved["materialBlock"] = anchor.blockId; saved["materialOffset"] = anchor.offset
        saved["materialInsertionToken"] = token
        saved["cameraToken"] = cameraToken
    }
    fun cameraToken(): String? = saved["cameraToken"]
    fun cancelMaterial() {
        saved.remove<String>("materialInsertionToken")?.let(notes::removeInsertion)
        saved.remove<String>("materialNote"); saved.remove<String>("materialBlock")
        saved.remove<Int>("materialOffset"); saved.remove<String>("cameraToken")
    }

    fun importPhoto(open: () -> java.io.InputStream, onImported: () -> Unit = {}) {
        val id = saved.get<String>("materialNote") ?: return
        val anchor = try {
            saved.get<String>("materialInsertionToken")?.let(notes::readInsertion)
                ?: NoteInsertion(saved.get<String>("materialBlock") ?: return, saved["materialOffset"] ?: 0)
        } catch (_: Exception) { tell("插入位置无法恢复，原材料保留；请重新选择插入位置"); return }
        if (busy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                notes.insertPhoto(id, anchor, open)
                cancelMaterial(); onImported(); tell("照片已插入笔记")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("照片导入失败或插入位置已变化，没有插入占位图片；原材料保留。请重选位置，并检查空间及图片格式（JPEG/PNG/WebP，单张不超过32 MB）") }
            finally { mutableBusy.value = false }
        }
    }

    fun export(note: NoteDocument, open: () -> java.io.OutputStream) {
        mutableBusy.value = true
        viewModelScope.launch {
            try { notes.exportDocument(note, open); tell("文档包已导出：正文、照片、可编辑笔迹及预览图；不含录音") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("导出失败，目标可能是不完整文件；原材料保留在本机。请重试，不要将该文件视为完整备份") }
            finally { mutableBusy.value = false }
        }
    }

    fun prepareMaterialExport(id: String) { saved["exportMaterialVersionId"] = id }
    fun consumeMaterialExport(): String? = saved.remove("exportMaterialVersionId")

    fun exportMaterial(id: String, open: () -> java.io.OutputStream) {
        if (busy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try { materials.export(id, open); tell("固定材料包已导出，含该版本全部录音、正文、照片和笔迹；仅本机，未上传") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("完整材料包导出失败，目标可能不完整；固定输入及原材料保留，请检查空间和材料后重试") }
            finally { mutableBusy.value = false }
        }
    }

    fun finishLocal(id: String, sessionId: String?) {
        if (busy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                if (sessionId != null) withTimeout(10_000) {
                    recordings.state.first { it.session?.id != sessionId || !it.active }
                }
                notes.update(id) { note ->
                    if (note.source.containsKey("endedAtMillis")) note
                    else note.copy(source = JsonObject(note.source + ("endedAtMillis" to JsonPrimitive(System.currentTimeMillis()))))
                }
                val snapshot = notes.checkpoint(id)
                val sessions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recordings.materialSessions(id) }
                val versionId = materials.freeze(snapshot, sessions)
                mutableCompletion.value = NoteCompletion(id, versionId)
            } catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException) tell("录音尚未确认停止，请检查状态并重试") else throw error
            } catch (_: Exception) {
                tell("本机记录保留，但未能安排完整材料准备；请检查保存、录音及存储状态后再次结束。没有上传或启动云处理")
            } finally { mutableBusy.value = false }
        }
    }
}

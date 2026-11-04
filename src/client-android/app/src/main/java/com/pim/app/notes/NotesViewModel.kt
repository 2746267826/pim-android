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
) : ViewModel() {
    val selectedId = saved.getStateFlow<String?>("noteId", null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableCompletion = MutableStateFlow<NoteCompletion?>(null)
    val completion = mutableCompletion.asStateFlow()

    init {
        viewModelScope.launch {
            notes.initialize()
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recordings.initialize() } }
            catch (_: Exception) { tell("录音材料暂时无法读取，原始文件保留") }
            materials.initialize()
            uploads.initialize()
            asr.initialize()
        }
    }

    fun open(id: String?) { if (!busy.value) { saved["noteId"] = id; mutableMessage.value = null; dismissCompletion() } }
    fun create() { open(notes.create()) }
    fun tell(text: String) { mutableMessage.value = text }
    fun dismissMessage(text: String) { if (mutableMessage.value == text) mutableMessage.value = null }
    fun dismissCompletion() { mutableCompletion.value = null }

    fun enableAutoSubmit(versionId: String, target: NoteUploadTarget) {
        viewModelScope.launch {
            try {
                val result = uploads.authorizeAutoSubmitForVersion(versionId, target)
                if (!result.authorized) {
                    tell("账号或服务器已变化，未保存自动上传授权；请重新核对当前目标")
                } else if (result.scheduled) {
                    tell("持续授权已保存；此版本材料齐备后会加入后台上传队列，转写与 AI 分析仍需单独确认")
                } else {
                    tell("持续授权已保存，但此版本的后台安排尚未确认；材料版本仍保留，可重试准备或上传")
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("未能确认自动上传授权或后台安排，请重试；没有启动 AI 分析") }
        }
    }

    fun enableAutoSubmit(target: NoteUploadTarget) {
        viewModelScope.launch {
            try {
                if (uploads.authorizeAutoSubmit(target)) tell("持续授权已保存；之后固定的完整材料会自动上传到此账号和服务器，转写与 AI 分析仍需单独确认")
                else tell("账号或服务器已变化，未保存自动上传授权；请重新核对当前目标")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("未能确认自动上传授权已保存，请重试") }
        }
    }

    fun revokeAutoSubmit() {
        viewModelScope.launch {
            try {
                if (uploads.revokeAutoSubmitDurably()) tell("已撤销持续授权；已加入上传队列的任务仍会继续")
                else tell("未能确认撤销成功；授权状态可能仍有效，请重试")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("未能确认撤销成功；授权状态可能仍有效，请重试") }
        }
    }

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
                val registration = try {
                    uploads.submitIfAuthorized(versionId)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { NoteAutoSubmitRegistration(authorized = false, scheduled = false) }
                mutableCompletion.value = NoteCompletion(id, versionId)
                tell(when {
                    !registration.authorized -> "完整材料已保存在本机；未能确认自动上传安排，请核对授权与材料版本"
                    registration.scheduled -> "完整材料已保存；持续授权已按结束时账号和服务器锁定，材料齐备后加入后台上传队列"
                    else -> "完整材料已保存；自动上传授权有效，但后台安排未确认，请检查材料版本后重试"
                })
            } catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException) tell("录音尚未确认停止，请检查状态并重试") else throw error
            } catch (_: Exception) {
                tell("本机记录保留，但未能安排完整材料准备；请检查保存、录音及存储状态后再次结束。没有上传或启动云处理")
            } finally { mutableBusy.value = false }
        }
    }
}

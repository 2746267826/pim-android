package com.pim.app.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pim.app.recording.RecordingRepository
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

@HiltViewModel
class NotesViewModel @Inject constructor(
    val notes: NoteRepository,
    val recordings: RecordingRepository,
    val materials: NoteMaterialRepository,
    private val saved: SavedStateHandle
) : ViewModel() {
    val selectedId = saved.getStateFlow<String?>("noteId", null)
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    init {
        viewModelScope.launch {
            notes.initialize()
            try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recordings.initialize() } }
            catch (_: Exception) { tell("录音材料暂时无法读取，原始文件保留") }
            materials.initialize()
        }
    }

    fun open(id: String?) { if (!busy.value) { saved["noteId"] = id; mutableMessage.value = null } }
    fun create() { open(notes.create()) }
    fun tell(text: String) { mutableMessage.value = text }

    fun prepareMaterial(noteId: String, anchor: NoteInsertion, cameraToken: String? = null) {
        saved["materialNote"] = noteId; saved["materialBlock"] = anchor.blockId; saved["materialOffset"] = anchor.offset
        saved["cameraToken"] = cameraToken
    }
    fun cameraToken(): String? = saved["cameraToken"]
    fun cancelMaterial() {
        saved.remove<String>("materialNote"); saved.remove<String>("materialBlock")
        saved.remove<Int>("materialOffset"); saved.remove<String>("cameraToken")
    }

    fun importPhoto(open: () -> java.io.InputStream, onImported: () -> Unit = {}) {
        val id = saved.get<String>("materialNote") ?: return
        val anchor = NoteInsertion(saved.get<String>("materialBlock") ?: return, saved["materialOffset"] ?: 0)
        if (busy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                notes.insertPhoto(id, anchor, open)
                cancelMaterial(); onImported(); tell("照片已插入，本地保存状态见上方")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { tell("照片导入失败，没有插入占位图片。请检查空间及图片格式（JPEG/PNG/WebP，单张不超过32 MB）") }
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
                materials.freeze(snapshot, sessions)
                tell("本次记录已结束，固定材料在本机后台准备；可继续编辑或补录，旧版本不会改变。尚未上传、转写或分析")
            } catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException) tell("录音尚未确认停止，请检查状态并重试") else throw error
            } catch (_: Exception) {
                tell("本机记录保留，但未能安排完整材料准备；请检查保存、录音及存储状态后再次结束。没有上传或启动云处理")
            } finally { mutableBusy.value = false }
        }
    }
}

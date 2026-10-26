package com.pim.app.notes

import com.pim.app.recording.RecordingSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Local disk format, not a server DTO or a claim that cloud processing has started. */
@Serializable
data class NoteMaterialInput(
    val id: String,
    val createdAtMillis: Long,
    val note: JsonObject,
    val recordings: List<RecordingSession>,
    val schemaVersion: Int = 1
) {
    val document: NoteDocument get() = NoteDocument(note)
}

@Serializable
enum class NoteMaterialKind { Document, Markdown, Photo, InkPreview, RecordingManifest, AudioChunk }

@Serializable
data class NoteMaterialFile(
    val path: String,
    val kind: NoteMaterialKind,
    val sha256: String,
    val sizeBytes: Long,
    val blockId: String? = null,
    val recordingId: String? = null,
    val segment: Int? = null,
    val chunkIndex: Int? = null,
    val samples: Long? = null
)

@Serializable
data class NoteMaterialManifest(
    val versionId: String,
    val noteId: String,
    val revision: Long,
    val inputSha256: String,
    val files: List<NoteMaterialFile>,
    val schemaVersion: Int = 1
)

@Serializable
enum class NoteMaterialPhase { Preparing, Ready, Failed }

@Serializable
enum class NoteMaterialProblem(val message: String) {
    Incomplete("材料缺失、损坏或格式不支持；没有生成完整版本，原材料保留。请检查材料后重试"),
    Storage("本地材料准备失败；请检查剩余空间后重试，重试不会改用当前新稿"),
    Unreadable("固定版本无法读取，原文件保留；不会用空白或当前新稿替代它")
}

@Serializable
internal data class NoteMaterialStatus(val phase: NoteMaterialPhase, val problem: NoteMaterialProblem? = null)

data class NoteMaterialEntry(
    val id: String,
    val noteId: String?,
    val revision: Long?,
    val createdAtMillis: Long?,
    val phase: NoteMaterialPhase,
    val problem: NoteMaterialProblem? = null,
    val fileCount: Int = 0,
    val sizeBytes: Long = 0,
    val recordingCount: Int = 0,
    val segmentCount: Int = 0,
    val recordings: List<RecordingSession> = emptyList()
)

data class NoteMaterialsState(
    val loaded: Boolean = false,
    val entries: List<NoteMaterialEntry> = emptyList(),
    val loadFailed: Boolean = false
)

internal fun noteMaterialIdentity(note: NoteDocument, recordings: List<RecordingSession>): String = materialDigest(buildJsonObject {
    put("note", note.source)
    put("recordings", NoteJson.encodeToJsonElement(recordings.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))))
}.toString())

internal fun NoteMaterialEntry.matches(note: NoteDocument, sessions: List<RecordingSession>) =
    noteId == note.id && revision == note.revision && recordings == sessions

package com.pim.app.notes

import com.pim.core.contract.NoteMaterialUploadDto
import com.pim.core.contract.NoteMaterialUploadFileDto
import kotlinx.serialization.Serializable

/**
 * Bounded, server-derived material metadata used by conflict review. Local input
 * bytes remain authoritative for the fixed version; this summary never replaces
 * or rewrites them.
 */
@Serializable
data class NoteMaterialVersionSummary(
    val versionId: String,
    val noteId: String,
    val title: String,
    val revision: Long,
    val createdAtMillis: Long,
    val fileCount: Int,
    val sizeBytes: Long,
    val recordingCount: Int,
    val segmentCount: Int,
    val serverPhase: String? = null,
    val isCurrent: Boolean? = null,
    val hasConflict: Boolean? = null,
)

internal fun noteMaterialVersionSummary(
    inputJson: String,
    manifestJson: String,
    upload: NoteMaterialUploadDto?,
): NoteMaterialVersionSummary {
    val input = NoteJson.decodeFromString<NoteMaterialInput>(inputJson).also { it.document.validate() }
    val manifest = NoteJson.decodeFromString<NoteMaterialManifest>(manifestJson)
    require(input.id == manifest.versionId && input.document.id == manifest.noteId &&
        input.document.revision == manifest.revision && materialDigest(inputJson) == manifest.inputSha256) {
        "Fixed material summary does not match its manifest"
    }
    if (upload != null) {
        require(upload.versionId == input.id && upload.noteId == input.document.id)
        require(upload.files.size == manifest.files.size)
        upload.files.forEachIndexed { index, file ->
            val pinned = manifest.files[index]
            require(file.index == index && file.path == pinned.path && file.kind == pinned.kind.name &&
                file.sha256 == pinned.sha256 && file.sizeBytes == pinned.sizeBytes)
        }
    }
    return NoteMaterialVersionSummary(
        versionId = input.id,
        noteId = input.document.id,
        title = input.document.title.ifBlank { "无标题笔记" },
        revision = input.document.revision,
        createdAtMillis = input.createdAtMillis,
        fileCount = manifest.files.size,
        sizeBytes = manifest.files.sumOf { it.sizeBytes },
        recordingCount = input.recordings.size,
        segmentCount = input.recordings.sumOf { session -> session.chunks.map { it.segment }.distinct().size },
        serverPhase = upload?.phase,
        isCurrent = upload?.isCurrent,
        hasConflict = upload?.hasConflict,
    )
}

/**
 * Counts only parts the server explicitly listed. Local bytes are never used as
 * proof that a whole version was received.
 */
internal fun NoteMaterialUploadDto.confirmedBytes(): Long {
    val partSize = limits.partSizeBytes
    require(partSize > 0)
    return files.sumOf { file ->
        require(file.sizeBytes >= 0 && file.partCount == ((file.sizeBytes + partSize - 1) / partSize).toInt())
        require(file.uploadedParts.distinct().size == file.uploadedParts.size &&
            file.uploadedParts.all { it in 0 until file.partCount })
        file.uploadedParts.sumOf { part ->
            minOf(partSize.toLong(), file.sizeBytes - part.toLong() * partSize)
        }
    }
}

internal fun NoteMaterialUploadDto.withConfirmedPart(fileIndex: Int, partIndex: Int): NoteMaterialUploadDto {
    val file = files.single { it.index == fileIndex }
    require(partIndex in 0 until file.partCount && partIndex !in file.uploadedParts)
    return copy(files = files.map { current ->
        if (current.index == fileIndex) current.copy(uploadedParts = (current.uploadedParts + partIndex).sorted())
        else current
    })
}

internal fun NoteMaterialUploadFileDto.isComplete() =
    partCount >= 0 && uploadedParts.distinct().size == partCount && uploadedParts.all { it in 0 until partCount }

/**
 * Applies one exact server view without deleting local summaries or fixed
 * material. A prior explicit choice remains resolved only while the server still
 * reports that selected version as current.
 */
internal fun NoteUploadJob.withServerReceipt(
    upload: NoteMaterialUploadDto,
    remoteVersion: NoteMaterialVersionSummary? = conflictRemoteVersion,
    promotedVersionId: String? = resolutionVersionId,
    problem: String? = this.problem,
): NoteUploadJob {
    require(upload.versionId == versionId && upload.noteId == noteId && upload.baseVersionId == baseVersionId)
    val bytes = upload.confirmedBytes()
    require(bytes in 0..totalBytes)
    val resolved = promotedVersionId?.takeIf { upload.currentVersionId == it }
    val openConflict = upload.hasConflict && resolved == null
    val nextPhase = when {
        openConflict -> NoteUploadPhase.Conflict
        upload.phase == "Uploaded" || upload.phase == "Conflict" -> NoteUploadPhase.Uploaded
        upload.phase == "Uploading" -> when (phase) {
            NoteUploadPhase.Paused -> NoteUploadPhase.Paused
            NoteUploadPhase.WaitingForNetwork -> NoteUploadPhase.WaitingForNetwork
            NoteUploadPhase.NeedsLogin -> NoteUploadPhase.NeedsLogin
            NoteUploadPhase.Failed -> NoteUploadPhase.Failed
            else -> NoteUploadPhase.Uploading
        }
        else -> phase
    }
    val remote = when {
        !upload.hasConflict -> null
        resolved != null -> remoteVersion?.takeIf { it.versionId == resolved }
        else -> remoteVersion
    }
    return copy(
        phase = nextPhase,
        uploadedBytes = bytes,
        currentVersionId = upload.currentVersionId,
        serverUpload = upload,
        conflictRemoteVersion = remote,
        resolutionVersionId = resolved,
        problem = if (nextPhase == NoteUploadPhase.Failed && upload.phase == "Uploading") {
            problem ?: this.problem
        } else {
            problem
        },
    )
}

internal fun uploadReceiptPhaseText(job: NoteUploadJob): String {
    val upload = job.serverUpload ?: return "尚无服务器分块回执；本地累计字节不视为完整确认"
    val confirmed = if (upload.files.any { it.uploadedParts.isNotEmpty() }) "分块已确认" else "尚无服务器分块回执"
    return when (val phase = upload.phase) {
        "Uploading" -> "$confirmed；整版尚未确认"
        "Uploaded" -> if (upload.files.any { it.uploadedParts.isNotEmpty() }) "整版已确认"
        else "整版已确认；尚无服务器分块回执"
        "Conflict" -> {
            val whole = if (upload.files.isNotEmpty() && upload.files.all { it.isComplete() }) "整版已确认" else confirmed
            if (job.resolutionVersionId != null) "$whole；冲突已解决，当前保留另一版本"
            else "$whole；存在版本冲突"
        }
        else -> "$confirmed；服务器 phase=$phase；整版未确认"
    }
}

internal fun formatUploadedPartIndexes(parts: List<Int>): String {
    val sorted = parts.distinct().sorted()
    if (sorted.isEmpty()) return "无"
    val ranges = mutableListOf<IntRange>()
    var start = sorted.first()
    var previous = start
    for (part in sorted.drop(1)) {
        if (part == previous + 1) {
            previous = part
            continue
        }
        ranges += start..previous
        start = part
        previous = part
    }
    ranges += start..previous
    return ranges.joinToString(", ") { range ->
        if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
    }
}

internal fun processingStatusLabel(
    label: String,
    asr: NoteAsrTaskRecord?,
    artifact: NoteArtifactTaskRecord?,
    target: NoteUploadTarget? = null,
): String {
    asr?.let {
        return if (target != null && !it.isBoundTo(target)) "$label：原账号任务，当前不可访问"
        else "$label：${it.statusView(target).label}"
    }
    artifact?.let {
        return if (target != null && it.binding != target.binding) "$label：原账号任务，当前不可访问"
        else "$label：${it.remote?.phase ?: it.localPhase.name}"
    }
    return "$label：未发起"
}

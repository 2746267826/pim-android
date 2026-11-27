package com.pim.app.notes

import java.io.FileNotFoundException
import java.io.IOException

internal enum class NoteSaveState { Saving, SavedLocally, NeedsReview, SaveFailed }

internal fun noteSaveState(saved: Boolean, saveFailed: Boolean, needsReview: Boolean): NoteSaveState = when {
    saveFailed -> NoteSaveState.SaveFailed
    needsReview -> NoteSaveState.NeedsReview
    saved -> NoteSaveState.SavedLocally
    else -> NoteSaveState.Saving
}

internal fun noteSaveStateLabel(state: NoteSaveState): String = when (state) {
    NoteSaveState.Saving -> "保存中"
    NoteSaveState.SavedLocally -> "已保存本机"
    NoteSaveState.NeedsReview -> "待核对正文"
    NoteSaveState.SaveFailed -> "保存失败"
}

/**
 * Keeps the visible reason useful without exposing filesystem paths or raw exception text.
 * The top status must describe the actual operation that failed, never a server sync result.
 */
internal fun noteSaveProblemText(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        error is SecurityException -> "没有写入本机存储的权限"
        message.contains("Permission denied", ignoreCase = true) -> "没有写入本机存储的权限"
        error is FileNotFoundException -> "本机存储目录不可用"
        message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true) -> "本机存储空间不足"
        error is IOException -> "本机文件写入失败"
        else -> "本机保存发生异常"
    }
}

internal enum class NoteUploadNoticeTone { Error, Warning, Info }

internal data class NoteUploadNotice(val label: String, val tone: NoteUploadNoticeTone)

private val noteUploadPhaseOrder = mapOf(
    NoteUploadPhase.Conflict to 8,
    NoteUploadPhase.Failed to 7,
    NoteUploadPhase.NeedsLogin to 6,
    NoteUploadPhase.Paused to 5,
    NoteUploadPhase.WaitingForNetwork to 4,
    NoteUploadPhase.Uploading to 3,
    NoteUploadPhase.Queued to 2,
    NoteUploadPhase.Uploaded to 1,
)

/**
 * The newest job is the current task for this note. Phase order only breaks a timestamp tie so
 * tests and UI remain deterministic; an older conflict does not outrank a newer successful upload.
 */
internal fun latestUploadJobForNote(jobs: List<NoteUploadJob>, noteId: String): NoteUploadJob? =
    jobs.filter { it.noteId == noteId }.maxWithOrNull(
        compareBy<NoteUploadJob> { it.createdAtMillis }
            .thenBy { noteUploadPhaseOrder[it.phase] ?: 0 }
            .thenBy { it.id },
    )

internal fun noteUploadNotice(job: NoteUploadJob?): NoteUploadNotice? = when (job?.phase) {
    NoteUploadPhase.Conflict -> NoteUploadNotice("上传冲突 · 需核对版本", NoteUploadNoticeTone.Error)
    NoteUploadPhase.Failed -> NoteUploadNotice("上传未完成 · 需重试", NoteUploadNoticeTone.Error)
    NoteUploadPhase.NeedsLogin -> NoteUploadNotice("上传需原账号登录", NoteUploadNoticeTone.Warning)
    NoteUploadPhase.Paused -> NoteUploadNotice("上传已暂停", NoteUploadNoticeTone.Info)
    NoteUploadPhase.WaitingForNetwork -> NoteUploadNotice("等待网络上传", NoteUploadNoticeTone.Info)
    NoteUploadPhase.Uploading -> NoteUploadNotice("正在上传材料", NoteUploadNoticeTone.Info)
    NoteUploadPhase.Queued -> NoteUploadNotice("等待上传材料", NoteUploadNoticeTone.Info)
    NoteUploadPhase.Uploaded, null -> null
}

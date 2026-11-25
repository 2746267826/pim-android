package com.pim.app.notes

import com.pim.app.recording.RecordingPermissions

internal sealed interface RecordingStartDecision {
    data object WaitForDocument : RecordingStartDecision

    data class Start(val noteId: String) : RecordingStartDecision

    data class Stop(val message: String) : RecordingStartDecision
}

internal object RecordingStartPolicy {
    fun decide(
        requestedNoteId: String,
        selectedNoteId: String?,
        notesLoaded: Boolean,
        notesLoadFailed: Boolean,
        documentLoaded: Boolean,
        noteSaved: Boolean,
        recordingBusy: Boolean,
        permissions: RecordingPermissions,
    ): RecordingStartDecision {
        if (notesLoadFailed) {
            return RecordingStartDecision.Stop("本机笔记初始化失败，未开始此前请求的录音；请重新打开笔记后重试。")
        }
        if (!notesLoaded) return RecordingStartDecision.WaitForDocument
        if (selectedNoteId != requestedNoteId) {
            return RecordingStartDecision.Stop("已切换到其他笔记，未开始此前请求的录音。")
        }
        if (!documentLoaded) {
            return RecordingStartDecision.Stop("未能读取此前请求的笔记，录音未开始；请返回该笔记后重试。")
        }
        if (recordingBusy) {
            return RecordingStartDecision.Stop("已有录音正在开启或进行中，未开始此前请求的录音。")
        }
        if (!noteSaved) {
            return RecordingStartDecision.Stop("请先等待本地保存完成，再开始录音。")
        }
        if (!permissions.canStart) {
            return RecordingStartDecision.Stop(permissions.message)
        }
        return RecordingStartDecision.Start(requestedNoteId)
    }
}

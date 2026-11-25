package com.pim.app.notes

import com.pim.app.recording.PendingRecordingRequest
import com.pim.app.recording.RecordingPermissions
import com.pim.app.recording.RecordingRequestPhase

internal sealed interface RecordingRestoreDecision {
    data object NothingToRecover : RecordingRestoreDecision

    data object RetryOnce : RecordingRestoreDecision

    data class Discard(val message: String) : RecordingRestoreDecision
}

/**
 * A permission dialog result cannot survive process death. When the recreated activity restores
 * the permission phase, retry only if every prerequisite is already satisfied; otherwise drop the
 * dangling request and explain why so the gate cannot block future recording attempts.
 */
internal object RecordingRestorePolicy {
    fun decide(
        request: PendingRecordingRequest?,
        permissions: RecordingPermissions,
    ): RecordingRestoreDecision {
        if (request?.phase != RecordingRequestPhase.AwaitingPermission) {
            return RecordingRestoreDecision.NothingToRecover
        }
        if (permissions.canStart) return RecordingRestoreDecision.RetryOnce
        return RecordingRestoreDecision.Discard(
            "上次录音权限请求在页面重建时中断，录音没有开始。${permissions.message}",
        )
    }
}

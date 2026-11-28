package com.pim.app.recording

internal object RecordingRecoveryPolicy {
    /**
     * C13: a recoverable microphone interruption may reopen the input once. Permission loss,
     * storage pressure and an invisible notification surface are never retried automatically.
     */
    fun shouldRetry(
        problem: RecordingProblem,
        automaticAttempts: Int,
        permissions: RecordingPermissions,
        storageAvailable: Boolean,
    ): Boolean = automaticAttempts == 0 &&
        problem in setOf(RecordingProblem.Input, RecordingProblem.Silenced) &&
        permissions.canStart &&
        storageAvailable
}

package com.pim.app.recording

import java.io.IOException

internal object RecordingStoragePolicy {
    const val MIN_START_BYTES = 64L * 1024 * 1024
    const val MIN_CONTINUE_BYTES = 16L * 1024 * 1024

    fun canStart(availableBytes: Long): Boolean = availableBytes >= MIN_START_BYTES
    fun canContinue(availableBytes: Long): Boolean = availableBytes >= MIN_CONTINUE_BYTES

    fun problemFor(error: Throwable): RecordingProblem = when (error) {
        is SecurityException -> RecordingProblem.Permission
        is IOException -> RecordingProblem.Storage
        else -> RecordingProblem.Input
    }
}

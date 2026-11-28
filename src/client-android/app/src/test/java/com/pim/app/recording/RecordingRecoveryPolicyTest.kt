package com.pim.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRecoveryPolicyTest {
    private val ready = RecordingPermissions()

    @Test fun recoverableInputAndSilenceMayReopenExactlyOnce() {
        for (problem in listOf(RecordingProblem.Input, RecordingProblem.Silenced)) {
            assertTrue(RecordingRecoveryPolicy.shouldRetry(problem, 0, ready, storageAvailable = true))
            assertFalse(RecordingRecoveryPolicy.shouldRetry(problem, 1, ready, storageAvailable = true))
        }
    }

    @Test fun permissionStorageForegroundAndProcessLossNeverAutoRetry() {
        for (problem in listOf(
            RecordingProblem.Permission,
            RecordingProblem.Notifications,
            RecordingProblem.NotificationChannel,
            RecordingProblem.Storage,
            RecordingProblem.Foreground,
            RecordingProblem.ProcessEnded,
            RecordingProblem.Damaged,
            RecordingProblem.Empty,
        )) {
            assertFalse(problem.name, RecordingRecoveryPolicy.shouldRetry(problem, 0, ready, storageAvailable = true))
        }
    }

    @Test fun retryRequiresVisibleNotificationAndStorage() {
        assertFalse(RecordingRecoveryPolicy.shouldRetry(
            RecordingProblem.Input, 0,
            RecordingPermissions(notificationPermissionGranted = false),
            storageAvailable = true,
        ))
        assertFalse(RecordingRecoveryPolicy.shouldRetry(
            RecordingProblem.Input, 0,
            RecordingPermissions(channelEnabled = false),
            storageAvailable = true,
        ))
        assertFalse(RecordingRecoveryPolicy.shouldRetry(
            RecordingProblem.Input, 0,
            ready,
            storageAvailable = false,
        ))
    }
}

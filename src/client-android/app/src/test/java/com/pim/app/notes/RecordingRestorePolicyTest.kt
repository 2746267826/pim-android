package com.pim.app.notes

import com.pim.app.recording.PendingRecordingRequest
import com.pim.app.recording.RecordingPermissions
import com.pim.app.recording.RecordingRequestGate
import com.pim.app.recording.RecordingRequestPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRestorePolicyTest {
    private val ready = RecordingPermissions()

    @Test fun grantedPermissionRecoversTheRestoredRequestExactlyOnce() {
        val gate = gateWaitingForPermission()
        assertEquals(
            RecordingRestoreDecision.RetryOnce,
            RecordingRestorePolicy.decide(gate.pending(), ready),
        )

        assertNotNull(gate.claimRestoredPermissionWait())
        assertNull(gate.claimRestoredPermissionWait())
        assertEquals(RecordingRequestPhase.AwaitingDocument, gate.pending()?.phase)
        assertEquals(RecordingStartDecision.Start("note-a"), startDecision())

        assertTrue(gate.complete("note-a"))
        assertNull(gate.pending())
        assertEquals(
            RecordingRestoreDecision.NothingToRecover,
            RecordingRestorePolicy.decide(gate.pending(), ready),
        )
        assertNull(gate.onPermissionResult())
    }

    @Test fun missingPermissionDropsTheDanglingRequestAndAllowsAReplacement() {
        val gate = gateWaitingForPermission()
        val missing = RecordingPermissions(
            microphoneGranted = false,
            notificationPermissionGranted = false,
        )

        val decision = RecordingRestorePolicy.decide(gate.pending(), missing)
        assertTrue(decision is RecordingRestoreDecision.Discard)
        val message = (decision as RecordingRestoreDecision.Discard).message
        assertTrue(message.contains("页面重建时中断"))
        assertTrue(message.contains("麦克风权限"))
        assertTrue(message.contains("通知权限"))
        assertTrue(message.contains("再点「开始录音」"))

        gate.cancel()
        assertNull(gate.pending())
        assertTrue(gate.begin("note-a"))
        assertEquals(RecordingRequestPhase.AwaitingPermission, gate.pending()?.phase)
    }

    @Test fun documentWaitIsLeftToTheExistingInitializationFlow() {
        val gate = RecordingRequestGate()
        gate.restore(PendingRecordingRequest("note-a", RecordingRequestPhase.AwaitingDocument))

        assertEquals(
            RecordingRestoreDecision.NothingToRecover,
            RecordingRestorePolicy.decide(gate.pending(), ready),
        )
        assertNull(gate.claimRestoredPermissionWait())
        assertEquals(RecordingRequestPhase.AwaitingDocument, gate.pending()?.phase)
        assertTrue(gate.complete("note-a"))
    }

    @Test fun duplicateRecoveryDoesNotStartOrReopenTheGate() {
        val gate = gateWaitingForPermission()

        assertNotNull(gate.claimRestoredPermissionWait())
        assertNull(gate.claimRestoredPermissionWait())
        assertFalse(gate.begin("note-b"))
        assertEquals("note-a", gate.pending()?.noteId)

        assertTrue(gate.complete("note-a"))
        assertTrue(gate.begin("note-b"))
    }

    private fun gateWaitingForPermission(): RecordingRequestGate = RecordingRequestGate().apply {
        restore(PendingRecordingRequest("note-a", RecordingRequestPhase.AwaitingPermission))
    }

    private fun startDecision() = RecordingStartPolicy.decide(
        requestedNoteId = "note-a",
        selectedNoteId = "note-a",
        notesLoaded = true,
        notesLoadFailed = false,
        documentLoaded = true,
        noteSaved = true,
        recordingBusy = false,
        permissions = ready,
    )
}

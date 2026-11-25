package com.pim.app.notes

import com.pim.app.recording.RecordingPermissions
import com.pim.app.recording.RecordingRequestGate
import com.pim.app.recording.RecordingRequestPhase
import com.pim.app.recording.PendingRecordingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartPolicyTest {
    private val ready = RecordingPermissions()

    @Test fun permissionCallbackWaitsForAsyncDocumentInitializationInsteadOfSilentlyDropping() {
        assertEquals(
            RecordingStartDecision.WaitForDocument,
            decide(notesLoaded = false, documentLoaded = false),
        )
        assertEquals(
            RecordingStartDecision.WaitForDocument,
            decide(notesLoaded = false, documentLoaded = false, noteSaved = false),
        )
    }

    @Test fun initializedDocumentStartsExactlyOnceForTheRequestedNote() {
        val gate = RecordingRequestGate()
        gate.begin("note-a")
        gate.onPermissionResult()

        assertEquals(RecordingStartDecision.Start("note-a"), decide())
        assertTrue(gate.complete("note-a"))
        assertNull(gate.pending())
        assertNull(gate.onPermissionResult())
    }

    @Test fun restoredDocumentWaitKeepsTheSameNoteUntilTheDecisionCompletes() {
        val gate = RecordingRequestGate()
        gate.restore(PendingRecordingRequest("note-a", RecordingRequestPhase.AwaitingDocument))

        assertEquals(RecordingStartDecision.WaitForDocument, decide(notesLoaded = false, documentLoaded = false))
        assertEquals("note-a", gate.pending()?.noteId)
        assertEquals(RecordingStartDecision.Start("note-a"), decide())
        assertTrue(gate.complete("note-a"))
    }

    @Test fun anotherSelectedNoteNeverStartsTheRestoredRequest() {
        val decision = decide(selectedNoteId = "note-b")
        assertTrue(decision is RecordingStartDecision.Stop)
        assertEquals("已切换到其他笔记，未开始此前请求的录音。", (decision as RecordingStartDecision.Stop).message)
    }

    @Test fun loadedButMissingDocumentStopsWithVisibleMessage() {
        val decision = decide(documentLoaded = false)
        assertTrue(decision is RecordingStartDecision.Stop)
    }

    @Test fun failedInitializationDoesNotLeaveAnInvisiblePendingRequest() {
        val decision = decide(notesLoaded = false, notesLoadFailed = true, documentLoaded = false)
        assertTrue(decision is RecordingStartDecision.Stop)
    }

    @Test fun unsavedBusyOrStillUnauthorizedNoteDoesNotStart() {
        assertTrue(decide(noteSaved = false) is RecordingStartDecision.Stop)
        assertTrue(decide(recordingBusy = true) is RecordingStartDecision.Stop)
        assertTrue(
            decide(permissions = RecordingPermissions(microphoneGranted = false)) is RecordingStartDecision.Stop
        )
    }

    private fun decide(
        requestedNoteId: String = "note-a",
        selectedNoteId: String? = requestedNoteId,
        notesLoaded: Boolean = true,
        notesLoadFailed: Boolean = false,
        documentLoaded: Boolean = true,
        noteSaved: Boolean = true,
        recordingBusy: Boolean = false,
        permissions: RecordingPermissions = ready,
    ) = RecordingStartPolicy.decide(
        requestedNoteId = requestedNoteId,
        selectedNoteId = selectedNoteId,
        notesLoaded = notesLoaded,
        notesLoadFailed = notesLoadFailed,
        documentLoaded = documentLoaded,
        noteSaved = noteSaved,
        recordingBusy = recordingBusy,
        permissions = permissions,
    )
}

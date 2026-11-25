package com.pim.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRequestGateTest {
    @Test fun duplicateLaunchAndCallbackDeliveryProduceOneRequest() {
        val gate = RecordingRequestGate()

        assertTrue(gate.begin("note-a"))
        assertFalse(gate.begin("note-b"))
        assertEquals("note-a", gate.pending()?.noteId)
        assertEquals(RecordingRequestPhase.AwaitingPermission, gate.pending()?.phase)

        val firstResult = gate.onPermissionResult()
        assertEquals("note-a", firstResult?.noteId)
        assertEquals(RecordingRequestPhase.AwaitingDocument, firstResult?.phase)
        assertEquals(firstResult, gate.onPermissionResult())

        assertTrue(gate.complete("note-a"))
        assertNull(gate.pending())
        assertNull(gate.onPermissionResult())
        assertTrue(gate.begin("note-b"))
    }

    @Test fun restoredDocumentWaitSurvivesRecreationUntilNotesAreReady() {
        val gate = RecordingRequestGate()
        gate.restore(PendingRecordingRequest("note-a", RecordingRequestPhase.AwaitingDocument))

        assertEquals("note-a", gate.pending()?.noteId)
        assertEquals(RecordingRequestPhase.AwaitingDocument, gate.pending()?.phase)
        assertFalse(gate.complete("note-b"))
        assertEquals("note-a", gate.pending()?.noteId)
        assertTrue(gate.complete("note-a"))
    }

    @Test fun cancelledRequestAllowsANewLaunch() {
        val gate = RecordingRequestGate()
        assertTrue(gate.begin("note-a"))
        gate.cancel()
        assertTrue(gate.begin("note-b"))
        assertEquals(PendingRecordingRequest("note-b", RecordingRequestPhase.AwaitingPermission), gate.pending())
    }
}

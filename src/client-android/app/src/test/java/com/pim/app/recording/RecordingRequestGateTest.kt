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
        assertEquals("note-a", gate.pendingNoteId())
        assertEquals("note-a", gate.consume())
        assertNull(gate.consume())
        assertTrue(gate.begin("note-b"))
    }

    @Test fun restoredRequestIsConsumedOnlyOnce() {
        val gate = RecordingRequestGate()
        gate.restore("note-a")

        assertEquals("note-a", gate.consume())
        assertNull(gate.consume())
    }

    @Test fun cancelledRequestAllowsANewLaunch() {
        val gate = RecordingRequestGate()
        assertTrue(gate.begin("note-a"))
        gate.cancel()
        assertTrue(gate.begin("note-b"))
        assertEquals("note-b", gate.consume())
    }
}

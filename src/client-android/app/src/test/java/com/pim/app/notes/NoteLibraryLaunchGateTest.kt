package com.pim.app.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteLibraryLaunchGateTest {
    @Test fun repeatedLibraryTapsCreateAtMostOneNoteUntilTheScreenReturns() {
        var created = 0
        val gate = NoteLibraryLaunchGate {
            created++
            "note-$created"
        }

        assertEquals("note-1", gate.launch())
        assertNull(gate.launch())
        assertEquals(1, created)

        gate.finish()
        assertEquals("note-2", gate.launch())
        assertEquals(2, created)
    }

    @Test fun failedCreationDoesNotLeaveTheGateClosed() {
        var attempts = 0
        val gate = NoteLibraryLaunchGate {
            attempts++
            if (attempts == 1) null else "note-ready"
        }

        assertNull(gate.launch())
        assertEquals("note-ready", gate.launch())
    }
}

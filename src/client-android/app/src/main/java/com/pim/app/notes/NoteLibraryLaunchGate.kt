package com.pim.app.notes

internal class NoteLibraryLaunchGate(private val create: () -> String?) {
    private var inFlight = false

    @Synchronized
    fun launch(): String? {
        if (inFlight) return null
        val noteId = create() ?: return null
        inFlight = true
        return noteId
    }

    @Synchronized
    fun finish() {
        inFlight = false
    }
}

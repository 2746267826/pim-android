package com.pim.app.recording

internal class RecordingRequestGate {
    private var pendingNoteIdValue: String? = null

    @Synchronized
    fun begin(noteId: String): Boolean {
        if (pendingNoteIdValue != null) return false
        pendingNoteIdValue = noteId
        return true
    }

    @Synchronized
    fun consume(): String? {
        val pending = pendingNoteIdValue
        pendingNoteIdValue = null
        return pending
    }

    @Synchronized
    fun restore(noteId: String) {
        pendingNoteIdValue = noteId
    }

    @Synchronized
    fun cancel() {
        pendingNoteIdValue = null
    }

    @Synchronized
    fun pendingNoteId(): String? = pendingNoteIdValue
}

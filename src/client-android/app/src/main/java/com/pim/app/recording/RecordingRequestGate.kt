package com.pim.app.recording

internal enum class RecordingRequestPhase {
    AwaitingPermission,
    AwaitingDocument,
}

internal data class PendingRecordingRequest(
    val noteId: String,
    val phase: RecordingRequestPhase,
)

internal class RecordingRequestGate {
    private var pendingValue: PendingRecordingRequest? = null

    @Synchronized
    fun begin(noteId: String): Boolean {
        if (pendingValue != null) return false
        pendingValue = PendingRecordingRequest(noteId, RecordingRequestPhase.AwaitingPermission)
        return true
    }

    /**
     * The activity result callback may be delivered again after recreation. Keep the same
     * note in the document-waiting phase instead of dropping it or starting a different note.
     */
    @Synchronized
    fun onPermissionResult(): PendingRecordingRequest? {
        val pending = pendingValue ?: return null
        if (pending.phase == RecordingRequestPhase.AwaitingPermission) {
            pendingValue = pending.copy(phase = RecordingRequestPhase.AwaitingDocument)
        }
        return pendingValue
    }

    @Synchronized
    fun complete(noteId: String): Boolean {
        if (pendingValue?.noteId != noteId) return false
        pendingValue = null
        return true
    }

    @Synchronized
    fun restore(request: PendingRecordingRequest) {
        pendingValue = request
    }

    @Synchronized
    fun cancel() {
        pendingValue = null
    }

    @Synchronized
    fun pending(): PendingRecordingRequest? = pendingValue
}

package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import kotlinx.serialization.Serializable

/** Local product kinds only. These values are not a claim about the future wire contract. */
@Serializable
enum class NoteArtifactKind {
    FullRecord,
    Analysis,
}

/**
 * A server-provided candidate held locally until the user explicitly accepts it.
 * Server identifiers remain opaque strings and are never parsed as local IDs.
 */
@Serializable
data class NoteArtifactRemoteSnapshot(
    val text: String,
    val baseServerArtifactVersionId: String? = null,
    val expectedServerRevision: String? = null,
    val observedAtMillis: Long,
)

/**
 * One local manual draft for an exact owner/server/note/material/kind scope.
 * [text] is the recoverable working copy; [savedText] is the last explicit local save.
 */
@Serializable
data class NoteArtifactDraft(
    val localDraftId: String,
    val serverIdentity: String,
    val ownerId: String,
    val noteId: String,
    val materialVersionId: String,
    val kind: NoteArtifactKind,
    val localRevision: Long,
    val text: String,
    val savedText: String,
    val dirty: Boolean,
    val baseServerArtifactVersionId: String? = null,
    val expectedServerRevision: String? = null,
    val pendingServer: NoteArtifactRemoteSnapshot? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val savedAtMillis: Long,
) {
    val binding: BoundUploadSession get() = BoundUploadSession(serverIdentity, ownerId)
}

@Serializable
data class NoteArtifactDraftConflict(
    val conflictId: String,
    val localDraftId: String,
    val serverIdentity: String,
    val ownerId: String,
    val noteId: String,
    val materialVersionId: String,
    val kind: NoteArtifactKind,
    val expectedLocalRevision: Long,
    val attemptedText: String,
    val current: NoteArtifactDraft,
    val createdAtMillis: Long,
)

data class NoteArtifactDraftsState(
    val loaded: Boolean = false,
    val drafts: List<NoteArtifactDraft> = emptyList(),
    val loadFailed: Boolean = false,
)

sealed interface NoteArtifactDraftWriteResult {
    data class Written(val draft: NoteArtifactDraft) : NoteArtifactDraftWriteResult

    /** The current draft was not changed; the complete attempted text is preserved on disk. */
    data class Conflict(val preserved: NoteArtifactDraftConflict) : NoteArtifactDraftWriteResult
}

sealed interface NoteArtifactRemoteAcceptResult {
    data class Accepted(val draft: NoteArtifactDraft) : NoteArtifactRemoteAcceptResult

    /** A dirty local draft always wins until it is explicitly saved and the remote candidate is accepted. */
    data class BlockedByDirty(val draft: NoteArtifactDraft) : NoteArtifactRemoteAcceptResult
    data class StaleRevision(val draft: NoteArtifactDraft) : NoteArtifactRemoteAcceptResult
    data class NoPendingSnapshot(val draft: NoteArtifactDraft) : NoteArtifactRemoteAcceptResult
}

sealed interface NoteArtifactServerAcceptResult {
    data class Accepted(val draft: NoteArtifactDraft) : NoteArtifactServerAcceptResult
    data class StaleRevision(val draft: NoteArtifactDraft) : NoteArtifactServerAcceptResult
}

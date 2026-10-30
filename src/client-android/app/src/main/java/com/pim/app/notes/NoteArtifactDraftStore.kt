package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import java.io.File
import kotlinx.serialization.encodeToString

/**
 * Durable, account-isolated manual drafts for derived note results.
 * This store has no network or generated-contract dependency.
 */
class NoteArtifactDraftStore(private val root: File) {
    fun load(binding: BoundUploadSession): NoteArtifactDraftsState = synchronized(lock) {
        val draftsRoot = draftsRoot(binding)
        if (!draftsRoot.exists()) return@synchronized NoteArtifactDraftsState(loaded = true)
        var damaged = false
        val drafts = draftsRoot.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map { baseFile(it) }
            .distinct()
            .mapNotNull { file ->
                try {
                    readFile(file, binding)
                } catch (_: Exception) {
                    damaged = true
                    null
                }
            }
            .sortedByDescending { it.updatedAtMillis }
            .toList()
        NoteArtifactDraftsState(loaded = true, drafts = drafts, loadFailed = damaged)
    }

    fun drafts(binding: BoundUploadSession): List<NoteArtifactDraft> = load(binding).drafts

    fun getOrCreate(
        binding: BoundUploadSession,
        noteId: String,
        materialVersionId: String,
        kind: NoteArtifactKind,
        initialText: String = "",
        baseServerArtifactVersionId: String? = null,
        expectedServerRevision: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactDraft = synchronized(lock) {
        require(validArtifactId(noteId) && validArtifactId(materialVersionId))
        requireOpaqueServerId(baseServerArtifactVersionId)
        requireOpaqueServerId(expectedServerRevision)
        val folder = scopeFolder(binding, noteId, materialVersionId, kind)
        val existing = folder.listFiles()
            .orEmpty()
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map(::baseFile)
            .distinct()
        require(existing.size <= 1) { "More than one local draft exists for the same artifact scope" }
        if (existing.isNotEmpty()) {
            return@synchronized readFile(existing.single(), binding).also { draft ->
                require(draft.noteId == noteId && draft.materialVersionId == materialVersionId && draft.kind == kind)
            }
        }
        val timestamp = requireNonNegative(nowMillis)
        val draft = NoteArtifactDraft(
            localDraftId = newNoteId(),
            serverIdentity = binding.serverIdentity,
            ownerId = binding.ownerId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            kind = kind,
            localRevision = 0,
            text = initialText,
            savedText = initialText,
            dirty = false,
            baseServerArtifactVersionId = baseServerArtifactVersionId,
            expectedServerRevision = expectedServerRevision,
            createdAtMillis = timestamp,
            updatedAtMillis = timestamp,
            savedAtMillis = timestamp,
        )
        writeDraft(draft)
        draft
    }

    fun read(binding: BoundUploadSession, localDraftId: String): NoteArtifactDraft = synchronized(lock) {
        require(validArtifactId(localDraftId))
        val file = bindingRoot(binding).walkTopDown()
            .filter { it.isFile && baseFile(it).name == "$localDraftId.json" }
            .map(::baseFile)
            .distinct()
            .singleOrNull()
            ?: throw IllegalArgumentException("Local artifact draft was not found for this binding")
        readFile(file, binding)
    }

    /**
     * Persists an editable working copy and advances the local CAS revision when text changes.
     * Identical text is idempotent; [save] remains a separate explicit local save.
     */
    fun stageText(
        binding: BoundUploadSession,
        localDraftId: String,
        expectedLocalRevision: Long,
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactDraftWriteResult = synchronized(lock) {
        require(expectedLocalRevision >= 0)
        requireNonNegative(nowMillis)
        val current = read(binding, localDraftId)
        if (current.localRevision != expectedLocalRevision) {
            return@synchronized conflict(current, expectedLocalRevision, text, nowMillis)
        }
        if (current.text == text) return@synchronized NoteArtifactDraftWriteResult.Written(current)
        val next = current.copy(
            localRevision = current.localRevision + 1,
            text = text,
            dirty = text != current.savedText,
            updatedAtMillis = maxOf(current.updatedAtMillis, nowMillis),
        )
        writeDraft(next)
        NoteArtifactDraftWriteResult.Written(next)
    }

    fun save(
        binding: BoundUploadSession,
        localDraftId: String,
        expectedLocalRevision: Long,
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactDraftWriteResult = synchronized(lock) {
        require(expectedLocalRevision >= 0)
        requireNonNegative(nowMillis)
        val current = read(binding, localDraftId)
        if (current.localRevision != expectedLocalRevision) {
            return@synchronized conflict(current, expectedLocalRevision, text, nowMillis)
        }
        val timestamp = maxOf(current.updatedAtMillis, nowMillis)
        val next = current.copy(
            localRevision = current.localRevision + 1,
            text = text,
            savedText = text,
            dirty = false,
            updatedAtMillis = timestamp,
            savedAtMillis = timestamp,
        )
        writeDraft(next)
        NoteArtifactDraftWriteResult.Written(next)
    }

    /**
     * Stores a refresh/new-generation candidate without touching local text.
     * Dirty drafts therefore cannot be overwritten by a background observation.
     */
    fun observeRemote(
        binding: BoundUploadSession,
        localDraftId: String,
        snapshot: NoteArtifactRemoteSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactDraft = synchronized(lock) {
        requireNonNegative(nowMillis)
        requireOpaqueServerId(snapshot.baseServerArtifactVersionId)
        requireOpaqueServerId(snapshot.expectedServerRevision)
        requireNonNegative(snapshot.observedAtMillis)
        val current = read(binding, localDraftId)
        val next = current.copy(
            pendingServer = snapshot,
            updatedAtMillis = maxOf(current.updatedAtMillis, nowMillis, snapshot.observedAtMillis),
        )
        writeDraft(next)
        next
    }

    fun acceptRemote(
        binding: BoundUploadSession,
        localDraftId: String,
        expectedLocalRevision: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactRemoteAcceptResult = synchronized(lock) {
        require(expectedLocalRevision >= 0)
        requireNonNegative(nowMillis)
        val current = read(binding, localDraftId)
        if (current.localRevision != expectedLocalRevision) {
            return@synchronized NoteArtifactRemoteAcceptResult.StaleRevision(current)
        }
        if (current.dirty) {
            return@synchronized NoteArtifactRemoteAcceptResult.BlockedByDirty(current)
        }
        val snapshot = current.pendingServer
            ?: return@synchronized NoteArtifactRemoteAcceptResult.NoPendingSnapshot(current)
        val timestamp = maxOf(current.updatedAtMillis, nowMillis, snapshot.observedAtMillis)
        val next = current.copy(
            localRevision = current.localRevision + 1,
            text = snapshot.text,
            savedText = snapshot.text,
            dirty = false,
            baseServerArtifactVersionId = snapshot.baseServerArtifactVersionId,
            expectedServerRevision = snapshot.expectedServerRevision,
            pendingServer = null,
            updatedAtMillis = timestamp,
            savedAtMillis = timestamp,
        )
        writeDraft(next)
        NoteArtifactRemoteAcceptResult.Accepted(next)
    }

    /**
     * Records that the server accepted the exact saved local text as the new
     * baseline. The working text is never rewritten by this metadata update.
     */
    fun markServerAccepted(
        binding: BoundUploadSession,
        localDraftId: String,
        expectedLocalRevision: Long,
        baseServerArtifactVersionId: String,
        expectedServerRevision: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactServerAcceptResult = synchronized(lock) {
        require(expectedLocalRevision >= 0)
        requireNonNegative(nowMillis)
        requireOpaqueServerId(baseServerArtifactVersionId)
        requireOpaqueServerId(expectedServerRevision)
        val current = read(binding, localDraftId)
        if (current.localRevision != expectedLocalRevision) {
            return@synchronized NoteArtifactServerAcceptResult.StaleRevision(current)
        }
        val next = current.copy(
            baseServerArtifactVersionId = baseServerArtifactVersionId,
            expectedServerRevision = expectedServerRevision,
            pendingServer = null,
            updatedAtMillis = maxOf(current.updatedAtMillis, nowMillis),
        )
        writeDraft(next)
        NoteArtifactServerAcceptResult.Accepted(next)
    }

    fun conflicts(binding: BoundUploadSession): List<NoteArtifactDraftConflict> = synchronized(lock) {
        val folder = conflictsRoot(binding)
        if (!folder.exists()) return@synchronized emptyList()
        folder.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { file ->
                val conflict = NoteJson.decodeFromString<NoteArtifactDraftConflict>(readMaterialFile(file))
                validateConflict(conflict, binding)
                conflict
            }
            .sortedByDescending { it.createdAtMillis }
    }

    private fun conflict(
        current: NoteArtifactDraft,
        expectedLocalRevision: Long,
        attemptedText: String,
        nowMillis: Long,
    ): NoteArtifactDraftWriteResult.Conflict {
        val preserved = NoteArtifactDraftConflict(
            conflictId = newNoteId(),
            localDraftId = current.localDraftId,
            serverIdentity = current.serverIdentity,
            ownerId = current.ownerId,
            noteId = current.noteId,
            materialVersionId = current.materialVersionId,
            kind = current.kind,
            expectedLocalRevision = expectedLocalRevision,
            attemptedText = attemptedText,
            current = current,
            createdAtMillis = maxOf(current.updatedAtMillis, nowMillis),
        )
        validateConflict(preserved, current.binding)
        writeConflict(preserved)
        return NoteArtifactDraftWriteResult.Conflict(preserved)
    }

    private fun writeDraft(draft: NoteArtifactDraft) {
        validateDraft(draft)
        val file = File(scopeFolder(draft.binding, draft.noteId, draft.materialVersionId, draft.kind),
            "${draft.localDraftId}.json")
        writeJson(file, draft)
    }

    private fun writeConflict(conflict: NoteArtifactDraftConflict) {
        writeJson(File(conflictsRoot(conflict.current.binding), "${conflict.conflictId}.json"), conflict)
    }

    private inline fun <reified T> writeJson(file: File, value: T) {
        val json = NoteJson.encodeToString(value)
        writeVerifiedMaterialJson(file, json)
    }

    private fun readFile(file: File, binding: BoundUploadSession): NoteArtifactDraft {
        val draft = NoteJson.decodeFromString<NoteArtifactDraft>(readMaterialFile(file))
        validateDraft(draft, binding)
        return draft
    }

    private fun validateDraft(draft: NoteArtifactDraft, expectedBinding: BoundUploadSession? = null) {
        require(validArtifactId(draft.localDraftId) && validArtifactId(draft.noteId) &&
            validArtifactId(draft.materialVersionId))
        val binding = BoundUploadSession(draft.serverIdentity, draft.ownerId)
        require(expectedBinding == null || binding == expectedBinding)
        require(draft.localRevision >= 0)
        require(draft.dirty == (draft.text != draft.savedText))
        requireOpaqueServerId(draft.baseServerArtifactVersionId)
        requireOpaqueServerId(draft.expectedServerRevision)
        require(draft.createdAtMillis >= 0 && draft.savedAtMillis >= draft.createdAtMillis &&
            draft.updatedAtMillis >= draft.savedAtMillis)
        draft.pendingServer?.let { snapshot ->
            requireNonNegative(snapshot.observedAtMillis)
            requireOpaqueServerId(snapshot.baseServerArtifactVersionId)
            requireOpaqueServerId(snapshot.expectedServerRevision)
        }
    }

    private fun validateConflict(conflict: NoteArtifactDraftConflict, expectedBinding: BoundUploadSession) {
        require(validArtifactId(conflict.conflictId) && validArtifactId(conflict.localDraftId))
        require(conflict.expectedLocalRevision >= 0 && conflict.createdAtMillis >= 0)
        validateDraft(conflict.current, expectedBinding)
        require(conflict.serverIdentity == conflict.current.serverIdentity &&
            conflict.ownerId == conflict.current.ownerId &&
            conflict.noteId == conflict.current.noteId &&
            conflict.materialVersionId == conflict.current.materialVersionId &&
            conflict.kind == conflict.current.kind &&
            conflict.localDraftId == conflict.current.localDraftId)
    }

    private fun bindingRoot(binding: BoundUploadSession) =
        File(root, materialDigest("${binding.serverIdentity}\n${binding.ownerId}"))

    private fun draftsRoot(binding: BoundUploadSession) = File(bindingRoot(binding), "drafts")
    private fun conflictsRoot(binding: BoundUploadSession) = File(bindingRoot(binding), "conflicts")

    private fun scopeFolder(
        binding: BoundUploadSession,
        noteId: String,
        materialVersionId: String,
        kind: NoteArtifactKind,
    ) = File(File(File(draftsRoot(binding), noteId), materialVersionId), kind.name)

    private fun baseFile(file: File) = File(file.path.removeSuffix(".bak"))

    private fun requireOpaqueServerId(value: String?) {
        require(value == null || (value.isNotBlank() && value.length <= 512))
    }

    private fun validArtifactId(value: String) =
        validNoteId(value) && value != "00000000-0000-0000-0000-000000000000"

    private fun requireNonNegative(value: Long): Long {
        require(value >= 0)
        return value
    }

    companion object {
        private val lock = Any()

        fun at(noBackupDirectory: File) = NoteArtifactDraftStore(File(noBackupDirectory, "note-artifact-drafts"))
    }
}

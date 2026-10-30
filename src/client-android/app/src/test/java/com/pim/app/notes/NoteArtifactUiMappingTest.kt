package com.pim.app.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteArtifactUiMappingTest {
    @Test
    fun oneSharedTaskDrivesBothKindEditorsWithoutDuplicatingLocalState() {
        val task = sharedTask()
        val state = NoteArtifactRepositoryState(
            loaded = true,
            noteId = task.noteId,
            tasks = listOf(task),
            selectedTaskId = task.taskId,
        )

        val ui = state.toUiState(
            entry = NoteArtifactEntry(task.noteId, task.materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        )

        assertEquals(NoteArtifactWorkPhase.Running, ui.fullRecord.workPhase)
        assertEquals(NoteArtifactWorkPhase.Running, ui.analysis.workPhase)
        assertTrue(ui.fullRecord.workDetail.orEmpty().contains(task.taskId.take(8)))
        assertTrue(ui.analysis.workDetail.orEmpty().contains(task.taskId.take(8)))
    }

    @Test
    fun singleModelCandidateWithoutServerHeadRemainsSelectableAndIsOnlyBrowsing() {
        val candidate = version("candidate-model", isHead = false)
        val state = candidateState(headVersionId = null, candidates = listOf(candidate))

        val ui = state.toUiState(entry(), selectedKind = NoteArtifactKind.FullRecord)

        assertNull(ui.fullRecord.confirmedHeadCandidateId)
        assertFalse(ui.fullRecord.canSaveManualRevision)
        assertTrue(ui.fullRecord.canSelectCandidate(candidate.versionId))

        val pending = state.toUiState(
            entry = entry(),
            selectedKind = NoteArtifactKind.FullRecord,
            pendingSelectionCandidateIds = mapOf(
                NoteArtifactKind.FullRecord to candidate.versionId,
            ),
        )
        assertEquals(candidate.versionId, pending.fullRecord.pendingSelectionCandidateId)
        assertNull(pending.fullRecord.confirmedHeadCandidateId)
        assertFalse(pending.fullRecord.canSelectCandidate(candidate.versionId))

        val viewing = state.toUiState(
            entry = entry(),
            selectedKind = NoteArtifactKind.FullRecord,
            viewedCandidateIds = mapOf(NoteArtifactKind.FullRecord to candidate.versionId),
        )
        assertEquals(candidate.versionId, viewing.fullRecord.viewedCandidateId)
        assertNull(viewing.fullRecord.confirmedHeadCandidateId)
    }

    @Test
    fun staleIsHeadFlagCannotConfirmWhenArtifactMetadataHeadIsNull() {
        val staleCandidate = version("candidate-stale-head", isHead = true)
        val state = candidateState(headVersionId = null, candidates = listOf(staleCandidate))

        val ui = state.toUiState(entry(), selectedKind = NoteArtifactKind.FullRecord)

        assertNull(ui.fullRecord.confirmedHeadCandidateId)
        assertNull(ui.fullRecord.confirmedHeadCandidate)
        assertTrue(ui.fullRecord.canSelectCandidate(staleCandidate.versionId))
        assertFalse(ui.fullRecord.canSaveManualRevision)
    }

    @Test
    fun onlyMatchingServerHeadBecomesCurrentAndDisablesSelection() {
        val candidate = version("candidate-head", isHead = true)
        val state = candidateState(
            headVersionId = candidate.versionId,
            candidates = listOf(candidate),
            draft = cleanDraft(),
        )

        val ui = state.toUiState(entry(), selectedKind = NoteArtifactKind.FullRecord)

        assertEquals(candidate.versionId, ui.fullRecord.confirmedHeadCandidateId)
        assertTrue(ui.fullRecord.canSaveManualRevision)
        assertFalse(ui.fullRecord.canSelectCandidate(candidate.versionId))
    }

    private fun candidateState(
        headVersionId: String?,
        candidates: List<NoteArtifactVersionProjection>,
        draft: NoteArtifactDraft? = null,
    ) = NoteArtifactRepositoryState(
        loaded = true,
        noteId = noteId,
        materialVersionId = materialVersionId,
        tasks = listOf(sharedTask()),
        drafts = listOfNotNull(draft),
        artifactRefs = mapOf(
            NoteArtifactKind.FullRecord to NoteArtifactArtifactRefProjection(
                artifactId = "artifact-full",
                kind = NoteArtifactKind.FullRecord,
                revision = "artifact-revision",
                headVersionId = headVersionId,
            ),
        ),
        versions = mapOf(NoteArtifactKind.FullRecord to candidates),
    )

    private fun entry() = NoteArtifactEntry(noteId, materialVersionId)

    private fun version(versionId: String, isHead: Boolean) = NoteArtifactVersionProjection(
        versionId = versionId,
        artifactId = "artifact-full",
        kind = NoteArtifactKind.FullRecord,
        author = NoteArtifactVersionAuthor.Model,
        parentVersionId = null,
        observedHeadVersionId = null,
        sourceCompleteRecordVersionId = null,
        sourceVersionId = materialVersionId,
        generationTaskId = null,
        contentMarkdown = "model candidate",
        contentSha256 = "c".repeat(64),
        hasConflict = false,
        createdAtLabel = "2026-10-30 15:37",
        isHead = isHead,
    )

    private fun cleanDraft() = NoteArtifactDraft(
        localDraftId = "55555555-5555-4555-8555-555555555555",
        serverIdentity = "https://pim.example",
        ownerId = "22222222-2222-4222-8222-222222222222",
        noteId = noteId,
        materialVersionId = materialVersionId,
        kind = NoteArtifactKind.FullRecord,
        localRevision = 1,
        text = "saved local draft",
        savedText = "saved local draft",
        dirty = false,
        baseServerArtifactVersionId = null,
        expectedServerRevision = "artifact-revision",
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
        savedAtMillis = 1_000,
    )

    private fun sharedTask() = NoteArtifactTaskRecord(
        taskId = "11111111-1111-4111-8111-111111111111",
        noteId = "33333333-3333-4333-8333-333333333333",
        materialVersionId = "44444444-4444-4444-8444-444444444444",
        kind = NoteArtifactKind.FullRecord,
        kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        serverIdentity = "https://pim.example",
        ownerId = "22222222-2222-4222-8222-222222222222",
        ownerName = "alice",
        expectedUserId = "22222222-2222-4222-8222-222222222222",
        consent = NoteArtifactConsent(
            confirmed = true,
            modelId = "66666666-6666-4666-8666-666666666666",
            modelRevision = "77777777-7777-4777-8777-777777777777",
            endpoint = "https://model.example/v1/responses",
        ),
        localPhase = NoteArtifactIntentPhase.Accepted,
        remote = NoteArtifactRemoteTaskView(
            revision = "rev-1",
            phase = "Queued",
            observedAtMillis = 1_000,
        ),
        createdAtMillis = 1_000,
        updatedAtMillis = 2_000,
    )

    private companion object {
        const val noteId = "33333333-3333-4333-8333-333333333333"
        const val materialVersionId = "44444444-4444-4444-8444-444444444444"
    }
}

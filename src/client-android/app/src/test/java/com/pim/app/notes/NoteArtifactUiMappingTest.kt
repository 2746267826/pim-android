package com.pim.app.notes

import org.junit.Assert.assertEquals
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
}

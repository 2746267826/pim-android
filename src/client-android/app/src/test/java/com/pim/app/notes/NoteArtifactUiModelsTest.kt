package com.pim.app.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteArtifactUiModelsTest {
    @Test
    fun coverageShowsEveryMissingCountAndIssue() {
        val coverage = NoteArtifactCoverageUi(
            textBlocks = 3,
            transcriptSegments = 4,
            completedTranscriptSegments = 2,
            photos = 3,
            completedPhotos = 1,
            inkPreviews = 2,
            completedInkPreviews = 0,
            visionBatches = 5,
            completedVisionBatches = 4,
            serverReportsComplete = true,
            issues = listOf(NoteArtifactIssueUi("ImageMissing", "照片文件不可读")),
        )

        assertFalse(coverage.complete)
        assertEquals(
            listOf(
                "转写缺少 2/4",
                "照片缺少 2/3",
                "墨迹预览缺少 2/2",
                "图像识别批次缺少 1/5",
                "ImageMissing：照片文件不可读",
            ),
            coverage.gapMessages,
        )
    }

    @Test
    fun inconsistentCompletedCountsCannotBePresentedAsComplete() {
        val coverage = NoteArtifactCoverageUi(
            textBlocks = 1,
            transcriptSegments = 2,
            completedTranscriptSegments = 3,
            photos = 0,
            completedPhotos = 0,
            inkPreviews = 0,
            completedInkPreviews = 0,
            visionBatches = 0,
            completedVisionBatches = 0,
            serverReportsComplete = true,
        )

        assertTrue(coverage.hasCountAnomaly)
        assertFalse(coverage.complete)
        assertEquals(
            listOf("转写完成数 3 超过总数 2（服务端数据异常）"),
            coverage.gapMessages,
        )
    }

    @Test
    fun candidateViewsAndRefreshNeverReplaceDirtyDraftOrItsBase() {
        val draft = dirtyDraft()
        val first = candidate("candidate-1", NoteArtifactCandidateOrigin.ModelOriginal)
        val second = candidate("candidate-2", NoteArtifactCandidateOrigin.FollowUpCandidate)
        val editor = NoteArtifactEditorUiState(
            kind = NoteArtifactKind.FullRecord,
            draft = draft,
            candidates = listOf(first, second),
            selectedCandidateId = first.candidateId,
        )

        val selected = editor.withSelectedCandidate(second.candidateId)
        assertSame(draft, selected.draft)
        assertEquals("server-base-1", selected.localBaseLabel)

        val viewed = selected.withViewedCandidate(first.candidateId)
        assertSame(draft, viewed.draft)
        assertEquals(NoteArtifactContentView.ServerOriginal, viewed.contentView)
        assertEquals("server-base-1", viewed.localBaseLabel)

        val refreshed = viewed.withRefreshedCandidates(
            updatedCandidates = listOf(first),
            updatedWorkPhase = NoteArtifactWorkPhase.Running,
        )
        assertSame(draft, refreshed.draft)
        assertEquals("server-base-1", refreshed.localBaseLabel)
        assertEquals(NoteArtifactWorkPhase.Running, refreshed.workPhase)
        assertEquals(second.candidateId, refreshed.selectedCandidateId)
        assertEquals(first.candidateId, refreshed.viewedCandidateId)
    }

    @Test
    fun replacingOneKindDoesNotChangeTheOtherArtifact() {
        val original = sampleState()
        val analysisEditor = original.analysis.copy(
            draft = dirtyDraft().copy(kind = NoteArtifactKind.Analysis),
            workPhase = NoteArtifactWorkPhase.Unknown,
        )

        val updated = original.withEditor(analysisEditor)

        assertSame(original.fullRecord, updated.fullRecord)
        assertSame(analysisEditor, updated.analysis)
    }

    private fun dirtyDraft() = NoteArtifactDraft(
        localDraftId = "11111111-1111-4111-8111-111111111111",
        serverIdentity = "https://pim.example",
        ownerId = "22222222-2222-4222-8222-222222222222",
        noteId = "33333333-3333-4333-8333-333333333333",
        materialVersionId = "44444444-4444-4444-8444-444444444444",
        kind = NoteArtifactKind.FullRecord,
        localRevision = 2,
        text = "本机未同步草稿",
        savedText = "上次本机保存",
        dirty = true,
        baseServerArtifactVersionId = "server-base-1",
        expectedServerRevision = "server-revision-1",
        createdAtMillis = 1_000,
        updatedAtMillis = 2_000,
        savedAtMillis = 2_000,
    )

    private fun candidate(
        id: String,
        origin: NoteArtifactCandidateOrigin,
    ) = NoteArtifactCandidateUi(
        candidateId = id,
        serverVersionId = "version-$id",
        origin = origin,
        text = "candidate $id",
        createdAtLabel = "2026-10-29 10:00",
        baseServerArtifactVersionId = "server-base-1",
    )

    private fun sampleState() = NoteArtifactUiState(
        material = NoteArtifactMaterialUi(
            title = "固定材料",
            summary = "1 次录音",
            serverLabel = "https://pim.example",
            ownerLabel = "测试账号",
            noteId = "33333333-3333-4333-8333-333333333333",
            materialVersionId = "44444444-4444-4444-8444-444444444444",
            fixedAtLabel = "2026-10-29 10:00",
        ),
        coverage = NoteArtifactCoverageUi(
            textBlocks = 1,
            transcriptSegments = 1,
            completedTranscriptSegments = 1,
            photos = 0,
            completedPhotos = 0,
            inkPreviews = 0,
            completedInkPreviews = 0,
            visionBatches = 0,
            completedVisionBatches = 0,
            serverReportsComplete = true,
        ),
        fullRecord = NoteArtifactEditorUiState(
            kind = NoteArtifactKind.FullRecord,
            draft = dirtyDraft(),
        ),
        analysis = NoteArtifactEditorUiState(kind = NoteArtifactKind.Analysis),
    )
}

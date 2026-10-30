package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteArtifactRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val binding = BoundUploadSession("https://pim.example", owner)
    private val noteId = "33333333-3333-4333-8333-333333333333"
    private val materialVersionId = "44444444-4444-4444-8444-444444444444"
    private val consent = NoteArtifactConsent(
        confirmed = true,
        modelId = "66666666-6666-4666-8666-666666666666",
        modelRevision = "77777777-7777-4777-8777-777777777777",
        endpoint = "https://model.example/v1/responses",
    )

    private val transport = FakeTransport()
    private val tasks by lazy { NoteArtifactTaskStore.at(temp.root) }

    @Test fun partialDetailRefsNeverReplaceFullRevisionUsedByManualSaveOrSelect() = runBlocking {
        seedTask()
        transport.artifactResponses += listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-1", "head-1"),
            completeRef(NoteArtifactKind.Analysis, "artifact-analysis", "artifact-rev-a", "head-a"),
        )
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-1"),
            partialRef(NoteArtifactKind.Analysis, "artifact-analysis", "head-a"),
        )
        val repository = repository()
        repository.refresh(noteId)

        val full = repository.state.value.artifactRefs.getValue(NoteArtifactKind.FullRecord)
        assertEquals("artifact-rev-1", full.revision)
        assertEquals("head-1", full.headVersionId)
        val draft = repository.state.value.drafts.single {
            it.kind == NoteArtifactKind.FullRecord && it.materialVersionId == materialVersionId
        }

        repository.saveManualRevisionToServer(
            kind = NoteArtifactKind.FullRecord,
            artifactId = full.artifactId,
            localDraftId = draft.localDraftId,
            expectedLocalRevision = draft.localRevision,
            expectedHeadRevision = requireNotNull(full.revision),
            expectedHeadVersionId = full.headVersionId,
            contentMarkdown = "manual body",
        )
        repository.selectCandidate(
            artifactId = full.artifactId,
            expectedHeadRevision = requireNotNull(full.revision),
            versionId = "candidate-version",
        )

        assertEquals(listOf("artifact-rev-1"), transport.manualRevisionHeads)
        assertEquals(listOf("head-1"), transport.manualRevisionHeadVersions)
        assertEquals(listOf("artifact-rev-1"), transport.selectHeads)
    }

    @Test fun detailHeadChangeRefreshesFormalArtifactMetadataBeforeExposingIt() = runBlocking {
        seedTask()
        transport.artifactResponses += listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-1", "head-1"),
        )
        transport.artifactResponses += listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-2", "head-2"),
        )
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-2"),
        )
        val repository = repository()

        repository.refresh(noteId)

        val refreshed = repository.state.value.artifactRefs.getValue(NoteArtifactKind.FullRecord)
        assertEquals("artifact-rev-2", refreshed.revision)
        assertEquals("head-2", refreshed.headVersionId)
        assertEquals(2, transport.artifactCalls)
    }

    @Test fun metadataFailurePreservesFullRefsAndDirtyDraft() = runBlocking {
        seedTask()
        transport.artifactResponses += listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-1", "head-1"),
        )
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-1"),
        )
        val repository = repository()
        repository.refresh(noteId)
        val draft = repository.state.value.drafts.single {
            it.kind == NoteArtifactKind.FullRecord && it.materialVersionId == materialVersionId
        }
        repository.stageText(draft.localDraftId, draft.localRevision, "dirty text survives")

        transport.artifactError = IOException("metadata unavailable")
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-2"),
        )
        repository.refresh(noteId)

        val preserved = repository.state.value.artifactRefs.getValue(NoteArtifactKind.FullRecord)
        assertEquals("artifact-rev-1", preserved.revision)
        assertEquals("head-1", preserved.headVersionId)
        val preservedDraft = repository.state.value.drafts.single {
            it.localDraftId == draft.localDraftId
        }
        assertTrue(preservedDraft.dirty)
        assertEquals("dirty text survives", preservedDraft.text)
        val problem = repository.state.value.message
        assertNotNull(problem)
        assertTrue(problem.orEmpty().contains("保留"))
        assertTrue(problem.orEmpty().contains("未确认"))
    }

    private fun repository() = NoteArtifactRepository(
        tasks = tasks,
        drafts = NoteArtifactDraftStore.at(temp.root),
        materials = NoteMaterialStore.at(temp.root),
        targetProvider = { NoteArtifactTarget(binding, "alice") },
        factory = { transport },
    )

    private fun seedTask() = tasks.createIntent(
        binding = binding,
        noteId = noteId,
        materialVersionId = materialVersionId,
        kind = NoteArtifactKind.FullRecord,
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        consent = consent,
        ownerName = "alice",
    )

    private fun completeRef(
        kind: NoteArtifactKind,
        artifactId: String,
        revision: String,
        headVersionId: String,
    ) = NoteArtifactArtifactRefProjection(
        artifactId = artifactId,
        kind = kind,
        revision = revision,
        headVersionId = headVersionId,
    )

    private fun partialRef(
        kind: NoteArtifactKind,
        artifactId: String,
        headVersionId: String?,
    ) = NoteArtifactArtifactRefProjection(
        artifactId = artifactId,
        kind = kind,
        revision = null,
        headVersionId = headVersionId,
    )

    private fun detail(vararg artifacts: NoteArtifactArtifactRefProjection) = NoteArtifactTaskDetail(
        view = NoteArtifactRemoteTaskView(
            revision = "task-rev",
            phase = "Completed",
            observedAtMillis = 1_000,
        ),
        coverage = NoteArtifactCoverageProjection(
            textBlocks = 0,
            transcriptSegments = 0,
            completedTranscriptSegments = 0,
            photos = 0,
            completedPhotos = 0,
            inkPreviews = 0,
            completedInkPreviews = 0,
            visionBatches = 0,
            completedVisionBatches = 0,
            complete = true,
        ),
        issues = emptyList(),
        steps = emptyList(),
        artifacts = artifacts.toList(),
    )

    private class FakeTransport : NoteArtifactTransport {
        val artifactResponses = ArrayDeque<List<NoteArtifactArtifactRefProjection>>()
        val manualRevisionHeads = mutableListOf<String>()
        val manualRevisionHeadVersions = mutableListOf<String?>()
        val selectHeads = mutableListOf<String>()
        var artifactCalls = 0
        var artifactError: Throwable? = null
        lateinit var detail: NoteArtifactTaskDetail

        override suspend fun destinations(): List<NoteArtifactDestination> = emptyList()

        override suspend fun start(record: NoteArtifactTaskRecord): NoteArtifactRemoteTaskView =
            error("start is not used by repository tests")

        override suspend fun get(taskId: String): NoteArtifactRemoteTaskView =
            error("get is not used by repository tests")

        override suspend fun detail(taskId: String): NoteArtifactTaskDetail = detail

        override suspend fun artifacts(noteId: String): List<NoteArtifactArtifactRefProjection> {
            artifactCalls += 1
            artifactError?.let { throw it }
            return if (artifactResponses.isEmpty()) emptyList() else artifactResponses.removeFirst()
        }

        override suspend fun versions(artifactId: String): List<NoteArtifactVersionProjection> =
            emptyList()

        override suspend fun stop(taskId: String): NoteArtifactRemoteTaskView =
            error("stop is not used by repository tests")

        override suspend fun retry(
            record: NoteArtifactTaskRecord,
            retry: NoteArtifactRetryIntent,
        ): NoteArtifactRemoteTaskView = error("retry is not used by repository tests")

        override suspend fun saveManualRevision(
            artifactId: String,
            revisionId: String,
            expectedHeadRevision: String,
            expectedHeadVersionId: String?,
            contentMarkdown: String,
        ): NoteArtifactMutationProjection {
            manualRevisionHeads += expectedHeadRevision
            manualRevisionHeadVersions += expectedHeadVersionId
            return NoteArtifactMutationProjection(
                artifactRevision = "artifact-rev-manual",
                versionId = "manual-version",
                hasConflict = false,
                isHead = true,
            )
        }

        override suspend fun selectVersion(
            artifactId: String,
            expectedHeadRevision: String,
            versionId: String,
        ): String {
            selectHeads += expectedHeadRevision
            return versionId
        }
    }
}

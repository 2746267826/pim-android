package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
    private val destination = NoteArtifactDestination(
        modelId = consent.modelId,
        modelRevision = consent.modelRevision,
        displayName = "测试模型",
        model = "model-a",
        endpoint = consent.endpoint,
        capabilities = listOf("text", "vision"),
        backgroundProcessingAvailable = true,
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

    @Test fun oneConfirmationStartsOneTaskAssociatedWithBothArtifactKinds() = runBlocking {
        val material = readyMaterial()
        val repository = repository()
        repository.refresh(noteId, material)

        val taskId = repository.start(
            noteId = noteId,
            materialVersionId = material,
            asrTaskId = null,
            destination = destination,
            freshlyConfirmed = true,
        )

        assertEquals(1, transport.starts.size)
        assertEquals(taskId, transport.starts.single().taskId)
        assertEquals(
            setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            transport.starts.single().kinds,
        )
        val saved = repository.state.value.tasks.single { it.taskId == taskId }
        assertEquals(
            setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            saved.kinds,
        )
        assertTrue(saved.supports(NoteArtifactKind.FullRecord))
        assertTrue(saved.supports(NoteArtifactKind.Analysis))
    }

    @Test fun explicitRegenerationCreatesOneNewTaskAndKeepsManualDraftAndSelectedHead() = runBlocking {
        val material = readyMaterial()
        val seeded = tasks.createIntent(
            binding = binding,
            noteId = noteId,
            materialVersionId = material,
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = materialDigest(NoteMaterialStore.at(temp.root).uploadSnapshot(material).inputJson),
            manifestSha256 = materialDigest(NoteMaterialStore.at(temp.root).uploadSnapshot(material).manifestJson),
            consent = consent,
            ownerName = "alice",
        )
        tasks.recordRemote(
            seeded.taskId,
            NoteArtifactRemoteTaskView("task-rev-1", "Succeeded", observedAtMillis = 1_000),
        )
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-1", "head-full"),
            completeRef(NoteArtifactKind.Analysis, "artifact-analysis", "artifact-rev-a", "head-analysis"),
        )
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-full"),
            partialRef(NoteArtifactKind.Analysis, "artifact-analysis", "head-analysis"),
        )
        val repository = repository()
        repository.refresh(noteId, material, seeded.taskId)
        val beforeRef = repository.state.value.artifactRefs.getValue(NoteArtifactKind.Analysis)
        val beforeDraft = repository.state.value.drafts.single {
            it.kind == NoteArtifactKind.Analysis && it.materialVersionId == material
        }
        repository.stageText(beforeDraft.localDraftId, beforeDraft.localRevision, "manual text survives")
        val stagedDraft = repository.state.value.drafts.single { it.localDraftId == beforeDraft.localDraftId }

        repository.regenerate(NoteArtifactKind.Analysis)

        assertEquals(1, transport.starts.size)
        val regenerated = transport.starts.single()
        assertNotEquals(seeded.taskId, regenerated.taskId)
        assertEquals(
            setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            regenerated.kinds,
        )
        assertEquals(2, tasks.tasks().size)
        assertEquals(beforeRef, repository.state.value.artifactRefs.getValue(NoteArtifactKind.Analysis))
        val afterDraft = repository.state.value.drafts.single { it.localDraftId == beforeDraft.localDraftId }
        assertEquals(stagedDraft, afterDraft)
        assertEquals("manual text survives", afterDraft.text)
        assertTrue(afterDraft.dirty)
    }

    private fun repository() = NoteArtifactRepository(
        tasks = tasks,
        drafts = NoteArtifactDraftStore.at(temp.root),
        materials = NoteMaterialStore.at(temp.root),
        targetProvider = { NoteArtifactTarget(binding, "alice") },
        factory = { transport },
    )

    private fun readyMaterial(): String {
        val created = NoteDocument.create()
        val note = NoteDocument(JsonObject(created.source + ("id" to JsonPrimitive(noteId))))
        note.validate()
        val store = NoteMaterialStore.at(temp.root)
        val material = store.create(note, emptyList())
        store.prepare(material.id)
        return material.id
    }

    private fun seedTask() = tasks.createIntent(
        binding = binding,
        noteId = noteId,
        materialVersionId = materialVersionId,
        kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
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
        val starts = mutableListOf<NoteArtifactTaskRecord>()
        var artifactCalls = 0
        var artifactError: Throwable? = null
        var artifactFallback: List<NoteArtifactArtifactRefProjection> = emptyList()
        lateinit var detail: NoteArtifactTaskDetail

        override suspend fun destinations(): List<NoteArtifactDestination> = listOf(
            NoteArtifactDestination(
                modelId = "66666666-6666-4666-8666-666666666666",
                modelRevision = "77777777-7777-4777-8777-777777777777",
                displayName = "测试模型",
                model = "model-a",
                endpoint = "https://model.example/v1/responses",
                capabilities = listOf("text", "vision"),
                backgroundProcessingAvailable = true,
            ),
        )

        override suspend fun start(record: NoteArtifactTaskRecord): NoteArtifactRemoteTaskView {
            starts += record
            return NoteArtifactRemoteTaskView(
                revision = "task-rev-${starts.size + 1}",
                phase = "Queued",
                inputSha256 = record.inputSha256,
                manifestSha256 = record.manifestSha256,
                observedAtMillis = 2_000,
            )
        }

        override suspend fun get(taskId: String): NoteArtifactRemoteTaskView =
            error("get is not used by repository tests")

        override suspend fun detail(taskId: String): NoteArtifactTaskDetail = detail

        override suspend fun artifacts(noteId: String): List<NoteArtifactArtifactRefProjection> {
            artifactCalls += 1
            artifactError?.let { throw it }
            return if (artifactResponses.isEmpty()) artifactFallback else artifactResponses.removeFirst()
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

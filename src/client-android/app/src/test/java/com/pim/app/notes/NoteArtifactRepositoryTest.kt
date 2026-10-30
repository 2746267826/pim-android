package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val otherOwner = "22222222-2222-4222-8222-222222222222"
    private val binding = BoundUploadSession("https://pim.example", owner)
    private val otherBinding = BoundUploadSession("https://pim.example", otherOwner)
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
    private var activeBinding = binding

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

    @Test fun failedCandidateSelectionKeepsDirtyDraftAndRetryCanConfirmServerHead() = runBlocking {
        val task = seedTask()
        transport.artifactFallback = listOf(
            NoteArtifactArtifactRefProjection(
                artifactId = "artifact-full",
                kind = NoteArtifactKind.FullRecord,
                revision = "artifact-revision",
                headVersionId = null,
            ),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(version("candidate-model", NoteArtifactKind.FullRecord, materialVersionId, false)),
        )
        transport.detail = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", null))
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        val draft = repository.state.value.drafts.single { it.kind == NoteArtifactKind.FullRecord }
        repository.stageText(draft.localDraftId, draft.localRevision, "dirty text survives select failure")
        val dirtyDraft = repository.state.value.drafts.single { it.localDraftId == draft.localDraftId }
        val before = repository.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        )
        assertNull(before.fullRecord.confirmedHeadCandidateId)
        assertFalse(before.fullRecord.canSaveManualRevision)
        assertTrue(before.fullRecord.canSelectCandidate("candidate-model"))

        transport.selectError = IOException("selection receipt unknown")
        repository.selectCandidate("artifact-full", "artifact-revision", "candidate-model")

        val afterFailure = repository.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        )
        assertNull(afterFailure.fullRecord.confirmedHeadCandidateId)
        assertFalse(afterFailure.fullRecord.canSaveManualRevision)
        assertTrue(afterFailure.fullRecord.canSelectCandidate("candidate-model"))
        assertEquals(
            dirtyDraft,
            repository.state.value.drafts.single { it.localDraftId == draft.localDraftId },
        )
        assertEquals("dirty text survives select failure", dirtyDraft.text)
        assertTrue(dirtyDraft.dirty)

        transport.selectError = null
        transport.artifactFallback = listOf(
            NoteArtifactArtifactRefProjection(
                artifactId = "artifact-full",
                kind = NoteArtifactKind.FullRecord,
                revision = "artifact-revision",
                headVersionId = "candidate-model",
            ),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(version("candidate-model", NoteArtifactKind.FullRecord, materialVersionId, true)),
        )
        repository.selectCandidate("artifact-full", "artifact-revision", "candidate-model")

        val confirmed = repository.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        )
        assertEquals("candidate-model", confirmed.fullRecord.confirmedHeadCandidateId)
        assertFalse(confirmed.fullRecord.canSelectCandidate("candidate-model"))
        assertEquals("dirty text survives select failure", confirmed.fullRecord.draft?.text)
        assertTrue(confirmed.fullRecord.isDirty)
        assertEquals(listOf("artifact-revision", "artifact-revision"), transport.selectHeads)
    }

    @Test fun oneConfirmationStartsOneTaskAssociatedWithBothArtifactKinds() = runBlocking {
        val material = readyMaterial("A")
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
        val material = readyMaterial("A")
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

    @Test fun pinnedMaterialContextRejectsNewerOtherVersionAcrossRefreshAndRegeneration() = runBlocking {
        val materialStore = NoteMaterialStore.at(temp.root)
        val materialA = readyMaterial("A")
        val materialB = readyMaterial("B")
        val snapshotA = materialStore.uploadSnapshot(materialA)
        val snapshotB = materialStore.uploadSnapshot(materialB)
        val asrA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val asrB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val consentA = destination.asConsent()
        val consentB = consent.copy(modelRevision = "88888888-8888-4888-8888-888888888888")
        val taskA = tasks.createIntent(
            binding = binding,
            noteId = noteId,
            materialVersionId = materialA,
            asrTaskId = asrA,
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = materialDigest(snapshotA.inputJson),
            manifestSha256 = materialDigest(snapshotA.manifestJson),
            consent = consentA,
            ownerName = "alice",
            nowMillis = 1_000,
        )
        tasks.recordRemote(
            taskA.taskId,
            NoteArtifactRemoteTaskView("a-rev", "Succeeded", observedAtMillis = 1_100),
            nowMillis = 1_100,
        )
        val taskB = tasks.createIntent(
            binding = binding,
            noteId = noteId,
            materialVersionId = materialB,
            asrTaskId = asrB,
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = materialDigest(snapshotB.inputJson),
            manifestSha256 = materialDigest(snapshotB.manifestJson),
            consent = consentB,
            ownerName = "alice",
            nowMillis = 3_000,
        )
        tasks.update(taskB.taskId, nowMillis = 3_100) {
            it.copy(
                localPhase = NoteArtifactIntentPhase.VerificationPending,
                lastProblem = "unknown receipt",
            )
        }
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-a", "version-a-full"),
            completeRef(NoteArtifactKind.Analysis, "artifact-analysis", "artifact-rev-a", "version-a-analysis"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("version-b-full", NoteArtifactKind.FullRecord, materialB, isHead = false),
                version("version-a-full", NoteArtifactKind.FullRecord, materialA, isHead = true),
            ),
            "artifact-analysis" to listOf(
                version("version-b-analysis", NoteArtifactKind.Analysis, materialB, isHead = false),
                version("version-a-analysis", NoteArtifactKind.Analysis, materialA, isHead = true),
            ),
        )
        transport.details[taskA.taskId] = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "version-a-full"),
            partialRef(NoteArtifactKind.Analysis, "artifact-analysis", "version-a-analysis"),
        )
        val repository = repository()

        repository.refresh(noteId)
        assertEquals(materialB, repository.state.value.materialVersionId)
        assertEquals(taskB.taskId, repository.state.value.selectedTaskId)

        val detailCallsBeforePin = transport.detailCalls.size
        repository.refresh(noteId, materialA, selectedTaskId = taskB.taskId)

        val scoped = repository.state.value
        assertEquals(materialA, scoped.materialVersionId)
        assertEquals(binding, scoped.taskBinding)
        assertEquals(taskA.taskId, scoped.selectedTaskId)
        assertEquals(listOf(taskA.taskId), scoped.tasks.map { it.taskId })
        assertTrue(scoped.drafts.all { it.materialVersionId == materialA })
        assertFalse(transport.detailCalls.drop(detailCallsBeforePin).contains(taskB.taskId))
        val draft = scoped.drafts.single { it.kind == NoteArtifactKind.Analysis }
        repository.stageText(draft.localDraftId, draft.localRevision, "dirty A survives")
        val stagedDraft = repository.state.value.drafts.single { it.localDraftId == draft.localDraftId }
        val headBefore = scoped.artifactRefs.getValue(NoteArtifactKind.Analysis)
        val ui = repository.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialA),
            selectedKind = NoteArtifactKind.Analysis,
            pendingSelectionCandidateIds = mapOf(NoteArtifactKind.Analysis to "version-b-analysis"),
        )
        assertEquals(materialA, ui.material.materialVersionId)
        assertEquals(listOf("version-a-analysis"), ui.analysis.candidates.map { it.candidateId })
        assertEquals("version-a-analysis", ui.analysis.confirmedHeadCandidateId)
        assertNull(ui.analysis.pendingSelectionCandidateId)

        repository.refresh(noteId, selectedTaskId = taskB.taskId)
        assertEquals(materialA, repository.state.value.materialVersionId)
        assertEquals(taskA.taskId, repository.state.value.selectedTaskId)

        repository.regenerate(NoteArtifactKind.Analysis, materialA)

        assertEquals(1, transport.starts.size)
        val started = transport.starts.single()
        assertNotEquals(taskA.taskId, started.taskId)
        assertNotEquals(taskB.taskId, started.taskId)
        assertEquals(materialA, started.materialVersionId)
        assertEquals(asrA, started.asrTaskId)
        assertEquals(materialDigest(snapshotA.inputJson), started.inputSha256)
        assertEquals(materialDigest(snapshotA.manifestJson), started.manifestSha256)
        assertEquals(consentA, started.consent)
        assertEquals(setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis), started.kinds)
        assertEquals(3, tasks.tasks().size)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, tasks.read(taskB.taskId).localPhase)
        assertTrue(repository.state.value.tasks.all { it.materialVersionId == materialA })
        assertFalse(repository.state.value.tasks.any { it.taskId == taskB.taskId })
        assertEquals(headBefore, repository.state.value.artifactRefs.getValue(NoteArtifactKind.Analysis))
        val afterDraft = repository.state.value.drafts.single { it.localDraftId == draft.localDraftId }
        assertEquals(stagedDraft, afterDraft)
        assertEquals("dirty A survives", afterDraft.text)
        assertTrue(afterDraft.dirty)
        assertFalse(transport.detailCalls.drop(detailCallsBeforePin).contains(taskB.taskId))

        repository.regenerate(NoteArtifactKind.FullRecord, materialA)

        assertEquals(2, transport.starts.size)
        val fullStarted = transport.starts[1]
        assertNotEquals(started.taskId, fullStarted.taskId)
        assertNotEquals(taskB.taskId, fullStarted.taskId)
        assertEquals(materialA, fullStarted.materialVersionId)
        assertEquals(asrA, fullStarted.asrTaskId)
        assertEquals(consentA, fullStarted.consent)
        assertEquals(4, tasks.tasks().size)
        assertTrue(repository.state.value.tasks.all { it.materialVersionId == materialA })

        activeBinding = otherBinding
        repository.refresh(noteId)
        assertEquals(otherBinding, repository.state.value.taskBinding)
        assertNull(repository.state.value.materialVersionId)
        assertNull(repository.state.value.selectedTaskId)
        assertTrue(repository.state.value.tasks.isEmpty())
    }

    private fun repository() = NoteArtifactRepository(
        tasks = tasks,
        drafts = NoteArtifactDraftStore.at(temp.root),
        materials = NoteMaterialStore.at(temp.root),
        targetProvider = { NoteArtifactTarget(activeBinding, "alice") },
        factory = { transport },
    )

    private fun readyMaterial(label: String = "material"): String {
        val created = NoteDocument.create()
        val note = NoteDocument(JsonObject(created.source + ("id" to JsonPrimitive(noteId)))).withTitle(label)
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

    private fun version(
        versionId: String,
        kind: NoteArtifactKind,
        sourceVersionId: String,
        isHead: Boolean,
    ) = NoteArtifactVersionProjection(
        versionId = versionId,
        artifactId = if (kind == NoteArtifactKind.FullRecord) "artifact-full" else "artifact-analysis",
        kind = kind,
        author = NoteArtifactVersionAuthor.Model,
        parentVersionId = null,
        observedHeadVersionId = null,
        sourceCompleteRecordVersionId = null,
        sourceVersionId = sourceVersionId,
        generationTaskId = null,
        contentMarkdown = "content $versionId",
        contentSha256 = "c".repeat(64),
        hasConflict = false,
        createdAtLabel = "2026-10-30 10:00",
        isHead = isHead,
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
        var selectError: Throwable? = null
        val starts = mutableListOf<NoteArtifactTaskRecord>()
        val detailCalls = mutableListOf<String>()
        val details = mutableMapOf<String, NoteArtifactTaskDetail>()
        var artifactCalls = 0
        var artifactError: Throwable? = null
        var artifactFallback: List<NoteArtifactArtifactRefProjection> = emptyList()
        var versionFallback: Map<String, List<NoteArtifactVersionProjection>> = emptyMap()
        var detail: NoteArtifactTaskDetail? = null

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

        override suspend fun detail(taskId: String): NoteArtifactTaskDetail {
            detailCalls += taskId
            return details[taskId] ?: requireNotNull(detail) { "detail is not configured" }
        }

        override suspend fun artifacts(noteId: String): List<NoteArtifactArtifactRefProjection> {
            artifactCalls += 1
            artifactError?.let { throw it }
            return if (artifactResponses.isEmpty()) artifactFallback else artifactResponses.removeFirst()
        }

        override suspend fun versions(artifactId: String): List<NoteArtifactVersionProjection> =
            versionFallback[artifactId].orEmpty()

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
            selectError?.let { throw it }
            return versionId
        }
    }
}

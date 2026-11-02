package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("head-1", NoteArtifactKind.FullRecord, materialVersionId, isHead = true),
                version("candidate-version", NoteArtifactKind.FullRecord, materialVersionId, isHead = false),
            ),
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
        repository.stageText(draft.localDraftId, draft.localRevision, "manual body")
        val staged = repository.state.value.drafts.single { it.localDraftId == draft.localDraftId }
        repository.saveLocalDraft(staged.localDraftId, staged.localRevision, "manual body")
        val savedDraft = repository.state.value.drafts.single { it.localDraftId == draft.localDraftId }
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev-1", "head-1"),
            completeRef(NoteArtifactKind.Analysis, "artifact-analysis", "artifact-rev-a", "head-a"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("head-1", NoteArtifactKind.FullRecord, materialVersionId, isHead = true),
                version("candidate-version", NoteArtifactKind.FullRecord, materialVersionId, isHead = false),
            ),
        )

        repository.saveManualRevisionToServer(
            kind = NoteArtifactKind.FullRecord,
            artifactId = full.artifactId,
            localDraftId = savedDraft.localDraftId,
            expectedLocalRevision = savedDraft.localRevision,
            expectedHeadRevision = requireNotNull(full.revision),
            expectedHeadVersionId = full.headVersionId,
            contentMarkdown = savedDraft.text,
        )
        val acceptedDraft = NoteArtifactDraftStore.at(temp.root).read(binding, savedDraft.localDraftId)
        assertEquals("manual-version", acceptedDraft.baseServerArtifactVersionId)
        assertEquals("artifact-rev-manual", acceptedDraft.expectedServerRevision)
        assertFalse(acceptedDraft.dirty)
        repository.selectCandidate(
            artifactId = full.artifactId,
            expectedHeadRevision = requireNotNull(full.revision),
            versionId = "candidate-version",
        )

        assertEquals(listOf("artifact-rev-1"), transport.manualRevisionHeads)
        assertEquals(listOf("head-1"), transport.manualRevisionHeadVersions)
        assertEquals(listOf("artifact-rev-1"), transport.selectHeads)
    }

    @Test fun manualSaveRejectsDraftWhenConfirmedHeadBelongsToAnotherMaterial() = runBlocking {
        val task = seedTask()
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-revision", "version-b-full"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("version-b-full", NoteArtifactKind.FullRecord, "55555555-5555-4555-8555-555555555555", true),
            ),
        )
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "version-b-full"),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        val draft = repository.state.value.drafts.single { it.kind == NoteArtifactKind.FullRecord }

        repository.saveManualRevisionToServer(
            kind = NoteArtifactKind.FullRecord,
            artifactId = "artifact-full",
            localDraftId = draft.localDraftId,
            expectedLocalRevision = draft.localRevision,
            expectedHeadRevision = "artifact-revision",
            expectedHeadVersionId = "version-b-full",
            contentMarkdown = draft.text,
        )

        assertTrue(transport.manualRevisionHeads.isEmpty())
        assertTrue(repository.state.value.message.orEmpty().contains("其他固定材料"))
        assertEquals(draft, repository.state.value.drafts.single { it.localDraftId == draft.localDraftId })
    }

    @Test fun staleManualSaveCallbackCannotSendDraftToAnotherAccount() = runBlocking {
        val task = seedTask()
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-revision", "head-1"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("head-1", NoteArtifactKind.FullRecord, materialVersionId, isHead = true),
            ),
        )
        transport.detail = detail(
            partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-1"),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        val draft = repository.state.value.drafts.single { it.kind == NoteArtifactKind.FullRecord }

        activeBinding = otherBinding
        repository.saveManualRevisionToServer(
            kind = NoteArtifactKind.FullRecord,
            artifactId = "artifact-full",
            localDraftId = draft.localDraftId,
            expectedLocalRevision = draft.localRevision,
            expectedHeadRevision = "artifact-revision",
            expectedHeadVersionId = "head-1",
            contentMarkdown = draft.text,
        )

        assertTrue(transport.manualRevisionHeads.isEmpty())
        assertTrue(repository.state.value.message.orEmpty().contains("当前账号或服务器"))
    }

    @Test fun retrySelectedStepsSendsOnlyExplicitFailedKeysAndKeepsSucceededStepsOut() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("task-rev", "NeedsAttention", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        transport.detail = detailView(
            NoteArtifactRemoteTaskView("task-rev", "NeedsAttention", observedAtMillis = 2_000),
            steps = listOf(
                step("image-1", "Succeeded"),
                step("record-1", "Failed", error = "upstream_timeout"),
                step("analysis-1", "Blocked", error = "dependency_failed"),
            ),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)

        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("record-1"),
            duplicateChargeConfirmed = true,
        )

        assertEquals(listOf(listOf("record-1")), transport.retryStepKeys)
        assertEquals("NeedsAttention", tasks.read(task.taskId).remote?.phase)
        assertEquals("task-rev-retry-1", tasks.read(task.taskId).remote?.revision)
        assertNull(tasks.read(task.taskId).pendingRetry)
    }

    @Test fun concurrentStepRetryDoesNotQueueSecondChargedRequest() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("task-rev", "NeedsAttention", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        transport.detail = detailView(
            NoteArtifactRemoteTaskView("task-rev", "NeedsAttention", observedAtMillis = 2_000),
            steps = listOf(step("record-1", "Failed", error = "upstream_timeout")),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        val enteredTransport = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        transport.onRetry = {
            enteredTransport.complete(Unit)
            releaseTransport.await()
        }

        val first = async {
            repository.retrySelectedSteps(
                taskId = task.taskId,
                noteId = noteId,
                materialVersionId = materialVersionId,
                stepKeys = listOf("record-1"),
                duplicateChargeConfirmed = true,
            )
        }
        enteredTransport.await()
        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("record-1"),
            duplicateChargeConfirmed = true,
        )
        assertEquals(1, transport.retryStepKeys.size)

        releaseTransport.complete(Unit)
        first.await()
        assertEquals(1, transport.retryStepKeys.size)
        assertNull(tasks.read(task.taskId).pendingRetry)
    }

    @Test fun unknownRetryReceiptNeedsExplicitReplayWithSameRetryId() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("task-rev", "Completed", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        transport.detail = detailView(
            NoteArtifactRemoteTaskView("task-rev", "Completed", observedAtMillis = 2_000),
            steps = listOf(step("record-1", "Failed", error = "upstream_timeout")),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        transport.retryFailure = IOException("response was lost")

        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("record-1"),
            duplicateChargeConfirmed = true,
        )

        val pending = requireNotNull(tasks.read(task.taskId).pendingRetry)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, pending.phase)
        assertEquals(1, transport.retryStepKeys.size)
        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("record-1"),
            duplicateChargeConfirmed = true,
        )
        assertEquals(1, transport.retryStepKeys.size)
        assertTrue(repository.state.value.message.orEmpty().contains("核对原恢复请求"))

        repository.replayRetry(task.taskId, freshlyConfirmed = true)

        assertEquals(2, transport.retryStepKeys.size)
        assertEquals(listOf(pending.retryId, pending.retryId), transport.retryIds)
        assertNull(tasks.read(task.taskId).pendingRetry)
    }

    @Test fun retrySelectedStepsRejectsSucceededOrStaleStepsBeforeTransport() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("task-rev-new", "NeedsAttention", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        transport.detail = detailView(
            NoteArtifactRemoteTaskView("task-rev-old", "NeedsAttention", observedAtMillis = 1_000),
            steps = listOf(step("done", "Succeeded"), step("failed", "Failed")),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("task-rev-new", "NeedsAttention", observedAtMillis = 2_100),
            nowMillis = 2_100,
        )

        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("failed"),
            duplicateChargeConfirmed = true,
        )
        assertTrue(transport.retryStepKeys.isEmpty())
        assertTrue(repository.state.value.message.orEmpty().contains("进度已变化"))

        transport.detail = detailView(
            NoteArtifactRemoteTaskView("task-rev-new", "NeedsAttention", observedAtMillis = 2_100),
            steps = listOf(step("done", "Succeeded"), step("failed", "Failed")),
        )
        repository.refresh(noteId, materialVersionId, task.taskId)
        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("done"),
            duplicateChargeConfirmed = true,
        )

        assertTrue(transport.retryStepKeys.isEmpty())
        assertTrue(repository.state.value.message.orEmpty().contains("已成功或执行中"))

        transport.detail = detailView(
            NoteArtifactRemoteTaskView("task-rev-running", "Running", observedAtMillis = 2_200),
            steps = listOf(step("failed", "Failed")),
        )
        repository.refresh(noteId, materialVersionId, task.taskId)
        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("failed"),
            duplicateChargeConfirmed = true,
        )

        assertTrue(transport.retryStepKeys.isEmpty())
        assertTrue(repository.state.value.message.orEmpty().contains("仍在执行中"))
    }

    @Test fun inputBlockedTaskRejectsStepRetryBeforeTransport() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("blocked-rev", "Blocked", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        transport.detail = detailView(
            NoteArtifactRemoteTaskView("blocked-rev", "Blocked", observedAtMillis = 2_000),
            steps = listOf(step("record-1", "Failed", error = "input_blocked")),
        )
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)

        repository.retrySelectedSteps(
            taskId = task.taskId,
            noteId = noteId,
            materialVersionId = materialVersionId,
            stepKeys = listOf("record-1"),
            duplicateChargeConfirmed = true,
        )

        assertTrue(transport.retryStepKeys.isEmpty())
        assertTrue(repository.state.value.message.orEmpty().contains("输入级阻断"))
    }

    @Test fun manualRevisionConflictKeepsLocalDraftAndObservedServerHead() = runBlocking {
        val task = seedTask()
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-revision-1", "head-1"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("head-1", NoteArtifactKind.FullRecord, materialVersionId, true),
            ),
        )
        transport.detail = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-1"))
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        val initialDraft = repository.state.value.drafts.single { it.kind == NoteArtifactKind.FullRecord }
        repository.stageText(initialDraft.localDraftId, initialDraft.localRevision, "manual body").also {
            repository.saveLocalDraft(initialDraft.localDraftId, initialDraft.localRevision + 1, "manual body")
        }
        val savedDraft = repository.state.value.drafts.single { it.localDraftId == initialDraft.localDraftId }
        val draftStore = NoteArtifactDraftStore.at(temp.root)
        transport.onManualRevision = {
            draftStore.stageText(binding, savedDraft.localDraftId, savedDraft.localRevision, "newer local edit")
        }
        transport.manualReceipt = NoteArtifactMutationProjection(
            artifactRevision = "artifact-revision-2",
            versionId = "manual-conflict",
            hasConflict = true,
            isHead = false,
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("manual-conflict", NoteArtifactKind.FullRecord, materialVersionId, false)
                    .copy(author = NoteArtifactVersionAuthor.User, hasConflict = true),
                version("head-1", NoteArtifactKind.FullRecord, materialVersionId, true),
            ),
        )
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-revision-2", "head-1"),
        )

        repository.saveManualRevisionToServer(
            kind = NoteArtifactKind.FullRecord,
            artifactId = "artifact-full",
            localDraftId = savedDraft.localDraftId,
            expectedLocalRevision = savedDraft.localRevision,
            expectedHeadRevision = "artifact-revision-1",
            expectedHeadVersionId = "head-1",
            contentMarkdown = savedDraft.text,
        )

        val after = draftStore.read(binding, savedDraft.localDraftId)
        assertEquals("newer local edit", after.text)
        assertEquals(savedDraft.savedText, after.savedText)
        assertTrue(after.dirty)
        assertEquals("head-1", after.baseServerArtifactVersionId)
        assertEquals("artifact-revision-1", after.expectedServerRevision)
        assertEquals("head-1", repository.state.value.artifactRefs[NoteArtifactKind.FullRecord]?.headVersionId)
        assertTrue(repository.state.value.versions[NoteArtifactKind.FullRecord].orEmpty()
            .any { it.versionId == "manual-conflict" && it.hasConflict && !it.isHead })
        assertTrue(repository.state.value.message.orEmpty().contains("双方内容均保留"))
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

    @Test fun refreshPersistsSucceededDetailAndReopenKeepsRegenerationAvailable() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("local-queued", "Queued", observedAtMillis = 1_000),
            nowMillis = 1_000,
        )
        val retry = NoteArtifactRetryIntent(
            retryId = "99999999-9999-4999-8999-999999999999",
            expectedServerRevision = "server-succeeded",
            stepKeys = listOf("record-synthesis"),
            phase = NoteArtifactIntentPhase.VerificationPending,
            createdAtMillis = 1_100,
        )
        tasks.update(task.taskId, nowMillis = 1_100) {
            it.copy(
                stopPhase = NoteArtifactIntentPhase.VerificationPending,
                pendingRetry = retry,
            )
        }
        val otherVersionTask = tasks.createIntent(
            binding = binding,
            noteId = noteId,
            materialVersionId = "55555555-5555-4555-8555-555555555555",
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = "d".repeat(64),
            manifestSha256 = "e".repeat(64),
            consent = consent,
            ownerName = "alice",
            nowMillis = 3_000,
        )
        tasks.update(otherVersionTask.taskId, nowMillis = 3_100) {
            it.copy(
                localPhase = NoteArtifactIntentPhase.VerificationPending,
                lastProblem = "other material unknown",
            )
        }
        val succeeded = NoteArtifactRemoteTaskView(
            revision = "server-succeeded",
            phase = "Succeeded",
            stopRequested = false,
            observedAtMillis = 2_000,
        )
        transport.details[task.taskId] = detailView(succeeded)
        val repository = repository()

        repository.refresh(noteId, materialVersionId, task.taskId)

        val refreshed = repository.state.value.tasks.single()
        assertEquals(task.taskId, repository.state.value.selectedTaskId)
        assertEquals("Succeeded", refreshed.remote?.phase)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, refreshed.stopPhase)
        assertEquals(retry, refreshed.pendingRetry)
        assertFalse(repository.state.value.tasks.any { it.taskId == otherVersionTask.taskId })
        assertEquals(
            NoteArtifactIntentPhase.VerificationPending,
            tasks.read(otherVersionTask.taskId).localPhase,
        )
        assertEquals(NoteArtifactWorkPhase.Ready, repository.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        ).fullRecord.workPhase)
        assertEquals("Succeeded", tasks.read(task.taskId).remote?.phase)

        transport.detailError = IOException("detail unavailable after reopen")
        val reopened = repository()
        reopened.refresh(noteId, materialVersionId)

        val reopenedTask = reopened.state.value.tasks.single()
        assertEquals("Succeeded", reopenedTask.remote?.phase)
        assertNotNull(reopened.state.value.message)
        val reopenedUi = reopened.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        )
        assertEquals(NoteArtifactWorkPhase.Ready, reopenedUi.fullRecord.workPhase)
        assertTrue(reopenedUi.fullRecord.canRegenerate)
    }

    @Test fun detailReadFailureKeepsQueuedTaskAndDoesNotFakeCompletion() = runBlocking {
        val task = seedTask()
        tasks.recordRemote(
            task.taskId,
            NoteArtifactRemoteTaskView("local-queued", "Queued", observedAtMillis = 1_000),
            nowMillis = 1_000,
        )
        transport.detailError = IOException("detail read failed")
        val repository = repository()

        repository.refresh(noteId, materialVersionId, task.taskId)

        val retained = repository.state.value.tasks.single()
        assertEquals("Queued", retained.remote?.phase)
        assertNotNull(repository.state.value.message)
        val ui = repository.state.value.toUiState(
            entry = NoteArtifactEntry(noteId, materialVersionId),
            selectedKind = NoteArtifactKind.FullRecord,
        )
        assertEquals(NoteArtifactWorkPhase.Running, ui.fullRecord.workPhase)
        assertFalse(ui.fullRecord.canRegenerate)
        assertEquals("Queued", tasks.read(task.taskId).remote?.phase)
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

    @Test fun lateRefreshForPreviousOwnerCannotPublishTasksOrOverwriteCurrentDraftScope() = runBlocking {
        val taskA = seedTask()
        tasks.recordRemote(
            taskA.taskId,
            NoteArtifactRemoteTaskView("task-a-rev", "Succeeded", observedAtMillis = 1_100),
            nowMillis = 1_100,
        )
        val taskB = tasks.createIntent(
            binding = otherBinding,
            noteId = noteId,
            materialVersionId = materialVersionId,
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = "d".repeat(64),
            manifestSha256 = "e".repeat(64),
            consent = consent,
            ownerName = "bob",
            nowMillis = 2_000,
        )
        tasks.recordRemote(
            taskB.taskId,
            NoteArtifactRemoteTaskView("task-b-rev", "Succeeded", observedAtMillis = 2_100),
            nowMillis = 2_100,
        )
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev", "head-a"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("head-a", NoteArtifactKind.FullRecord, materialVersionId, isHead = true),
            ),
        )
        transport.details[taskA.taskId] = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-a"))
        transport.details[taskB.taskId] = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-a"))
        val repository = repository()
        repository.refresh(noteId, materialVersionId, taskA.taskId)
        val draftA = repository.state.value.drafts.first { it.kind == NoteArtifactKind.FullRecord }
        repository.stageText(draftA.localDraftId, draftA.localRevision, "dirty owner A")
        val detailCallsBefore = transport.detailCalls.toList()

        val detailEntered = CompletableDeferred<Unit>()
        val releaseDetail = CompletableDeferred<Unit>()
        transport.onDetail = { taskId ->
            if (taskId == taskA.taskId && !detailEntered.isCompleted) {
                detailEntered.complete(Unit)
                releaseDetail.await()
            }
        }
        val staleRefresh = async { repository.refresh(noteId, materialVersionId, taskA.taskId) }
        detailEntered.await()
        activeBinding = otherBinding
        val currentRefresh = async { repository.refresh(noteId, materialVersionId, taskB.taskId) }
        releaseDetail.complete(Unit)
        staleRefresh.await()
        currentRefresh.await()

        val current = repository.state.value
        assertEquals(otherBinding, current.taskBinding)
        assertEquals(taskB.taskId, current.selectedTaskId)
        assertEquals(listOf(taskB.taskId), current.tasks.map { it.taskId })
        assertTrue(current.drafts.isNotEmpty())
        assertTrue(current.drafts.all { it.binding == otherBinding })
        assertEquals(1, transport.detailCalls.drop(detailCallsBefore.size).count { it == taskA.taskId })
        assertEquals(1, transport.detailCalls.drop(detailCallsBefore.size).count { it == taskB.taskId })
        assertEquals("Succeeded", tasks.read(taskA.taskId).remote?.phase)
        assertEquals("dirty owner A", NoteArtifactDraftStore.at(temp.root).read(binding, draftA.localDraftId).text)
        assertTrue(NoteArtifactDraftStore.at(temp.root).read(binding, draftA.localDraftId).dirty)
    }

    @Test fun refreshQueuedForOldOwnerDoesNotInheritItsMaterialVersionAfterScopeSwitch() = runBlocking {
        val materialB = "55555555-5555-4555-8555-555555555555"
        val taskA = seedTask()
        val taskB = tasks.createIntent(
            binding = otherBinding,
            noteId = noteId,
            materialVersionId = materialB,
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = "c".repeat(64),
            manifestSha256 = "d".repeat(64),
            consent = consent,
            ownerName = "bob",
            nowMillis = 3_000,
        )
        tasks.recordRemote(taskA.taskId, NoteArtifactRemoteTaskView("a-rev", "Succeeded"), nowMillis = 2_000)
        tasks.recordRemote(taskB.taskId, NoteArtifactRemoteTaskView("b-rev", "Succeeded"), nowMillis = 3_100)
        transport.details[taskA.taskId] = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-a"))
        transport.details[taskB.taskId] = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-b"))

        var signalNextTargetRead: CompletableDeferred<Unit>? = null
        val repository = repository {
            val capturedBinding = activeBinding
            signalNextTargetRead?.also {
                signalNextTargetRead = null
                it.complete(Unit)
            }
            NoteArtifactTarget(capturedBinding, if (capturedBinding == binding) "alice" else "bob")
        }
        repository.refresh(noteId, materialVersionId, taskA.taskId)

        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        transport.onDestinations = {
            if (!blockerEntered.isCompleted) {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
            }
        }
        val blocker = async { repository.refresh(noteId, materialVersionId, taskA.taskId) }
        blockerEntered.await()

        val queuedTargetCaptured = CompletableDeferred<Unit>()
        signalNextTargetRead = queuedTargetCaptured
        val queuedForA = async { repository.refresh(noteId) }
        queuedTargetCaptured.await()
        activeBinding = otherBinding
        releaseBlocker.complete(Unit)
        blocker.await()
        queuedForA.await()

        assertEquals(otherBinding, repository.state.value.taskBinding)
        assertEquals(materialB, repository.state.value.materialVersionId)
        assertEquals(listOf(taskB.taskId), repository.state.value.tasks.map { it.taskId })
    }

    @Test fun staleRefreshCannotClearStateAfterNewGenerationWinsDuringAbandon() = runBlocking {
        val taskA = seedTask()
        tasks.recordRemote(taskA.taskId, NoteArtifactRemoteTaskView("a-rev", "Succeeded"), nowMillis = 2_000)
        transport.details[taskA.taskId] = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-a"))
        val currentMaterial = "55555555-5555-4555-8555-555555555555"
        val taskB = tasks.createIntent(
            binding = otherBinding,
            noteId = noteId,
            materialVersionId = currentMaterial,
            kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            inputSha256 = "c".repeat(64),
            manifestSha256 = "d".repeat(64),
            consent = consent,
            ownerName = "bob",
            nowMillis = 3_000,
        )
        tasks.recordRemote(taskB.taskId, NoteArtifactRemoteTaskView("b-rev", "Succeeded"), nowMillis = 3_100)
        transport.details[taskB.taskId] = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-b"))
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val providerReads = java.util.concurrent.atomic.AtomicInteger(0)
        val staleProviderEntered = java.util.concurrent.CountDownLatch(1)
        val releaseStaleProvider = java.util.concurrent.CountDownLatch(1)
        val currentProviderRead = java.util.concurrent.CountDownLatch(1)
        val targetProvider: () -> NoteArtifactTarget? = {
            if (armed.get()) {
                when (providerReads.incrementAndGet()) {
                    3 -> {
                        staleProviderEntered.countDown()
                        check(releaseStaleProvider.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    4 -> currentProviderRead.countDown()
                }
            }
            NoteArtifactTarget(activeBinding, if (activeBinding == binding) "alice" else "bob")
        }
        val repo = repository(targetProvider)
        repo.refresh(noteId, materialVersionId, taskA.taskId)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.onDetail = { id ->
            if (id == taskA.taskId && !entered.isCompleted) {
                entered.complete(Unit)
                release.await()
            }
        }
        val old = async { repo.refresh(noteId, materialVersionId, taskA.taskId) }
        entered.await()
        activeBinding = otherBinding
        armed.set(true)
        release.complete(Unit)
        assertTrue(staleProviderEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val current = async { repo.refresh(noteId, currentMaterial, taskB.taskId) }
        assertTrue(currentProviderRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
        releaseStaleProvider.countDown()
        old.await()
        current.await()

        assertEquals(otherBinding, repo.state.value.taskBinding)
        assertEquals(taskB.taskId, repo.state.value.selectedTaskId)
        assertEquals(listOf(taskB.taskId), repo.state.value.tasks.map { it.taskId })
        assertFalse(repo.state.value.message.orEmpty().contains("旧请求回执未应用"))
    }

    @Test fun candidateFromAnotherSourceVersionIsRejectedBeforeSelectionRequest() = runBlocking {
        val task = seedTask()
        val otherMaterialVersion = "55555555-5555-4555-8555-555555555555"
        transport.artifactFallback = listOf(
            completeRef(NoteArtifactKind.FullRecord, "artifact-full", "artifact-rev", "head-current"),
        )
        transport.versionFallback = mapOf(
            "artifact-full" to listOf(
                version("head-current", NoteArtifactKind.FullRecord, materialVersionId, isHead = true),
                version("candidate-other-source", NoteArtifactKind.FullRecord, otherMaterialVersion, isHead = false),
            ),
        )
        transport.detail = detail(partialRef(NoteArtifactKind.FullRecord, "artifact-full", "head-current"))
        val repository = repository()
        repository.refresh(noteId, materialVersionId, task.taskId)
        val draft = repository.state.value.drafts.first { it.kind == NoteArtifactKind.FullRecord }
        repository.stageText(draft.localDraftId, draft.localRevision, "dirty stays local")

        repository.selectCandidate("artifact-full", "artifact-rev", "candidate-other-source")

        assertTrue(transport.selectHeads.isEmpty())
        assertEquals("dirty stays local", repository.state.value.drafts.single { it.localDraftId == draft.localDraftId }.text)
        assertTrue(repository.state.value.message.orEmpty().contains("固定材料"))
    }

    private fun repository(
        targetProvider: (() -> NoteArtifactTarget?)? = null,
    ) = NoteArtifactRepository(
        tasks = tasks,
        drafts = NoteArtifactDraftStore.at(temp.root),
        materials = NoteMaterialStore.at(temp.root),
        targetProvider = targetProvider ?: { NoteArtifactTarget(activeBinding, "alice") },
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

    private fun detail(vararg artifacts: NoteArtifactArtifactRefProjection) = detailView(
        NoteArtifactRemoteTaskView(
            revision = "task-rev",
            phase = "Completed",
            observedAtMillis = 1_000,
        ),
        *artifacts,
    )

    private fun detailView(
        view: NoteArtifactRemoteTaskView,
        vararg artifacts: NoteArtifactArtifactRefProjection,
        steps: List<NoteArtifactStepProjection> = emptyList(),
    ) = NoteArtifactTaskDetail(
        view = view,
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
        steps = steps,
        artifacts = artifacts.toList(),
    )

    private fun step(key: String, status: String, error: String? = null) =
        NoteArtifactStepProjection(
            key = key,
            kind = "RecordSynthesis",
            status = status,
            attemptCount = if (error == null) 1 else 2,
            lastErrorCode = error,
            lastErrorMessage = error?.replace('_', ' '),
        )

    private class FakeTransport : NoteArtifactTransport {
        val artifactResponses = ArrayDeque<List<NoteArtifactArtifactRefProjection>>()
        val manualRevisionHeads = mutableListOf<String>()
        val manualRevisionHeadVersions = mutableListOf<String?>()
        val selectHeads = mutableListOf<String>()
        var selectError: Throwable? = null
        val starts = mutableListOf<NoteArtifactTaskRecord>()
        val retryStepKeys = mutableListOf<List<String>>()
        val retryIds = mutableListOf<String>()
        var manualReceipt = NoteArtifactMutationProjection(
            artifactRevision = "artifact-rev-manual",
            versionId = "manual-version",
            hasConflict = false,
            isHead = true,
        )
        var onManualRevision: (() -> Unit)? = null
        var onRetry: suspend () -> Unit = {}
        var retryFailure: Throwable? = null
        val detailCalls = mutableListOf<String>()
        val details = mutableMapOf<String, NoteArtifactTaskDetail>()
        var onDetail: suspend (String) -> Unit = {}
        var artifactCalls = 0
        var artifactError: Throwable? = null
        var artifactFallback: List<NoteArtifactArtifactRefProjection> = emptyList()
        var versionFallback: Map<String, List<NoteArtifactVersionProjection>> = emptyMap()
        var detail: NoteArtifactTaskDetail? = null
        var detailError: Throwable? = null
        var onDestinations: suspend () -> Unit = {}

        override suspend fun destinations(): List<NoteArtifactDestination> {
            onDestinations()
            return listOf(NoteArtifactDestination(
                modelId = "66666666-6666-4666-8666-666666666666",
                modelRevision = "77777777-7777-4777-8777-777777777777",
                displayName = "测试模型",
                model = "model-a",
                endpoint = "https://model.example/v1/responses",
                capabilities = listOf("text", "vision"),
                backgroundProcessingAvailable = true,
            ))
        }

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
            onDetail(taskId)
            detailError?.let { throw it }
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
        ): NoteArtifactRemoteTaskView {
            retryStepKeys += retry.stepKeys
            retryIds += retry.retryId
            onRetry()
            retryFailure?.let {
                retryFailure = null
                throw it
            }
            val view = NoteArtifactRemoteTaskView(
                revision = "task-rev-retry-${retryStepKeys.size}",
                phase = "NeedsAttention",
                observedAtMillis = 2_200,
            )
            (details[record.taskId] ?: detail)?.let { details[record.taskId] = it.copy(view = view) }
            return view
        }

        override suspend fun saveManualRevision(
            artifactId: String,
            revisionId: String,
            expectedHeadRevision: String,
            expectedHeadVersionId: String?,
            contentMarkdown: String,
        ): NoteArtifactMutationProjection {
            manualRevisionHeads += expectedHeadRevision
            manualRevisionHeadVersions += expectedHeadVersionId
            onManualRevision?.invoke()
            return manualReceipt
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

package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteArtifactTaskStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val otherOwner = "22222222-2222-4222-8222-222222222222"
    private val binding = BoundUploadSession("https://pim.example", owner)
    private val otherBinding = BoundUploadSession("https://pim.example", otherOwner)
    private val noteId = "33333333-3333-4333-8333-333333333333"
    private val materialVersionId = "44444444-4444-4444-8444-444444444444"
    private val otherMaterialVersionId = "55555555-5555-4555-8555-555555555555"
    private val modelId = "66666666-6666-4666-8666-666666666666"
    private val modelRevision = "77777777-7777-4777-8777-777777777777"
    private val consent = NoteArtifactConsent(
        confirmed = true,
        modelId = modelId,
        modelRevision = modelRevision,
        endpoint = "https://model.example/v1/responses",
    )

    private fun store() = NoteArtifactTaskStore.at(temp.root)

    private fun intent(
        kinds: Set<NoteArtifactKind> = setOf(NoteArtifactKind.FullRecord),
        material: String = materialVersionId,
        target: BoundUploadSession = binding,
    ) = store().createIntent(
        binding = target,
        noteId = noteId,
        materialVersionId = material,
        kinds = kinds,
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        consent = consent,
        destinationDisplayName = "合成模型",
        destinationModel = "model-a",
        destinationCapabilities = listOf("text", "vision"),
        ownerName = "alice",
        nowMillis = 1_000,
    )

    @Test fun intentPersistsTaskOwnerServerFixedMaterialKindAndConsentWithoutTokens() {
        val record = intent()

        val reloaded = store().read(record.taskId)
        assertEquals(record, reloaded)
        assertEquals(binding, reloaded.binding)
        assertEquals(materialVersionId, reloaded.materialVersionId)
        assertEquals(NoteArtifactKind.FullRecord, reloaded.kind)
        assertEquals(setOf(NoteArtifactKind.FullRecord), reloaded.kinds)
        assertEquals(consent, reloaded.consent)
        assertEquals(NoteArtifactIntentPhase.Prepared, reloaded.localPhase)
        val raw = File(temp.root, "note-artifact-tasks/${record.taskId}.json").readText()
        assertFalse(raw.contains("accessToken"))
        assertFalse(raw.contains("refreshToken"))
        assertFalse(raw.contains("Bearer"))
        assertFalse(raw.contains("Authorization"))
    }

    @Test fun pendingSameIntentIsIdempotentButKindAndMaterialRemainSeparate() {
        val full = intent()
        val same = intent()
        val analysis = intent(kinds = setOf(NoteArtifactKind.Analysis))
        val otherMaterial = intent(material = otherMaterialVersionId)

        assertEquals(full.taskId, same.taskId)
        assertNotEquals(full.taskId, analysis.taskId)
        assertNotEquals(full.taskId, otherMaterial.taskId)
        assertEquals(3, store().tasks().size)
    }

    @Test fun pendingSharedIntentIsIdempotentAcrossBothUiKinds() {
        val kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis)
        val first = intent(kinds)
        val repeated = intent(kinds)

        assertEquals(first.taskId, repeated.taskId)
        assertEquals(kinds, repeated.kinds)
        assertTrue(repeated.supports(NoteArtifactKind.FullRecord))
        assertTrue(repeated.supports(NoteArtifactKind.Analysis))
        assertEquals(1, store().tasks().size)
    }

    @Test fun acceptedOrRejectedIntentDoesNotBlockANewExplicitTask() {
        val first = intent()
        store().recordRemote(
            first.taskId,
            NoteArtifactRemoteTaskView("rev-1", "Queued", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        val second = intent()
        assertNotEquals(first.taskId, second.taskId)

        store().update(second.taskId) {
            it.copy(localPhase = NoteArtifactIntentPhase.Rejected, lastProblem = "拒绝")
        }
        val third = intent()
        assertNotEquals(second.taskId, third.taskId)
    }

    @Test fun taskIdentityCannotBeReboundToAnotherMaterialKindOwnerOrConsent() {
        val record = intent()
        assertThrows(IllegalArgumentException::class.java) {
            store().update(record.taskId) { it.copy(materialVersionId = otherMaterialVersionId) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().update(record.taskId) {
                it.copy(kind = NoteArtifactKind.Analysis, kinds = setOf(NoteArtifactKind.Analysis))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().update(record.taskId) { it.copy(ownerId = otherOwner, expectedUserId = otherOwner) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().update(record.taskId) {
                it.copy(consent = consent.copy(modelRevision = "88888888-8888-4888-8888-888888888888"))
            }
        }
        assertEquals(record, store().read(record.taskId))
    }

    @Test fun unconfirmedOrUnsafeConsentIsRejectedBeforeAnySend() {
        assertThrows(IllegalArgumentException::class.java) {
            store().createIntent(
                binding = binding,
                noteId = noteId,
                materialVersionId = materialVersionId,
                kinds = setOf(NoteArtifactKind.FullRecord),
                inputSha256 = "a".repeat(64),
                manifestSha256 = "b".repeat(64),
                consent = consent.copy(confirmed = false),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().createIntent(
                binding = binding,
                noteId = noteId,
                materialVersionId = materialVersionId,
                kinds = setOf(NoteArtifactKind.FullRecord),
                inputSha256 = "a".repeat(64),
                manifestSha256 = "b".repeat(64),
                consent = consent.copy(endpoint = "http://user:secret@model.example/v1"),
            )
        }
        assertTrue(store().tasks().isEmpty())
    }

    @Test fun remoteViewAdvancesPhaseAndClearsUnknownStopOnlyWhenServerConfirms() {
        val record = intent()
        store().update(record.taskId) {
            it.copy(stopPhase = NoteArtifactIntentPhase.VerificationPending)
        }
        val running = store().recordRemote(
            record.taskId,
            NoteArtifactRemoteTaskView("rev-1", "Running", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        assertEquals(NoteArtifactIntentPhase.Accepted, running.localPhase)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, running.stopPhase)
        assertNull(running.lastProblem)

        val stopped = store().recordRemote(
            record.taskId,
            NoteArtifactRemoteTaskView("rev-2", "Stopped", stopRequested = true, observedAtMillis = 3_000),
            nowMillis = 3_000,
        )
        assertNull(stopped.stopPhase)
    }

    @Test fun pendingRetrySurvivesRestartOnlyWhileItsExpectedRevisionMatches() {
        val record = intent()
        store().recordRemote(
            record.taskId,
            NoteArtifactRemoteTaskView("rev-1", "NeedsAttention", observedAtMillis = 2_000),
            nowMillis = 2_000,
        )
        val retry = NoteArtifactRetryIntent(
            retryId = "99999999-9999-4999-8999-999999999999",
            expectedServerRevision = "rev-1",
            stepKeys = listOf("step/photo-1", "step/synthesis"),
            phase = NoteArtifactIntentPhase.VerificationPending,
            createdAtMillis = 3_000,
        )
        store().update(record.taskId) { it.copy(pendingRetry = retry) }
        assertEquals(retry, store().read(record.taskId).pendingRetry)

        val sameRevision = store().recordRemote(
            record.taskId,
            NoteArtifactRemoteTaskView("rev-1", "NeedsAttention", observedAtMillis = 4_000),
            nowMillis = 4_000,
        )
        assertEquals(retry, sameRevision.pendingRetry)

        val advanced = store().recordRemote(
            record.taskId,
            NoteArtifactRemoteTaskView("rev-2", "Running", observedAtMillis = 5_000),
            nowMillis = 5_000,
        )
        assertNull(advanced.pendingRetry)
    }

    @Test fun damagedFileDoesNotHideOtherDurableTasks() {
        val record = intent()
        File(temp.root, "note-artifact-tasks/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.json")
            .writeText("{broken")
        val loaded = store().load()
        assertTrue(loaded.loadFailed)
        assertEquals(listOf(record.taskId), loaded.tasks.map { it.taskId })
        assertEquals(record, store().read(record.taskId))
    }
}

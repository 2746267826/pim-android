package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteArtifactControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val otherOwner = "22222222-2222-4222-8222-222222222222"
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
    private val store by lazy { NoteArtifactTaskStore.at(temp.root) }

    private fun controller(target: BoundUploadSession = binding) =
        NoteArtifactController(store, transport, target)

    private fun prepared() = controller().prepare(
        noteId = noteId,
        materialVersionId = materialVersionId,
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        consent = consent,
        destinationDisplayName = "合成模型",
        ownerName = "alice",
    )

    @Test fun startSendsTheDurableTaskIdAndRecordsConfirmedServerView() = runBlocking {
        val record = prepared()
        val saved = controller().start(record.taskId, freshlyConfirmed = true)

        assertEquals(listOf(record.taskId), transport.starts.map { it.taskId })
        assertEquals(record.taskId, saved.taskId)
        assertEquals(NoteArtifactIntentPhase.Accepted, saved.localPhase)
        assertEquals("rev-1", saved.remote?.revision)
        assertEquals("Queued", saved.remote?.phase)
        assertEquals(1, store.tasks().size)
    }

    @Test fun oneConfirmationWithBothKindsStartsOneSharedTaskAndKeepsBothAssociations() = runBlocking {
        val kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis)
        val record = prepared()
        transport.startView = view("rev-1", "Queued").copy(
            completeRecordArtifactId = "artifact-full",
            analysisArtifactId = "artifact-analysis",
        )

        val saved = controller().start(record.taskId, freshlyConfirmed = true)

        assertEquals(1, transport.starts.size)
        assertEquals(setOf(record.taskId), transport.starts.map { it.taskId }.toSet())
        assertEquals(kinds, transport.starts.single().kinds)
        assertEquals(kinds, saved.kinds)
        assertTrue(saved.supports(NoteArtifactKind.FullRecord))
        assertTrue(saved.supports(NoteArtifactKind.Analysis))
        assertEquals("artifact-full", saved.remote?.completeRecordArtifactId)
        assertEquals("artifact-analysis", saved.remote?.analysisArtifactId)
        assertEquals(1, store.tasks().size)
    }

    @Test fun viewPreferenceNeverNarrowsTheTwoArtifactsProducedByOneTask() {
        val record = prepared()

        assertEquals(
            setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis),
            record.kinds,
        )
        assertTrue(record.supports(NoteArtifactKind.FullRecord))
        assertTrue(record.supports(NoteArtifactKind.Analysis))
    }

    @Test fun unknownStartReceiptIsNeverResentAndVerifyUsesTheSameTaskId() = runBlocking {
        val record = prepared()
        transport.startError = IOException("timeout after send")
        assertThrows(IOException::class.java) {
            runBlocking { controller().start(record.taskId, freshlyConfirmed = true) }
        }
        val pending = store.read(record.taskId)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, pending.localPhase)
        assertTrue(pending.lastProblem.orEmpty().contains("不自动重发"))
        assertEquals(1, transport.starts.size)

        transport.getView = view("rev-1", "Queued")
        val verified = controller().verify(record.taskId)
        assertEquals(listOf(record.taskId), transport.gets)
        assertEquals(1, transport.starts.size)
        assertEquals(NoteArtifactIntentPhase.Accepted, verified.localPhase)
    }

    @Test fun unknownSharedStartReplayFromEitherKindUsesTheSameTaskId() = runBlocking {
        val kinds = setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis)
        val record = prepared()
        transport.startError = IOException("timeout after send")
        assertThrows(IOException::class.java) {
            runBlocking { controller().start(record.taskId, freshlyConfirmed = true) }
        }

        val pending = store.tasks().single()
        assertEquals(record.taskId, pending.taskId)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, pending.localPhase)
        assertTrue(pending.supports(NoteArtifactKind.FullRecord))
        assertTrue(pending.supports(NoteArtifactKind.Analysis))
        assertEquals(record.taskId, prepared().taskId)

        transport.startError = null
        controller().replayStart(record.taskId, freshlyConfirmed = true)

        assertEquals(2, transport.starts.size)
        assertEquals(setOf(record.taskId), transport.starts.map { it.taskId }.toSet())
        assertEquals(1, store.tasks().size)
    }

    @Test fun replayStartRequiresFreshConfirmationAndKeepsTheOriginalTaskId() = runBlocking {
        val record = prepared()
        transport.startError = IOException("timeout after send")
        runCatching { controller().start(record.taskId, freshlyConfirmed = true) }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { controller().replayStart(record.taskId, freshlyConfirmed = false) }
        }
        transport.startError = null
        controller().replayStart(record.taskId, freshlyConfirmed = true)

        assertEquals(2, transport.starts.size)
        assertEquals(setOf(record.taskId), transport.starts.map { it.taskId }.toSet())
        assertEquals(1, store.tasks().size)
    }

    @Test fun verify404KeepsTheIntentAndDoesNotCreateAnotherTask() = runBlocking {
        val record = prepared()
        transport.startError = IOException("timeout after send")
        runCatching { controller().start(record.taskId, freshlyConfirmed = true) }

        transport.getError = httpError(404)
        assertThrows(NoteArtifactTaskMissingException::class.java) {
            runBlocking { controller().verify(record.taskId) }
        }
        val missing = store.read(record.taskId)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, missing.localPhase)
        assertTrue(missing.lastProblem.orEmpty().contains("不会自动另建任务"))
        assertEquals(1, store.tasks().size)
        assertEquals(1, transport.starts.size)
    }

    @Test fun accountSwitchIsRejectedBeforeAnyTransportCall() = runBlocking {
        val record = prepared()
        val switched = NoteArtifactController(store, transport, BoundUploadSession("https://pim.example", otherOwner))
        assertThrows(NoteArtifactBindingChangedException::class.java) {
            runBlocking { switched.start(record.taskId, freshlyConfirmed = true) }
        }
        assertTrue(transport.starts.isEmpty())
        assertEquals(NoteArtifactIntentPhase.Prepared, store.read(record.taskId).localPhase)
    }

    @Test fun unknownStopReceiptStaysMarkedUntilServerViewConfirmsStop() = runBlocking {
        val record = prepared()
        controller().start(record.taskId, freshlyConfirmed = true)
        transport.stopError = IOException("timeout after stop")
        assertThrows(IOException::class.java) {
            runBlocking { controller().stop(record.taskId) }
        }
        assertEquals(NoteArtifactIntentPhase.VerificationPending, store.read(record.taskId).stopPhase)

        transport.getView = NoteArtifactRemoteTaskView(
            revision = "rev-2",
            phase = "Stopped",
            stopRequested = true,
            observedAtMillis = 9_000,
        )
        val verified = controller().verify(record.taskId)
        assertNull(verified.stopPhase)
        assertTrue(verified.remote?.stopRequested == true)
    }

    @Test fun retryRequiresDuplicateChargeConfirmationAndReplaysTheSameRetryId() = runBlocking {
        val record = prepared()
        transport.startView = view("rev-1", "NeedsAttention")
        controller().start(record.taskId, freshlyConfirmed = true)
        val steps = listOf("photo-1", "synthesis")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { controller().retry(record.taskId, steps, duplicateChargeConfirmed = false) }
        }
        assertTrue(transport.retries.isEmpty())

        transport.retryError = IOException("timeout after retry")
        assertThrows(IOException::class.java) {
            runBlocking { controller().retry(record.taskId, steps, duplicateChargeConfirmed = true) }
        }
        val pending = store.read(record.taskId).pendingRetry ?: error("retry intent missing")
        assertEquals(steps, pending.stepKeys)
        assertEquals("rev-1", pending.expectedServerRevision)
        assertEquals(NoteArtifactIntentPhase.VerificationPending, pending.phase)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { controller().replayRetry(record.taskId, freshlyConfirmed = false) }
        }
        transport.retryError = null
        transport.retryView = view("rev-2", "Running")
        val completed = controller().replayRetry(record.taskId, freshlyConfirmed = true)

        assertNull(completed.pendingRetry)
        assertEquals(listOf(pending.retryId, pending.retryId), transport.retries.map { it.second.retryId })
        assertEquals(listOf(steps, steps), transport.retries.map { it.second.stepKeys })
    }

    @Test fun unauthorizedStartMarksNeedsLoginAndNeverClaimsSuccess() = runBlocking {
        val record = prepared()
        transport.startError = httpError(401)
        assertThrows(HttpException::class.java) {
            runBlocking { controller().start(record.taskId, freshlyConfirmed = true) }
        }
        val failed = store.read(record.taskId)
        assertEquals(NoteArtifactIntentPhase.NeedsLogin, failed.localPhase)
        assertNull(failed.remote)
        assertTrue(failed.lastProblem.orEmpty().contains("登录"))
    }

    private fun view(revision: String, phase: String, stopRequested: Boolean = false) =
        NoteArtifactRemoteTaskView(
            revision = revision,
            phase = phase,
            stopRequested = stopRequested,
            observedAtMillis = 5_000,
        )

    private fun httpError(code: Int) =
        HttpException(Response.error<Any>(code, "".toResponseBody()))

    private class FakeTransport : NoteArtifactTransport {
        val starts = mutableListOf<NoteArtifactTaskRecord>()
        val gets = mutableListOf<String>()
        val stops = mutableListOf<String>()
        val retries = mutableListOf<Pair<NoteArtifactTaskRecord, NoteArtifactRetryIntent>>()

        var startView = NoteArtifactRemoteTaskView("rev-1", "Queued", observedAtMillis = 5_000)
        var startError: Throwable? = null
        var getView = NoteArtifactRemoteTaskView("rev-1", "Queued", observedAtMillis = 5_000)
        var getError: Throwable? = null
        var stopView = NoteArtifactRemoteTaskView(
            "rev-2", "Stopped", stopRequested = true, observedAtMillis = 6_000,
        )
        var stopError: Throwable? = null
        var retryView = NoteArtifactRemoteTaskView("rev-2", "Running", observedAtMillis = 7_000)
        var retryError: Throwable? = null

        override suspend fun destinations(): List<NoteArtifactDestination> = emptyList()

        override suspend fun start(record: NoteArtifactTaskRecord): NoteArtifactRemoteTaskView {
            starts += record
            startError?.let { throw it }
            return startView
        }

        override suspend fun get(taskId: String): NoteArtifactRemoteTaskView {
            gets += taskId
            getError?.let { throw it }
            return getView
        }

        override suspend fun detail(taskId: String): NoteArtifactTaskDetail =
            error("detail is not used by controller tests")

        override suspend fun artifacts(noteId: String): List<NoteArtifactArtifactRefProjection> =
            error("artifacts is not used by controller tests")

        override suspend fun versions(artifactId: String): List<NoteArtifactVersionProjection> =
            error("versions is not used by controller tests")

        override suspend fun stop(taskId: String): NoteArtifactRemoteTaskView {
            stops += taskId
            stopError?.let { throw it }
            return stopView
        }

        override suspend fun retry(
            record: NoteArtifactTaskRecord,
            retry: NoteArtifactRetryIntent,
        ): NoteArtifactRemoteTaskView {
            retries += record to retry
            retryError?.let { throw it }
            return retryView
        }

        override suspend fun saveManualRevision(
            artifactId: String,
            revisionId: String,
            expectedHeadRevision: String,
            expectedHeadVersionId: String?,
            contentMarkdown: String,
        ): NoteArtifactMutationProjection = error("saveManualRevision is not used by controller tests")

        override suspend fun selectVersion(
            artifactId: String,
            expectedHeadRevision: String,
            versionId: String,
        ): String? = error("selectVersion is not used by controller tests")
    }
}

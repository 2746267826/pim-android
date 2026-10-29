package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.contract.AiAsrDestinationConsent
import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.contract.NoteAsrAttemptDto
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrSourceDto
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.network.BoundUploadSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteAsrTaskStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val otherOwner = "22222222-2222-4222-8222-222222222222"
    private val binding = BoundUploadSession("https://pim.example", owner)
    private val source = NoteAsrSourceDto(
        createdAt = "2026-10-29T00:00:00Z",
        hasConflict = true,
        isCurrent = false,
        noteId = "33333333-3333-4333-8333-333333333333",
        recordings = 2,
        sampleRate = 16_000,
        title = "固定课堂 A",
        totalSamples = 192_000,
        versionId = "44444444-4444-4444-8444-444444444444",
    )
    private val destination = AiAsrDestinationConsent(
        confirmed = true,
        endpoint = "https://asr.example/v1/transcriptions",
        modelId = "55555555-5555-4555-8555-555555555555",
        modelRevision = "66666666-6666-4666-8666-666666666666",
        presetId = "mimo-v2",
        presetVersion = "2026-10-01",
    )

    private fun store() = NoteAsrTaskStore.at(temp.root)

    @Test fun intentPersistsTaskOwnerServerFixedVersionAndTargetWithoutTokensAndCannotBeRebound() {
        val record = store().createIntent(source, null, binding, "alice", destination, "zh", nowMillis = 1_000)
        val reloaded = store().read(record.taskId)
        assertEquals(record.taskId, reloaded.taskId)
        assertEquals(source.versionId, reloaded.versionId)
        assertEquals(binding, reloaded.binding)
        assertEquals(destination, reloaded.destination)
        val raw = File(temp.root, "note-asr-tasks/${record.taskId}.json").readText()
        assertFalse(raw.contains("accessToken"))
        assertFalse(raw.contains("refreshToken"))
        assertFalse(raw.contains("Bearer"))
        try {
            store().update(record.taskId) { it.copy(versionId = "77777777-7777-4777-8777-777777777777") }
            fail("TaskId must not be rebound to a new fixed version")
        } catch (_: IllegalArgumentException) { }
        assertEquals(source.versionId, store().read(record.taskId).versionId)
    }

    @Test fun selectedDestinationDisplayFactsArePinnedWithTheIntentButNeverContainSecrets() {
        val info = NoteAsrDestinationDto(
            backgroundProcessingAvailable = true,
            displayName = "合成 ASR",
            endpoint = destination.endpoint,
            languages = listOf("auto", "zh"),
            model = "mimo-asr",
            modelId = destination.modelId,
            modelRevision = destination.modelRevision,
            presetId = destination.presetId,
            presetVersion = destination.presetVersion,
        )
        val record = store().createIntent(
            source = source,
            material = null,
            binding = binding,
            ownerName = "alice",
            destination = info.asAsrConsent(),
            language = "auto",
            destinationInfo = info,
        )
        val loaded = store().read(record.taskId)
        assertEquals("合成 ASR", loaded.destinationDisplayName)
        assertEquals("mimo-asr", loaded.destinationModel)
        assertEquals(listOf("auto", "zh"), loaded.destinationLanguages)
        assertEquals(true, loaded.destinationBackgroundAvailable)
        val raw = File(temp.root, "note-asr-tasks/${record.taskId}.json").readText()
        assertFalse(raw.contains("apiKeySecret"))
        assertFalse(raw.contains("api_key"))
    }

    @Test fun segmentsRemainIncompleteUntilEveryIndexArrivesAndEmptyTextNeverClaimsWholeSuccess() {
        val store = store()
        val intent = store.createIntent(source, null, binding, "alice", destination, "auto")
        val task = task(
            intent.taskId,
            phase = "Succeeded",
            completedSegments = 2,
            completedSamples = 192_000,
            totalSegments = 2,
        )
        store.importRemote(task, binding, "alice", null)
        store.mergeSegments(intent.taskId, listOf(segment(0, "record-a", "Succeeded", "第一片")), expectedTotal = 2)
        assertFalse(store.read(intent.taskId).segmentsComplete)
        store.mergeSegments(intent.taskId, listOf(segment(1, "record-a", "Succeeded", "")), expectedTotal = 2)
        val loaded = store.read(intent.taskId)
        assertTrue(loaded.segmentsComplete)
        assertFalse(loaded.canClaimWholeComplete())
        assertTrue(loaded.statusView(target()).detail.contains("空文字"))
    }

    @Test fun revisionChangeInvalidatesPreviouslyCompleteSegmentPages() {
        val store = store()
        val intent = store.createIntent(source, null, binding, "alice", destination, "auto")
        val revision1 = "88888888-8888-4888-8888-888888888888"
        val revision2 = "99999999-9999-4999-8999-999999999999"
        store.importRemote(
            task(
                intent.taskId,
                phase = "Succeeded",
                completedSegments = 2,
                completedSamples = 192_000,
                totalSegments = 2,
                revision = revision1,
            ),
            binding,
            "alice",
            null,
        )
        store.mergeSegments(
            intent.taskId,
            listOf(
                segment(0, "record-a", "Succeeded", "第一修订片 0"),
                segment(1, "record-a", "Succeeded", "第一修订片 1"),
            ),
            expectedTotal = 2,
        )
        assertTrue(store.read(intent.taskId).canClaimWholeComplete())

        store.importRemote(
            task(
                intent.taskId,
                phase = "Succeeded",
                completedSegments = 2,
                completedSamples = 192_000,
                totalSegments = 2,
                revision = revision2,
            ),
            binding,
            "alice",
            null,
        )
        val after = store.read(intent.taskId)
        assertEquals(revision2, after.task?.revision)
        assertTrue(after.segments.isEmpty())
        assertFalse(after.segmentsComplete)
        assertFalse(after.canClaimWholeComplete())
    }

    @Test fun destinationConsentIdentityChangesForEndpointPresetAndVersionWithSameModelRevision() {
        val base = NoteAsrDestinationDto(
            backgroundProcessingAvailable = true,
            displayName = "合成 ASR",
            endpoint = destination.endpoint,
            languages = listOf("auto", "zh"),
            model = "mimo-asr",
            modelId = destination.modelId,
            modelRevision = destination.modelRevision,
            presetId = destination.presetId,
            presetVersion = destination.presetVersion,
        )
        val endpointChanged = base.copy(endpoint = "https://asr-alt.example/v1/transcriptions")
        val presetChanged = base.copy(presetId = "mimo-v3")
        val versionChanged = base.copy(presetVersion = "2026-10-02")
        assertNotEquals(base.consentIdentity(), endpointChanged.consentIdentity())
        assertNotEquals(base.consentIdentity(), presetChanged.consentIdentity())
        assertNotEquals(base.consentIdentity(), versionChanged.consentIdentity())
        assertEquals(base.modelId, endpointChanged.modelId)
        assertEquals(base.modelRevision, endpointChanged.modelRevision)
    }

    @Test fun userRecordingGroupsKeepInternalChunkIndexesAndSampleOffsetsVisible() {
        val store = store()
        val intent = store.createIntent(source, null, binding, "alice", destination, "auto")
        store.update(intent.taskId) {
            it.copy(sourceRecordings = listOf(NoteAsrRecordingSource(
                id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                createdAtMillis = 1,
                recordingSegments = 1,
                samples = 120 * 16_000L,
            )))
        }
        store.importRemote(task(intent.taskId, "Running", completedSegments = 2, completedSamples = 96_000), binding, "alice", null)
        store.mergeSegments(
            intent.taskId,
            listOf(
                segment(0, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Succeeded", "A0", timeline = 0),
                segment(1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Succeeded", "A1", timeline = 60 * 16_000L),
                segment(2, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "Failed", null, timeline = 120 * 16_000L),
            ),
            expectedTotal = 3,
        )
        val groups = store.read(intent.taskId).recordingGroups()
        assertEquals(2, groups.size)
        assertEquals("录音 1", groups[0].label)
        assertEquals(listOf(0, 1), groups[0].items.map { it.index })
        assertEquals(60 * 16_000L, groups[0].items[1].timelineOffsetSamples)
    }

    @Test fun retrySelectionExcludesSucceededQueuedAndRunningChunks() {
        val store = store()
        val intent = store.createIntent(source, null, binding, "alice", destination, "auto")
        store.importRemote(
            task(intent.taskId, "NeedsAttention", completedSegments = 1, completedSamples = 64_000, totalSegments = 5),
            binding, "alice", null,
        )
        val segments = listOf(
            segment(0, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Succeeded", "done"),
            segment(1, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Running", null),
            segment(2, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Queued", null),
            segment(3, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Failed", null),
            segment(4, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Unknown", null),
        )
        store.mergeSegments(intent.taskId, segments, expectedTotal = 5)
        val record = store.read(intent.taskId)
        assertFalse(record.isRetryEligible(segments[0]))
        assertFalse(record.isRetryEligible(segments[1]))
        assertFalse(record.isRetryEligible(segments[2]))
        assertTrue(record.isRetryEligible(segments[3]))
        assertTrue(record.isRetryEligible(segments[4]))
    }

    @Test fun retainedTaskFromAnotherOwnerServerIsVisibleWithoutUsingCurrentAccess() {
        val store = store()
        val record = store.createIntent(source.copy(title = "Alice private material"), null, binding, "alice", destination, "auto")
        store.importRemote(task(record.taskId, "Succeeded", completedSegments = 1), binding, "alice", null)
        store.mergeSegments(
            record.taskId,
            listOf(segment(0, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Succeeded", "Alice private transcript")),
            expectedTotal = 3,
        )
        val wrong = NoteUploadTarget(BoundUploadSession("https://other.example", otherOwner), "bob")
        val retained = store.read(record.taskId)
        val status = retained.statusView(wrong)
        assertEquals(NoteAsrStatus.BindingUnavailable, status.status)
        val presentation = retained.listPresentation(wrong)
        assertEquals("原账号转写任务", presentation.title)
        assertFalse(presentation.showProgress)
        assertNull(retained.detailSourceTitle(wrong))
        val inaccessibleText = listOf(status.label, status.detail, presentation.title, presentation.subtitle).joinToString(" ")
        assertFalse(inaccessibleText.contains("Alice private material"))
        assertFalse(inaccessibleText.contains("Alice private transcript"))
        assertFalse(inaccessibleText.contains("https://pim.example"))
        assertFalse(inaccessibleText.contains("alice"))
        assertFalse(inaccessibleText.contains(destination.endpoint))
        assertTrue(status.detail.contains("本机"))
    }

    @Test fun damagedCheckpointIsNotSilentlyOverwrittenByRemoteImport() {
        val store = store()
        val intent = store.createIntent(source, null, binding, "alice", destination, "auto")
        val file = File(temp.root, "note-asr-tasks/${intent.taskId}.json")
        file.writeText("broken")
        assertTrue(store.load().loadFailed)
        try {
            store.importRemote(task(intent.taskId, "Queued"), binding, "alice", null)
            fail("A damaged local task must not be replaced")
        } catch (_: Exception) { }
        assertEquals("broken", file.readText())
    }

    @Test fun aDifferentPendingTargetCreatesADifferentTaskInsteadOfRebindingConsent() {
        val store = store()
        val first = store.createIntent(source, null, binding, "alice", destination, "auto")
        val second = store.createIntent(
            source, null, binding, "alice", destination.copy(presetVersion = "2026-10-02"), "auto",
        )
        assertNotEquals(first.taskId, second.taskId)
        assertEquals("2026-10-01", store.read(first.taskId).destination.presetVersion)
        assertEquals("2026-10-02", store.read(second.taskId).destination.presetVersion)
    }

    private fun target() = NoteUploadTarget(binding, "alice")

    private fun task(
        id: String,
        phase: String,
        completedSegments: Int = 0,
        completedSamples: Long = 0,
        totalSegments: Int = 3,
        revision: String = "88888888-8888-4888-8888-888888888888",
    ) = NoteAsrTaskDto(
        backgroundProcessingAvailable = true,
        completedSamples = completedSamples,
        completedSegments = completedSegments,
        createdAt = "2026-10-29T00:00:00Z",
        destination = destination,
        failedSegments = if (phase == "NeedsAttention") 1 else 0,
        inputSha256 = "a".repeat(64),
        language = "auto",
        manifestSha256 = "b".repeat(64),
        noteId = source.noteId,
        phase = phase,
        revision = revision,
        sampleRate = 16_000,
        stopRequested = phase == "Stopped",
        taskId = id,
        totalSamples = 192_000,
        totalSegments = totalSegments,
        updatedAt = "2026-10-29T00:01:00Z",
        versionId = source.versionId,
    )

    private fun segment(
        index: Int,
        recordingId: String,
        status: String,
        transcript: String?,
        timeline: Long = index * 60 * 16_000L,
    ) = NoteAsrSegmentDto(
        attempts = listOf(NoteAsrAttemptDto(requestId = "99999999-9999-4999-8999-999999999999", status = status)),
        chunkIndex = index,
        index = index,
        recordingId = recordingId,
        recordingOffsetSamples = index * 60 * 16_000L,
        recordingSegment = 1,
        samples = 60 * 16_000L,
        sourceFileIndex = 0,
        sourceOffsetSamples = index * 60 * 16_000L,
        sourcePath = "recordings/$recordingId/s0001-c${index.toString().padStart(6, '0')}.wav",
        sourceSha256 = "c".repeat(64),
        status = status,
        timelineOffsetSamples = timeline,
        transcript = transcript,
    )
}

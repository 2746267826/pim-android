package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.contract.AiAsrDestinationConsent
import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.contract.NoteAsrDestinationsDto
import com.pim.core.contract.NoteAsrDestinationsDtoApiResponse
import com.pim.core.contract.NoteAsrAttemptDto
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrSegmentsDto
import com.pim.core.contract.NoteAsrSegmentsDtoApiResponse
import com.pim.core.contract.NoteAsrSourceDto
import com.pim.core.contract.NoteAsrSourcesDto
import com.pim.core.contract.NoteAsrSourcesDtoApiResponse
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.contract.NoteAsrTaskDtoApiResponse
import com.pim.core.contract.NoteAsrTaskDtoIReadOnlyListApiResponse
import com.pim.core.contract.RetryNoteAsrRequest
import com.pim.core.contract.StartNoteAsrRequest
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
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
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteAsrClientTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val binding = BoundUploadSession("https://pim.example", owner)
    private val source = NoteAsrSourceDto(
        createdAt = "2026-10-29T00:00:00Z",
        hasConflict = false,
        isCurrent = true,
        noteId = "33333333-3333-4333-8333-333333333333",
        recordings = 1,
        sampleRate = 16_000,
        title = "固定课堂 A",
        totalSamples = 180 * 16_000L,
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
    private fun materials() = NoteMaterialStore.at(temp.root)

    private fun intent(): NoteAsrTaskRecord =
        store().createIntent(source, null, binding, "alice", destination, "auto")

    @Test fun lostCreateReceiptStaysVerificationPendingAndExplicitReplayReusesTheSameTaskId() = runBlocking {
        val record = intent()
        val server = Server(record.taskId)
        server.loseStart = true
        val client = client(server)
        try { client.start(record); fail("Expected unknown receipt") } catch (_: IOException) { }
        val pending = store().read(record.taskId)
        assertEquals(NoteAsrIntentPhase.VerificationPending, pending.localPhase)
        assertEquals(1, server.startRequests.size)
        try { client.replayStart(record.taskId, freshlyConfirmed = false); fail("Fresh page confirmation is required") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, server.startRequests.size)
        server.loseStart = false
        client.replayStart(record.taskId, freshlyConfirmed = true)
        assertEquals(2, server.startRequests.size)
        assertEquals(record.taskId, server.startRequests[0].taskId)
        assertEquals(record.taskId, server.startRequests[1].taskId)
        assertEquals(NoteAsrIntentPhase.Accepted, store().read(record.taskId).localPhase)
    }

    @Test fun authorizationFailureNeverBecomesUnknownOrSuccessful() = runBlocking {
        val record = intent()
        val server = Server(record.taskId).apply { startStatus = 403 }
        try { client(server).start(record); fail("Expected authorization failure") } catch (_: HttpException) { }
        val after = store().read(record.taskId)
        assertEquals(NoteAsrIntentPhase.NeedsLogin, after.localPhase)
        assertNull(after.task)
        assertFalse(after.canClaimWholeComplete())
    }

    @Test fun missingTaskOnRecheckSupersedesAStaleCachedSuccessView() = runBlocking {
        val record = intent()
        val server = Server(record.taskId)
        store().importRemote(
            server.task("Succeeded", totalSegments = 1, completedSegments = 1),
            binding, "alice", null,
        )
        server.getStatus = 404
        try { client(server).get(record.taskId); fail("Expected missing task") } catch (_: NoteAsrTaskMissingException) { }
        val after = store().read(record.taskId)
        assertEquals(NoteAsrIntentPhase.Unavailable, after.localPhase)
        assertEquals(NoteAsrStatus.Unknown, after.statusView(NoteUploadTarget(binding, "alice")).status)
        assertFalse(after.canClaimWholeComplete())
    }

    @Test fun segmentPaginationLoadsEveryInternalChunk() = runBlocking {
        val record = intent()
        val server = Server(record.taskId)
        store().importRemote(server.task("Running", totalSegments = 5, completedSegments = 2), binding, "alice", null)
        val loaded = client(server).segments(record.taskId, pageSize = 2)
        assertEquals(listOf(0, 1, 2, 3, 4), loaded.segments.map { it.index })
        assertTrue(loaded.segmentsComplete)
        assertEquals(3, server.segmentCalls)
        assertFalse(loaded.canClaimWholeComplete())
    }

    @Test fun retryPersistsOneRetryIdAndOnlySelectsFailedOrUnknownChunks() = runBlocking {
        val record = intent()
        val server = Server(record.taskId)
        store().importRemote(
            server.task("NeedsAttention", totalSegments = 3, completedSegments = 1),
            binding, "alice", null,
        )
        client(server).segments(record.taskId)
        try { client(server).retry(record.taskId, listOf(0), true); fail("Succeeded chunk must not be retried") }
        catch (_: IllegalArgumentException) { }
        server.loseRetry = true
        try { client(server).retry(record.taskId, listOf(1), true); fail("Expected unknown retry receipt") }
        catch (_: IOException) { }
        val pending = store().read(record.taskId)
        assertEquals(NoteAsrIntentPhase.VerificationPending, pending.pendingRetry?.phase)
        val retryId = pending.pendingRetry!!.retryId
        assertEquals(1, server.retryRequests.size)
        server.loseRetry = false
        client(server).replayRetry(record.taskId, freshlyConfirmed = true)
        assertEquals(2, server.retryRequests.size)
        assertEquals(retryId, server.retryRequests[0].retryId)
        assertEquals(retryId, server.retryRequests[1].retryId)
        assertNull(store().read(record.taskId).pendingRetry)
    }

    @Test fun rejectedRetryIntentDoesNotReuseItsRetryIdForANewAttempt() = runBlocking {
        val record = intent()
        val server = Server(record.taskId)
        store().importRemote(
            server.task("NeedsAttention", totalSegments = 3, completedSegments = 1),
            binding, "alice", null,
        )
        client(server).segments(record.taskId)
        server.retryStatus = 409
        try { client(server).retry(record.taskId, listOf(1), true); fail("Expected rejected retry") }
        catch (_: HttpException) { }
        val rejected = store().read(record.taskId)
        assertEquals(NoteAsrIntentPhase.Rejected, rejected.pendingRetry?.phase)
        val firstRetryId = rejected.pendingRetry!!.retryId
        server.retryStatus = null
        client(server).retry(record.taskId, listOf(1), true)
        assertEquals(2, server.retryRequests.size)
        assertNotEquals(firstRetryId, server.retryRequests[1].retryId)
    }

    @Test fun stopKeepsSuccessfulSegmentText() = runBlocking {
        val record = intent()
        val server = Server(record.taskId)
        store().importRemote(
            server.task("Running", totalSegments = 2, completedSegments = 1),
            binding, "alice", null,
        )
        val withSegments = client(server).segments(record.taskId)
        assertEquals("成功片文字", withSegments.segments[0].transcript)
        val stopped = client(server).stop(record.taskId)
        assertEquals("Stopped", stopped.task?.phase)
        assertEquals("成功片文字", stopped.segments[0].transcript)
        assertFalse(stopped.canClaimWholeComplete())
    }

    @Test fun sourcePaginationDoesNotStopAtTheFirstServerPage() = runBlocking {
        val server = Server(newNoteId()).apply { sourceCount = 55 }
        val (sources, total) = client(server).sources()
        assertEquals(55, total)
        assertEquals(55, sources.size)
        assertEquals(2, server.sourceCalls)
        assertEquals(55, sources.map { it.versionId }.distinct().size)
    }

    @Test fun destinationDirectoryUsesOnlyTheGeneratedOwnerScopedDto() = runBlocking {
        val server = Server(newNoteId()).apply {
            destinations = listOf(
                NoteAsrDestinationDto(
                    backgroundProcessingAvailable = true,
                    displayName = "合成 ASR",
                    endpoint = "https://asr.example/v1/transcriptions",
                    languages = listOf("auto", "zh"),
                    model = "mimo-asr",
                    modelId = "55555555-5555-4555-8555-555555555555",
                    modelRevision = "66666666-6666-4666-8666-666666666666",
                    presetId = "mimo-v2",
                    presetVersion = "2026-10-01",
                ),
            )
        }
        val destinations = client(server).destinations()
        assertEquals(1, destinations.size)
        assertEquals("合成 ASR", destinations.single().displayName)
        assertEquals(listOf("auto", "zh"), destinations.single().languages)
    }

    private fun client(server: Server) = NoteAsrClient(
        store(), materials(), server.api, binding, "alice",
    )

    private inner class Server(private val taskId: String) {
        var loseStart = false
        var loseRetry = false
        var startStatus: Int? = null
        var getStatus: Int? = null
        var retryStatus: Int? = null
        var sourceCount = 0
        var sourceCalls = 0
        var segmentCalls = 0
        var destinations = listOf(
            NoteAsrDestinationDto(
                backgroundProcessingAvailable = true,
                displayName = "合成 ASR",
                endpoint = "https://asr.example/v1/transcriptions",
                languages = listOf("auto"),
                model = "mimo-asr",
                modelId = "55555555-5555-4555-8555-555555555555",
                modelRevision = "66666666-6666-4666-8666-666666666666",
                presetId = "mimo-v2",
                presetVersion = "2026-10-01",
            ),
        )
        val startRequests = mutableListOf<StartNoteAsrRequest>()
        val retryRequests = mutableListOf<RetryNoteAsrRequest>()
        private var revision = "88888888-8888-4888-8888-888888888888"
        private var phase = "Queued"
        private var totalSegments = 3
        private var completedSegments = 0

        fun task(phase: String, totalSegments: Int, completedSegments: Int): NoteAsrTaskDto {
            this.phase = phase
            this.totalSegments = totalSegments
            this.completedSegments = completedSegments
            return dto()
        }

        private fun dto() = NoteAsrTaskDto(
            backgroundProcessingAvailable = true,
            completedSamples = completedSegments * 60 * 16_000L,
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
            taskId = taskId,
            totalSamples = totalSegments * 60 * 16_000L,
            totalSegments = totalSegments,
            updatedAt = "2026-10-29T00:01:00Z",
            versionId = source.versionId,
        )

        val api: ApiService = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "startNoteAsrTask" -> {
                    val request = args!![0] as StartNoteAsrRequest
                    startRequests += request
                    startStatus?.let { status ->
                        failCall(args, HttpException(Response.error<Any>(status, "".toResponseBody())))
                    } ?: run {
                        if (loseStart) {
                            loseStart = false
                            failCall(args, IOException("synthetic lost create receipt"))
                        } else {
                            phase = "Queued"
                            completedSegments = 0
                            NoteAsrTaskDtoApiResponse(0, dto(), "ok", "2026-10-29T00:00:00Z")
                        }
                    }
                }
                "getNoteAsrTask" -> getStatus?.let { status ->
                    failCall(args!!, HttpException(Response.error<Any>(status, "".toResponseBody())))
                } ?: NoteAsrTaskDtoApiResponse(0, dto(), "ok", "2026-10-29T00:00:00Z")
                "getNoteAsrTasks" -> NoteAsrTaskDtoIReadOnlyListApiResponse(
                    0, listOf(dto()), "ok", "2026-10-29T00:00:00Z",
                )
                "getNoteAsrDestinations" -> NoteAsrDestinationsDtoApiResponse(
                    0, NoteAsrDestinationsDto(items = destinations, total = destinations.size),
                    "ok", "2026-10-29T00:00:00Z",
                )
                "getNoteAsrSources" -> {
                    sourceCalls++
                    val offset = args!![0] as Int
                    val limit = args[1] as Int
                    val items = (offset until minOf(offset + limit, sourceCount)).map { index ->
                        source.copy(
                            versionId = java.util.UUID.nameUUIDFromBytes("source-$index".toByteArray()).toString(),
                            title = "source $index",
                        )
                    }
                    NoteAsrSourcesDtoApiResponse(
                        0, NoteAsrSourcesDto(items = items, offset = offset, total = sourceCount),
                        "ok", "2026-10-29T00:00:00Z",
                    )
                }
                "getNoteAsrSegments" -> {
                    segmentCalls++
                    val offset = args!![1] as Int
                    val limit = args[2] as Int
                    val items = (offset until minOf(offset + limit, totalSegments)).map(::segment)
                    NoteAsrSegmentsDtoApiResponse(
                        0, NoteAsrSegmentsDto(items = items, offset = offset, total = totalSegments),
                        "ok", "2026-10-29T00:00:00Z",
                    )
                }
                "stopNoteAsrTask" -> {
                    phase = "Stopped"
                    NoteAsrTaskDtoApiResponse(0, dto(), "ok", "2026-10-29T00:00:00Z")
                }
                "retryNoteAsrTask" -> {
                    val request = args!![1] as RetryNoteAsrRequest
                    retryRequests += request
                    retryStatus?.let { status ->
                        failCall(args, HttpException(Response.error<Any>(status, "".toResponseBody())))
                    } ?: run {
                        if (loseRetry) {
                            loseRetry = false
                            failCall(args, IOException("synthetic lost retry receipt"))
                        } else {
                            revision = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                            phase = "Queued"
                            NoteAsrTaskDtoApiResponse(0, dto(), "ok", "2026-10-29T00:00:00Z")
                        }
                    }
                }
                else -> error("Unexpected operation ${method.name}")
            }
        } as ApiService

        private fun segment(index: Int): NoteAsrSegmentDto {
            val status = when {
                index == 0 -> "Succeeded"
                index == 1 -> "Failed"
                else -> "Running"
            }
            return NoteAsrSegmentDto(
                attempts = listOf(NoteAsrAttemptDto(
                    errorMessage = if (status == "Failed") "provider failed" else null,
                    requestId = java.util.UUID.nameUUIDFromBytes("attempt-$index".toByteArray()).toString(),
                    status = status,
                )),
                chunkIndex = index,
                index = index,
                recordingId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                recordingOffsetSamples = index * 60 * 16_000L,
                recordingSegment = 1,
                samples = 60 * 16_000L,
                sourceFileIndex = 0,
                sourceOffsetSamples = index * 60 * 16_000L,
                sourcePath = "recordings/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/s0001-c${index.toString().padStart(6, '0')}.wav",
                sourceSha256 = "c".repeat(64),
                status = status,
                timelineOffsetSamples = index * 60 * 16_000L,
                transcript = if (status == "Succeeded") "成功片文字" else null,
            )
        }
    }

    private fun failCall(args: Array<out Any?>, error: Throwable): Any {
        @Suppress("UNCHECKED_CAST")
        (args.last() as Continuation<Any?>).resumeWithException(error)
        return COROUTINE_SUSPENDED
    }
}

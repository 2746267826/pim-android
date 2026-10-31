package com.pim.app.mobile.sync

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class MobileSyncOutcomeMappingTest {

    // --- mapOutcomeToWorkerResult ---

    @Test
    fun `SUCCESS maps to Result success`() {
        val result = mapOutcomeToWorkerResult(MobileSyncOutcome.SUCCESS)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `RETRY maps to Result retry`() {
        val result = mapOutcomeToWorkerResult(MobileSyncOutcome.RETRY)
        assertTrue(result is Result.Retry)
    }

    @Test
    fun `BLOCKED maps to Result failure`() {
        val result = mapOutcomeToWorkerResult(MobileSyncOutcome.BLOCKED)
        assertTrue(result is Result.Failure)
    }

    @Test
    fun `DEFERRED maps to success so WorkManager does not busy-retry`() {
        val result = mapOutcomeToWorkerResult(MobileSyncOutcome.DEFERRED)
        assertTrue(result is Result.Success)
    }

    // --- classifyException outcomes ---

    @Test(expected = CancellationException::class)
    fun `CancellationException is rethrown`() {
        MobileSyncErrorClassifier.classify(CancellationException("cancel"))
    }

    @Test
    fun `SocketTimeoutException is RETRY`() {
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(SocketTimeoutException()))
    }

    @Test
    fun `ConnectException is RETRY`() {
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(ConnectException()))
    }

    @Test
    fun `UnknownHostException is RETRY`() {
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(UnknownHostException()))
    }

    @Test
    fun `SocketException is RETRY`() {
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(SocketException()))
    }

    @Test
    fun `IOException is RETRY`() {
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(IOException("generic IO")))
    }

    // --- HttpException classification (via subclass) ---

    @Test
    fun `HTTP 408 is RETRY`() {
        val httpEx = FakeHttpException(408)
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 409 terminal batch conflict is BLOCKED pending explicit user recovery`() {
        val httpEx = FakeHttpException(409)
        assertEquals(MobileSyncOutcome.BLOCKED, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `40903 is the only conflict code eligible for manual generation`() {
        val retryExhausted = FakeHttpException(
            409,
            """{"code":40903,"message":"retry exhausted","data":null}"""
        )
        val payloadConflict = FakeHttpException(
            409,
            """{"code":40902,"message":"payload conflict","data":null}"""
        )
        val leaseLost = FakeHttpException(
            409,
            """{"code":40904,"message":"lease lost","data":null}"""
        )
        val unknown = FakeHttpException(
            409,
            """{"code":40901,"message":"generic conflict","data":null}"""
        )

        assertEquals(
            UsageBatchBlockReason.RETRY_EXHAUSTED,
            classifyUsageBatchConflictCode(usageBatchHttpErrorCode(retryExhausted))
        )
        assertEquals(
            UsageBatchBlockReason.PAYLOAD_CONFLICT,
            classifyUsageBatchConflictCode(usageBatchHttpErrorCode(payloadConflict))
        )
        assertEquals(
            UsageBatchBlockReason.LEASE_LOST,
            classifyUsageBatchConflictCode(usageBatchHttpErrorCode(leaseLost))
        )
        assertEquals(
            UsageBatchBlockReason.UNKNOWN_CONFLICT,
            classifyUsageBatchConflictCode(usageBatchHttpErrorCode(unknown))
        )
        assertEquals(
            UsageBatchBlockReason.UNKNOWN_CONFLICT,
            classifyUsageBatchConflictCode(null)
        )
    }

    @Test
    fun `HTTP 429 is RETRY`() {
        val httpEx = FakeHttpException(429)
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 500 is RETRY`() {
        val httpEx = FakeHttpException(500)
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 503 is RETRY`() {
        val httpEx = FakeHttpException(503)
        assertEquals(MobileSyncOutcome.RETRY, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 401 is BLOCKED`() {
        val httpEx = FakeHttpException(401)
        assertEquals(MobileSyncOutcome.BLOCKED, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 403 is BLOCKED`() {
        val httpEx = FakeHttpException(403)
        assertEquals(MobileSyncOutcome.BLOCKED, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 400 is BLOCKED`() {
        val httpEx = FakeHttpException(400)
        assertEquals(MobileSyncOutcome.BLOCKED, MobileSyncErrorClassifier.classify(httpEx))
    }

    @Test
    fun `HTTP 404 is BLOCKED`() {
        val httpEx = FakeHttpException(404)
        assertEquals(MobileSyncOutcome.BLOCKED, MobileSyncErrorClassifier.classify(httpEx))
    }

    // --- MobileSyncState.merge outcome priority ---

    @Test
    fun `merge RETRY wins over BLOCKED`() {
        val a = MobileSyncState(phase = "a", progressText = "", outcome = MobileSyncOutcome.RETRY)
        val b = MobileSyncState(phase = "b", progressText = "", outcome = MobileSyncOutcome.BLOCKED)
        assertEquals(MobileSyncOutcome.RETRY, a.merge(b).outcome)
    }

    @Test
    fun `merge RETRY wins over SUCCESS`() {
        val a = MobileSyncState(phase = "a", progressText = "", outcome = MobileSyncOutcome.RETRY)
        val b = MobileSyncState(phase = "b", progressText = "", outcome = MobileSyncOutcome.SUCCESS)
        assertEquals(MobileSyncOutcome.RETRY, a.merge(b).outcome)
    }

    @Test
    fun `merge BLOCKED wins over SUCCESS`() {
        val a = MobileSyncState(phase = "a", progressText = "", outcome = MobileSyncOutcome.BLOCKED)
        val b = MobileSyncState(phase = "b", progressText = "", outcome = MobileSyncOutcome.SUCCESS)
        assertEquals(MobileSyncOutcome.BLOCKED, a.merge(b).outcome)
    }

    @Test
    fun `merge SUCCESS plus SUCCESS stays SUCCESS`() {
        val a = MobileSyncState(phase = "a", progressText = "", outcome = MobileSyncOutcome.SUCCESS)
        val b = MobileSyncState(phase = "b", progressText = "", outcome = MobileSyncOutcome.SUCCESS)
        assertEquals(MobileSyncOutcome.SUCCESS, a.merge(b).outcome)
    }

    @Test
    fun `merge BLOCKED persists through SUCCESS`() {
        val a = MobileSyncState(phase = "a", progressText = "", outcome = MobileSyncOutcome.BLOCKED)
        val b = MobileSyncState(phase = "b", progressText = "", outcome = MobileSyncOutcome.SUCCESS)
        assertEquals(MobileSyncOutcome.BLOCKED, a.merge(b).outcome)
    }

    @Test
    fun `merge RETRY not overwritten by SUCCESS`() {
        val a = MobileSyncState(phase = "a", progressText = "", outcome = MobileSyncOutcome.RETRY)
        val b = MobileSyncState(phase = "b", progressText = "", outcome = MobileSyncOutcome.SUCCESS)
        assertEquals(MobileSyncOutcome.RETRY, a.merge(b).outcome)
    }

    // --- MobileSyncState.merge count accumulation ---

    @Test
    fun `merge accumulates accepted counts`() {
        val a = MobileSyncState(phase = "a", progressText = "", acceptedCount = 3)
        val b = MobileSyncState(phase = "b", progressText = "", acceptedCount = 5)
        assertEquals(8, a.merge(b).acceptedCount)
    }

    @Test
    fun `merge accumulates skipped counts`() {
        val a = MobileSyncState(phase = "a", progressText = "", skippedCount = 1)
        val b = MobileSyncState(phase = "b", progressText = "", skippedCount = 2)
        assertEquals(3, a.merge(b).skippedCount)
    }

    @Test
    fun `merge accumulates rejected counts`() {
        val a = MobileSyncState(phase = "a", progressText = "", rejectedCount = 1)
        val b = MobileSyncState(phase = "b", progressText = "", rejectedCount = 3)
        assertEquals(4, a.merge(b).rejectedCount)
    }

    @Test
    fun `merge accumulates failed counts`() {
        val a = MobileSyncState(phase = "a", progressText = "", failedCount = 0)
        val b = MobileSyncState(phase = "b", progressText = "", failedCount = 2)
        assertEquals(2, a.merge(b).failedCount)
    }

    @Test
    fun `merge preserves lastBatchId and lastBatchStatus from other`() {
        val a = MobileSyncState(phase = "a", progressText = "", lastBatchId = "old", lastBatchStatus = "old-status")
        val b = MobileSyncState(phase = "b", progressText = "", lastBatchId = "new", lastBatchStatus = "new-status")
        val merged = a.merge(b)
        assertEquals("new", merged.lastBatchId)
        assertEquals("new-status", merged.lastBatchStatus)
    }

    // --- LocationUploadStatusUpdates retryableFirstError ---

    @Test
    fun `retryableFirstError returns first retryable perItemError`() {
        val updates = LocationUploadStatusUpdates(
            syncedIds = listOf(1L),
            failedIds = listOf(2L, 3L, 4L),
            failedReason = "permanent reason",
            shouldRetry = true,
            perItemErrors = mapOf(2L to "network error", 3L to "timeout", 4L to "permanent"),
            retryableFailedIds = listOf(2L, 3L)
        )
        assertEquals("network error", updates.retryableFirstError())
    }

    @Test
    fun `retryableFirstError returns null when no retryable`() {
        val updates = LocationUploadStatusUpdates(
            syncedIds = emptyList(),
            failedIds = listOf(1L),
            failedReason = "permanent reason",
            shouldRetry = false,
            perItemErrors = mapOf(1L to "permanent"),
            retryableFailedIds = emptyList()
        )
        assertEquals(null, updates.retryableFirstError())
    }

    @Test
    fun `toState with failedCount gt 0 returns RETRY`() {
        val resp = com.pim.core.contract.MobileUsageIngestResult(
            batchId = "batch1",
            acceptedCount = 1,
            skippedCount = 0,
            rejectedCount = 0,
            failedCount = 2,
            itemResults = emptyList()
        )
        val state = resp.toState("bId", "start", "end", 5, 3, 0)
        assertEquals(MobileSyncOutcome.RETRY, state.outcome)
    }

    @Test
    fun `toState with only rejectedCount gt 0 returns SUCCESS`() {
        val resp = com.pim.core.contract.MobileUsageIngestResult(
            batchId = "batch1",
            acceptedCount = 1,
            skippedCount = 0,
            rejectedCount = 1,
            failedCount = 0,
            itemResults = emptyList()
        )
        val state = resp.toState("bId", "start", "end", 5, 3, 0)
        assertEquals(MobileSyncOutcome.SUCCESS, state.outcome)
    }

    @Test
    fun `toState all zero counts returns SUCCESS`() {
        val resp = com.pim.core.contract.MobileUsageIngestResult(
            batchId = "batch1",
            acceptedCount = 0,
            skippedCount = 0,
            rejectedCount = 0,
            failedCount = 0,
            itemResults = emptyList()
        )
        val state = resp.toState("bId", "start", "end", 0, 0, 0)
        assertEquals(MobileSyncOutcome.SUCCESS, state.outcome)
    }

    @Test
    fun `toState with skipped returns SUCCESS when no failed`() {
        val resp = com.pim.core.contract.MobileUsageIngestResult(
            batchId = "batch1",
            acceptedCount = 3,
            skippedCount = 2,
            rejectedCount = 0,
            failedCount = 0,
            itemResults = emptyList()
        )
        val state = resp.toState("bId", "start", "end", 5, 3, 0)
        assertEquals(MobileSyncOutcome.SUCCESS, state.outcome)
    }

    @Test
    fun `toState empty ambiguous ack for sent items is RETRY not success`() {
        val sent = MobileAcknowledgementItem("usage-event", "1")
        val response = com.pim.core.contract.MobileUsageIngestResult(
            batchId = "batch-empty-ack",
            acceptedCount = 0,
            skippedCount = 0,
            rejectedCount = 0,
            failedCount = 0,
            itemResults = emptyList()
        )
        val plan = MobileTypedAcknowledgementPlan(
            retryItems = setOf(sent),
            failureCode = "server-ack-ambiguous",
            itemErrors = mapOf(sent to "server-ack-ambiguous")
        )

        val state = response.toState(
            batchId = "batch-empty-ack",
            windowStartUtc = "start",
            windowEndUtc = "end",
            eventCount = 1,
            summaryCount = 0,
            appMetadataCount = 0,
            acknowledgement = plan
        )

        assertEquals(MobileSyncOutcome.RETRY, state.outcome)
        assertEquals(1, state.failedCount)
        assertEquals("server-ack-ambiguous", state.lastError)
        assertEquals("completed-with-errors", state.lastBatchStatus)
    }

    @Test
    fun `toState partial ack keeps failed count and does not claim success`() {
        val accepted = MobileAcknowledgementItem("usage-event", "1")
        val missing = MobileAcknowledgementItem("usage-event", "2")
        val response = com.pim.core.contract.MobileUsageIngestResult(
            batchId = "batch-partial-ack",
            acceptedCount = 1,
            skippedCount = 0,
            rejectedCount = 0,
            failedCount = 0,
            itemResults = listOf(
                com.pim.core.contract.MobileIngestItemResult(
                    clientItemKey = "1",
                    entityType = "usage-event",
                    outcome = "accepted",
                    code = "accepted",
                    message = "OK"
                )
            )
        )
        val plan = MobileTypedAcknowledgementPlan(
            confirmedItems = setOf(accepted),
            retryItems = setOf(missing),
            failureCode = "server-ack-ambiguous",
            itemErrors = mapOf(missing to "server-ack-ambiguous")
        )

        val state = response.toState(
            batchId = "batch-partial-ack",
            windowStartUtc = "start",
            windowEndUtc = "end",
            eventCount = 2,
            summaryCount = 0,
            appMetadataCount = 0,
            acknowledgement = plan
        )

        assertEquals(MobileSyncOutcome.RETRY, state.outcome)
        assertEquals(1, state.acceptedCount)
        assertEquals(1, state.failedCount)
    }
}

internal class FakeHttpException(
    code: Int,
    body: String = "{}"
) : HttpException(createFakeResponse(code, body)) {
    companion object {
        fun createFakeResponse(code: Int, body: String): retrofit2.Response<*> {
            return retrofit2.Response.error<Any>(code, okhttp3.ResponseBody.create(null, body))
        }
    }
}

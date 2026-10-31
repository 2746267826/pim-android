package com.pim.app.mobile.sync

import com.pim.core.contract.MobileUsageIngestResult
import com.pim.core.network.MobileUsageResponseMetadata
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileUsageRetryPolicyTest {

    private fun metadata(status: Int, retryAfter: String?) =
        MobileUsageResponseMetadata(status, retryAfter)

    private fun ingest(
        processingState: String? = null,
        retryAfterSeconds: Int? = null,
        leaseUntilUtc: String? = null,
        retryAtUtc: String? = null
    ) = MobileUsageIngestResult(
        acceptedCount = 0,
        batchId = "batch",
        failedCount = 0,
        itemResults = emptyList(),
        leaseUntilUtc = leaseUntilUtc,
        processingState = processingState,
        rejectedCount = 0,
        retryAfterSeconds = retryAfterSeconds,
        retryAtUtc = retryAtUtc,
        skippedCount = 0
    )

    @Test
    fun `non-202 responses never impose a not-before deadline`() {
        assertNull(resolveMobileUsageNotBefore(metadata(200, "15"), ingest(retryAfterSeconds = 90), 1_000L))
        assertNull(resolveMobileUsageNotBefore(metadata(500, "15"), ingest(retryAfterSeconds = 90), 1_000L))
        assertNull(resolveMobileUsageNotBefore(null, ingest(retryAfterSeconds = 90), 1_000L))
    }

    @Test
    fun `missing invalid and non-positive retry-after fall back to the safe default`() {
        val base = 1_000_000L
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(metadata(202, null), nowUtcMillis = base))
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(metadata(202, "not-a-number"), nowUtcMillis = base))
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(metadata(202, ""), nowUtcMillis = base))
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(metadata(202, "   "), nowUtcMillis = base))
        assertEquals(base + 1_000L, resolveMobileUsageNotBefore(metadata(202, "0"), nowUtcMillis = base))
        assertEquals(base + 1_000L, resolveMobileUsageNotBefore(metadata(202, "-5"), nowUtcMillis = base))
    }

    @Test
    fun `huge retry-after values are capped at one day without overflow`() {
        val base = 1_000_000L
        assertEquals(
            base + MAX_USAGE_NOT_BEFORE_DELAY_MS,
            resolveMobileUsageNotBefore(metadata(202, Long.MAX_VALUE.toString()), nowUtcMillis = base)
        )
        assertEquals(
            base + MAX_USAGE_NOT_BEFORE_DELAY_MS,
            resolveMobileUsageNotBefore(metadata(202, "99999999999999999999"), nowUtcMillis = base)
        )
    }

    @Test
    fun `http-date retry-after uses the parsed instant when valid and falls back when not`() {
        val base = 1_000_000L
        assertEquals(
            base + 1_000L,
            resolveMobileUsageNotBefore(
                metadata(202, "Thu, 01 Jan 1970 00:00:00 GMT"),
                nowUtcMillis = base
            )
        )
        assertEquals(
            base + 30_000L,
            resolveMobileUsageNotBefore(metadata(202, "Fri, 99 Foo 9999 99:99 GMT"), nowUtcMillis = base)
        )
    }

    @Test
    fun `body retry hint and header merge by conservative maximum`() {
        val base = 1_000_000L
        assertEquals(
            base + 90_000L,
            resolveMobileUsageNotBefore(
                metadata(202, "15"),
                ingest(processingState = "in-flight", retryAfterSeconds = 90),
                base
            )
        )
        assertEquals(
            base + 90_000L,
            resolveMobileUsageNotBefore(
                metadata(202, "90"),
                ingest(processingState = "retry-scheduled", retryAfterSeconds = 15),
                base
            )
        )
    }

    @Test
    fun `relative body hint wins over absolute fields under device clock skew`() {
        val base = 1_000_000L
        val farFuture = "2026-11-10T00:00:00Z"
        assertEquals(
            base + 45_000L,
            resolveMobileUsageNotBefore(
                metadata(202, "15"),
                ingest(
                    processingState = "retry-scheduled",
                    retryAfterSeconds = 45,
                    retryAtUtc = farFuture
                ),
                base
            )
        )
    }

    @Test
    fun `absolute body fields are a fallback when relative hint is absent`() {
        val base = 1_000_000L
        val leaseUntil = Instant.ofEpochMilli(base + 120_000L).toString()
        val retryAt = Instant.ofEpochMilli(base + 90_000L).toString()
        assertEquals(
            base + 120_000L,
            resolveMobileUsageNotBefore(
                metadata(202, null),
                ingest(
                    processingState = "in-flight",
                    retryAfterSeconds = null,
                    leaseUntilUtc = leaseUntil
                ),
                base
            )
        )
        assertEquals(
            base + 90_000L,
            resolveMobileUsageNotBefore(
                metadata(202, null),
                ingest(
                    processingState = "retry-scheduled",
                    retryAfterSeconds = null,
                    retryAtUtc = retryAt
                ),
                base
            )
        )
    }

    @Test
    fun `unknown processing state stays deferred with a bounded deadline`() {
        val base = 1_000_000L
        assertEquals(
            base + 30_000L,
            resolveMobileUsageNotBefore(
                metadata(202, null),
                ingest(processingState = "future-state"),
                base
            )
        )
        assertEquals(
            base + 40_000L,
            resolveMobileUsageNotBefore(
                metadata(202, "20"),
                ingest(processingState = null, retryAfterSeconds = 40),
                base
            )
        )
        assertEquals("server-processing-unknown", processingStateStatus(null))
        assertEquals("server-processing-unknown", processingStateStatus("future-state"))
        assertEquals("server-processing", processingStateStatus("in-flight"))
        assertEquals("server-retry-scheduled", processingStateStatus("retry-scheduled"))
    }

    @Test
    fun `malformed and oversized body hints remain bounded`() {
        val base = 1_000_000L
        assertEquals(
            base + MAX_USAGE_NOT_BEFORE_DELAY_MS,
            resolveMobileUsageNotBefore(
                metadata(202, "15"),
                ingest(retryAfterSeconds = Int.MAX_VALUE),
                base
            )
        )
        assertEquals(
            base + 20_000L,
            resolveMobileUsageNotBefore(
                metadata(202, "20"),
                ingest(processingState = "retry-scheduled", retryAtUtc = "not-a-date"),
                base
            )
        )
    }

    @Test
    fun `remaining delay conversion always rounds up to whole seconds`() {
        assertEquals(0L, retryDelaySecondsForRemainingMillis(0L))
        assertEquals(0L, retryDelaySecondsForRemainingMillis(-1L))
        assertEquals(1L, retryDelaySecondsForRemainingMillis(1L))
        assertEquals(1L, retryDelaySecondsForRemainingMillis(1_000L))
        assertEquals(2L, retryDelaySecondsForRemainingMillis(1_001L))
        assertEquals(
            Long.MAX_VALUE / 1_000L,
            retryDelaySecondsForRemainingMillis(Long.MAX_VALUE)
        )
    }
}

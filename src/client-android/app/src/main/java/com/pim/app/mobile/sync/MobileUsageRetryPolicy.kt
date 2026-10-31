package com.pim.app.mobile.sync

import com.pim.core.contract.MobileUsageIngestResult
import com.pim.core.network.MobileUsageResponseMetadata
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val DEFAULT_USAGE_NOT_BEFORE_DELAY_MS = 30_000L
internal const val MIN_USAGE_NOT_BEFORE_DELAY_MS = 1_000L
internal const val MAX_USAGE_NOT_BEFORE_DELAY_MS = 24L * 60L * 60L * 1_000L

/**
 * Resolves the processing not-before constraint conservatively.
 *
 * The body's relative `retryAfterSeconds` is preferred over the absolute
 * `leaseUntilUtc` / `retryAtUtc` fields because it is immune to device clock
 * skew. The header and body relative hints are merged by taking the later one.
 * Unknown processing states still produce a deadline and never become success.
 */
internal fun resolveMobileUsageNotBefore(
    metadata: MobileUsageResponseMetadata?,
    ingest: MobileUsageIngestResult? = null,
    nowUtcMillis: Long
): Long? {
    if (metadata?.httpStatus != 202) return null
    val headerCandidate = metadata.retryAfterHeader
        ?.let { parseRetryAfterDeadline(it, nowUtcMillis) }
        ?: safeAdd(nowUtcMillis, DEFAULT_USAGE_NOT_BEFORE_DELAY_MS)
    val bodyCandidate = resolveBodyNotBefore(ingest, nowUtcMillis)
    val candidate = if (bodyCandidate == null) {
        headerCandidate
    } else {
        maxOf(headerCandidate, bodyCandidate)
    }
    return normalizeUsageNotBeforeDeadline(candidate, nowUtcMillis)
}

internal fun resolveBodyNotBefore(
    ingest: MobileUsageIngestResult?,
    nowUtcMillis: Long
): Long? {
    if (ingest == null) return null
    val relative = ingest.retryAfterSeconds
        ?.takeIf { it > 0 }
        ?.let { seconds ->
            val boundedSeconds = minOf(seconds.toLong(), MAX_USAGE_NOT_BEFORE_DELAY_MS / 1_000L)
            safeAdd(nowUtcMillis, boundedSeconds * 1_000L)
        }
    if (relative != null) return relative
    val absolute = when (ingest.processingState) {
        "in-flight" -> ingest.leaseUntilUtc
        "retry-scheduled" -> ingest.retryAtUtc
        else -> null
    }
    return absolute?.let(::parseIsoInstantMillis)
}

internal fun processingStateStatus(processingState: String?): String {
    return when (processingState) {
        "in-flight" -> "server-processing"
        "retry-scheduled" -> "server-retry-scheduled"
        else -> "server-processing-unknown"
    }
}

private fun parseIsoInstantMillis(value: String): Long? = runCatching {
    Instant.parse(value).toEpochMilli()
}.getOrNull()

internal fun parseRetryAfterDeadline(value: String, nowUtcMillis: Long): Long {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return safeAdd(nowUtcMillis, DEFAULT_USAGE_NOT_BEFORE_DELAY_MS)
    }
    val seconds = trimmed.toLongOrNull()
    if (seconds != null) {
        if (seconds <= 0) return safeAdd(nowUtcMillis, MIN_USAGE_NOT_BEFORE_DELAY_MS)
        val cappedSeconds = minOf(seconds, MAX_USAGE_NOT_BEFORE_DELAY_MS / 1_000L)
        return safeAdd(nowUtcMillis, cappedSeconds * 1_000L)
    }
    return runCatching {
        ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
    }.getOrElse {
        if (trimmed.all { it.isDigit() }) {
            safeAdd(nowUtcMillis, MAX_USAGE_NOT_BEFORE_DELAY_MS)
        } else {
            safeAdd(nowUtcMillis, DEFAULT_USAGE_NOT_BEFORE_DELAY_MS)
        }
    }
}

internal fun normalizeUsageNotBeforeDeadline(
    candidateUtcMillis: Long,
    nowUtcMillis: Long
): Long {
    val earliest = safeAdd(nowUtcMillis, MIN_USAGE_NOT_BEFORE_DELAY_MS)
    val latest = safeAdd(nowUtcMillis, MAX_USAGE_NOT_BEFORE_DELAY_MS)
    return candidateUtcMillis.coerceIn(earliest, latest)
}

internal fun safeAdd(left: Long, right: Long): Long {
    if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE
    return left + right
}

internal fun retryDelaySecondsForRemainingMillis(remainingMillis: Long): Long {
    if (remainingMillis <= 0L) return 0L
    return if (remainingMillis > Long.MAX_VALUE - 999L) {
        Long.MAX_VALUE / 1_000L
    } else {
        (remainingMillis + 999L) / 1_000L
    }
}

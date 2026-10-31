package com.pim.app.mobile.sync

import com.pim.core.network.MobileUsageResponseMetadata
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val DEFAULT_USAGE_NOT_BEFORE_DELAY_MS = 30_000L
internal const val MIN_USAGE_NOT_BEFORE_DELAY_MS = 1_000L
internal const val MAX_USAGE_NOT_BEFORE_DELAY_MS = 24L * 60L * 60L * 1_000L

/**
 * Resolves transport-only processing constraints conservatively.
 *
 * Contract body signals are intentionally not read here. Once the official
 * generated DTO contains them, their deadlines can be merged by taking the
 * maximum before [normalizeUsageNotBeforeDeadline] is applied.
 */
internal fun resolveMobileUsageNotBefore(
    metadata: MobileUsageResponseMetadata?,
    nowUtcMillis: Long
): Long? {
    if (metadata?.httpStatus != 202) return null
    val candidate = metadata.retryAfterHeader
        ?.let { parseRetryAfterDeadline(it, nowUtcMillis) }
        ?: safeAdd(nowUtcMillis, DEFAULT_USAGE_NOT_BEFORE_DELAY_MS)
    return normalizeUsageNotBeforeDeadline(candidate, nowUtcMillis)
}

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

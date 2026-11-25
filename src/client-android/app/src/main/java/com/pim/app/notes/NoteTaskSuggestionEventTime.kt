package com.pim.app.notes

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale

private val eventInputFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("uuuu-MM-dd'T'HH:mm")
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val eventInputPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")
private val fixedOffsetPattern = Regex("^[+-]\\d{2}:?\\d{2}$")
private val utcOffsetPattern = Regex("^(?:UTC|GMT)[+-]\\d{1,2}(?::?\\d{2})?$", RegexOption.IGNORE_CASE)

/** IANA IDs only: fixed offsets and Windows IDs are rejected instead of using the device zone. */
internal fun isIanaTimeZone(value: String?): Boolean {
    val zone = value?.trim() ?: return false
    if (zone.isEmpty() || zone.any { it.isWhitespace() } ||
        fixedOffsetPattern.matches(zone) || utcOffsetPattern.matches(zone)
    ) return false
    return try {
        ZoneId.of(zone) !is ZoneOffset
    } catch (_: DateTimeException) {
        false
    }
}

/** Converts an absolute ISO instant into a `yyyy-MM-ddTHH:mm` wall clock in the event zone. */
internal fun suggestionEventInput(value: String?, timeZoneId: String?): String {
    val zone = timeZoneId?.trim()?.takeIf { isIanaTimeZone(it) } ?: return ""
    val instant = parseEventInstant(value) ?: return ""
    return eventInputFormatter.format(instant.atZone(ZoneId.of(zone)))
}

/**
 * Converts a wall-clock input in the declared IANA zone to UTC ISO.
 * DST gaps and ambiguous wall-clock values fail closed instead of picking one offset.
 */
internal fun suggestionEventWire(value: String, timeZoneId: String?): String? {
    if (value.isBlank()) return null
    val zone = timeZoneId?.trim().orEmpty()
    if (zone.isEmpty()) throw IllegalArgumentException("请先填写日程的 IANA 时区")
    if (!isIanaTimeZone(zone)) throw IllegalArgumentException("日程时区必须是有效的 IANA 时区")
    if (!eventInputPattern.matches(value)) {
        throw IllegalArgumentException("时间格式必须是 YYYY-MM-DDTHH:mm")
    }
    val local = LocalDateTime.parse(value, eventInputFormatter)
    val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
    if (offsets.isEmpty()) {
        throw IllegalArgumentException("该时间在所选时区不存在，请避开夏令时跳变")
    }
    if (offsets.size > 1) {
        throw IllegalArgumentException("该时间在所选时区重复，无法唯一换算")
    }
    return local.atOffset(offsets.single()).toInstant().toString()
}

internal fun eventRangeIsOrdered(start: String, end: String, timeZoneId: String): Boolean {
    val startIso = suggestionEventWire(start, timeZoneId) ?: return false
    val endIso = suggestionEventWire(end, timeZoneId) ?: return false
    return Instant.parse(endIso).isAfter(Instant.parse(startIso))
}

internal fun formatEventRange(
    start: String?,
    end: String?,
    timeZoneId: String?,
): String {
    if (start.isNullOrBlank() || end.isNullOrBlank()) return "待补开始或结束时间"
    val zone = timeZoneId?.trim().orEmpty()
    if (zone.isBlank()) {
        return "时区未知：将按已保存的绝对时间创建；如需按当地时间编辑，请先补全 IANA 时区"
    }
    if (!isIanaTimeZone(zone)) return "时区无效：请核对 IANA 时区后再确认创建日程"
    val startInstant = parseEventInstant(start)
    val endInstant = parseEventInstant(end)
    if (startInstant == null || endInstant == null) return "日程时间无效：请重新选择开始和结束时间"
    val startText = eventInputFormatter.format(startInstant.atZone(ZoneId.of(zone)))
    val endText = eventInputFormatter.format(endInstant.atZone(ZoneId.of(zone)))
    val startDate = startText.take(10)
    val endDate = endText.take(10)
    val range = if (startDate == endDate) {
        "$startDate ${startText.substring(11)}–${endText.substring(11)}"
    } else {
        "$startDate ${startText.substring(11)} – $endDate ${endText.substring(11)}"
    }
    return "$range · $zone"
}

private fun parseEventInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrNull()
}

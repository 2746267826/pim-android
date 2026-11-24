package com.pim.app.nativework

import com.pim.core.contract.EventResponse
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class NativeCalendarViewMode(val label: String) {
    Day("日"),
    Week("周"),
    Month("月"),
}

data class NativeCalendarViewState(
    val mode: NativeCalendarViewMode = NativeCalendarViewMode.Day,
    val anchorDate: LocalDate = LocalDate.now(),
)

data class NativeCalendarDateRange(
    val start: LocalDate,
    val endExclusive: LocalDate,
) {
    init {
        require(!endExclusive.isAfter(start)) { "日历范围结束日期必须晚于开始日期。" }
    }

    val dates: List<LocalDate>
        get() = generateSequence(start) { current ->
            current.plusDays(1).takeIf { it.isBefore(endExclusive) }
        }.toList()

    fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && date.isBefore(endExclusive)
}

data class NativeCalendarEventDates(
    val start: LocalDate,
    val endExclusive: LocalDate,
)

fun NativeCalendarViewState.shift(delta: Int): NativeCalendarViewState {
    if (delta == 0) return this
    return copy(
        anchorDate = when (mode) {
            NativeCalendarViewMode.Day -> anchorDate.plusDays(delta.toLong())
            NativeCalendarViewMode.Week -> anchorDate.plusWeeks(delta.toLong())
            NativeCalendarViewMode.Month ->
                anchorDate.withDayOfMonth(1).plusMonths(delta.toLong())
        }
    )
}

fun NativeCalendarViewState.goToToday(today: LocalDate): NativeCalendarViewState =
    copy(anchorDate = today)

fun NativeCalendarViewState.goToDate(date: LocalDate): NativeCalendarViewState =
    copy(anchorDate = date)

fun NativeCalendarViewState.openDay(date: LocalDate): NativeCalendarViewState =
    copy(anchorDate = date)

fun NativeCalendarViewState.withMode(mode: NativeCalendarViewMode): NativeCalendarViewState =
    copy(mode = mode)

/**
 * Day and week pages use their natural local calendar bounds. A month page uses
 * the complete Monday-to-Sunday grid so events in the leading/trailing cells
 * remain visible. LocalDate.plusMonths clamps Jan 31 to Feb 28/29.
 */
fun NativeCalendarViewState.dateRange(): NativeCalendarDateRange = when (mode) {
    NativeCalendarViewMode.Day -> NativeCalendarDateRange(
        start = anchorDate,
        endExclusive = anchorDate.plusDays(1),
    )
    NativeCalendarViewMode.Week -> {
        val start = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        NativeCalendarDateRange(start = start, endExclusive = start.plusDays(7))
    }
    NativeCalendarViewMode.Month -> {
        val month = YearMonth.from(anchorDate)
        val start = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastDay = month.atEndOfMonth()
        val endExclusive = lastDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusDays(1)
        NativeCalendarDateRange(start = start, endExclusive = endExclusive)
    }
}

fun NativeCalendarViewState.periodTitle(): String {
    val range = dateRange()
    val endInclusive = range.endExclusive.minusDays(1)
    return when (mode) {
        NativeCalendarViewMode.Day -> {
            val weekday = anchorDate.dayOfWeek.getDisplayName(
                TextStyle.SHORT,
                Locale.SIMPLIFIED_CHINESE,
            )
            "${anchorDate.year}年${anchorDate.monthValue}月${anchorDate.dayOfMonth}日 $weekday"
        }
        NativeCalendarViewMode.Week -> if (range.start.year == endInclusive.year) {
            "${range.start.year}年${range.start.monthValue}月${range.start.dayOfMonth}日 – " +
                "${endInclusive.monthValue}月${endInclusive.dayOfMonth}日"
        } else {
            "${range.start.year}年${range.start.monthValue}月${range.start.dayOfMonth}日 – " +
                "${endInclusive.year}年${endInclusive.monthValue}月${endInclusive.dayOfMonth}日"
        }
        NativeCalendarViewMode.Month -> "${anchorDate.year}年${anchorDate.monthValue}月"
    }
}

fun EventResponse.nativeCalendarDates(zoneId: ZoneId): NativeCalendarEventDates? {
    if (isAllDay == true) {
        val start = allDayLocalDate(dtStart) ?: return null
        val parsedEnd = allDayLocalDate(dtEnd)
        val endExclusive = if (parsedEnd == null || !parsedEnd.isAfter(start)) {
            start.plusDays(1)
        } else {
            parsedEnd
        }
        return NativeCalendarEventDates(start = start, endExclusive = endExclusive)
    }

    val startInstant = parseCalendarInstant(dtStart) ?: return null
    val endInstant = parseCalendarInstant(dtEnd) ?: return null
    val startDate = startInstant.atZone(zoneId).toLocalDate()
    val endLocal = endInstant.atZone(zoneId)
    val endExclusive = if (endLocal.toLocalTime() == LocalTime.MIDNIGHT &&
        endInstant == endLocal.toLocalDate().atStartOfDay(zoneId).toInstant()
    ) {
        endLocal.toLocalDate()
    } else {
        endLocal.toLocalDate().plusDays(1)
    }
    return NativeCalendarEventDates(
        start = startDate,
        endExclusive = if (endExclusive.isAfter(startDate)) endExclusive else startDate.plusDays(1),
    )
}

/**
 * All-day events are date-only values. Their serialized date must not be
 * converted through the display timezone: a UTC-midnight all-day event would
 * otherwise move to the previous day in negative offsets, and a DST fold must
 * not duplicate or shift the date.
 */
private fun allDayLocalDate(raw: String): LocalDate? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }
        .recoverCatching { LocalDateTime.parse(value).toLocalDate() }
        .recoverCatching { LocalDate.parse(value) }
        .getOrNull()
}

fun calendarEventsByDate(
    events: List<EventResponse>,
    range: NativeCalendarDateRange,
    zoneId: ZoneId,
): Map<LocalDate, List<EventResponse>> {
    val grouped = range.dates.associateWith { mutableListOf<EventResponse>() }
    events.forEach { event ->
        val dates = event.nativeCalendarDates(zoneId) ?: return@forEach
        val first = maxOf(dates.start, range.start)
        val lastExclusive = minOf(dates.endExclusive, range.endExclusive)
        var date = first
        while (date.isBefore(lastExclusive)) {
            grouped[date]?.add(event)
            date = date.plusDays(1)
        }
    }
    return grouped.mapValues { (_, dayEvents) ->
        dayEvents.sortedWith(nativeCalendarEventComparator)
    }
}

fun eventsForLocalDate(
    events: List<EventResponse>,
    date: LocalDate,
    zoneId: ZoneId,
): List<EventResponse> = events.filter { event ->
    event.nativeCalendarDates(zoneId)?.let { dates ->
        !date.isBefore(dates.start) && date.isBefore(dates.endExclusive)
    } == true
}.sortedWith(nativeCalendarEventComparator)

private val nativeCalendarEventComparator = Comparator<EventResponse> { left, right ->
    if (left.isAllDay != right.isAllDay) {
        return@Comparator if (left.isAllDay == true) -1 else 1
    }
    val leftStart = eventStartInstant(left)
    val rightStart = eventStartInstant(right)
    when {
        leftStart != null && rightStart != null && leftStart != rightStart ->
            leftStart.compareTo(rightStart)
        leftStart == null && rightStart != null -> -1
        leftStart != null && rightStart == null -> 1
        else -> {
            val titleComparison = left.title.compareTo(right.title)
            if (titleComparison != 0) titleComparison else left.id.compareTo(right.id)
        }
    }
}

fun eventStartInstant(event: EventResponse): Instant? =
    parseCalendarInstant(event.dtStart)

private fun parseCalendarInstant(raw: String): Instant? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

fun localDateToDatePickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun datePickerMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

fun formatCalendarEventRange(event: EventResponse, zoneId: ZoneId): String {
    if (event.isAllDay == true) {
        val dates = event.nativeCalendarDates(zoneId) ?: return "${event.dtStart} – ${event.dtEnd}"
        val endInclusive = dates.endExclusive.minusDays(1)
        val formatter = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
        return if (dates.start == endInclusive) {
            "全天 · ${formatter.format(dates.start)}"
        } else {
            "全天 · ${formatter.format(dates.start)} – ${formatter.format(endInclusive)}"
        }
    }
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
        .withZone(zoneId)
    val start = parseCalendarInstant(event.dtStart)
    val end = parseCalendarInstant(event.dtEnd)
    return when {
        start != null && end != null -> "${formatter.format(start)} – ${formatter.format(end)}"
        else -> "${event.dtStart} – ${event.dtEnd}"
    }
}

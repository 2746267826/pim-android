@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pim.app.nativework

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pim.core.contract.EventResponse
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class CalendarViewMode(val label: String) {
    Day("日"),
    Week("周"),
    Month("月"),
    List("列表"),
}

internal object CalendarViewPreference {
    private const val PREFERENCES = "pim_calendar_view"
    private const val VIEW_KEY = "view"

    fun read(context: Context): CalendarViewMode? {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(VIEW_KEY, null)
            ?: return null
        return runCatching { CalendarViewMode.valueOf(raw) }.getOrNull()
    }

    fun write(context: Context, view: CalendarViewMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(VIEW_KEY, view.name)
            .apply()
    }
}

internal fun calendarRangeFor(
    view: CalendarViewMode,
    anchor: LocalDate,
    zoneId: ZoneId = ZoneId.systemDefault(),
): NativeWorkbenchRange {
    val dates = calendarRangeDates(view, anchor)
    return NativeWorkbenchRange(
        start = dates.first().atStartOfDay(zoneId).toInstant().toString(),
        endExclusive = dates.last().plusDays(1).atStartOfDay(zoneId).toInstant().toString(),
    )
}

internal fun calendarRangeDates(view: CalendarViewMode, anchor: LocalDate): List<LocalDate> =
    when (view) {
        CalendarViewMode.Day -> listOf(anchor)
        CalendarViewMode.Week -> {
            val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            (0L..6L).map(start::plusDays)
        }
        CalendarViewMode.Month -> calendarGridDates(anchor)
        CalendarViewMode.List -> (0L..6L).map(anchor::plusDays)
    }

internal fun calendarGridDates(anchor: LocalDate): List<LocalDate> {
    val start = anchor.withDayOfMonth(1)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val endExclusive = anchor.withDayOfMonth(anchor.lengthOfMonth())
        .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        .plusDays(1)
    return generateSequence(start) { it.plusDays(1) }
        .takeWhile { it.isBefore(endExclusive) }
        .toList()
}

internal fun moveCalendarDate(
    view: CalendarViewMode,
    anchor: LocalDate,
    direction: Int,
): LocalDate = when (view) {
    CalendarViewMode.Day -> anchor.plusDays(direction.toLong())
    CalendarViewMode.Week -> anchor.plusWeeks(direction.toLong())
    CalendarViewMode.Month -> {
        val target = YearMonth.from(anchor).plusMonths(direction.toLong())
        target.atDay(anchor.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
    }
    CalendarViewMode.List -> anchor.plusDays(7L * direction)
}

internal fun calendarViewTitle(view: CalendarViewMode, anchor: LocalDate): String = when (view) {
    CalendarViewMode.Day -> "${anchor.year}年${anchor.monthValue}月${anchor.dayOfMonth}日 ${weekdayLabel(anchor)}"
    CalendarViewMode.Week -> {
        val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = start.plusDays(6)
        "${start.year}年${start.monthValue}月${start.dayOfMonth}日 – " +
            "${end.monthValue}月${end.dayOfMonth}日"
    }
    CalendarViewMode.Month -> "${anchor.year}年${anchor.monthValue}月"
    CalendarViewMode.List -> "${anchor.year}年${anchor.monthValue}月${anchor.dayOfMonth}日 起 7 天"
}

internal fun calendarDateFromPickerMillis(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun calendarPickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal data class CalendarEventPlacement(
    val event: EventResponse,
    val startDate: LocalDate,
    val endDateExclusive: LocalDate,
    val allDay: Boolean,
    val zone: ZoneId,
    val zoneResolved: Boolean,
    val startInstant: Instant,
    val endInstant: Instant,
) {
    fun covers(date: LocalDate): Boolean =
        !date.isBefore(startDate) && date.isBefore(endDateExclusive)
}

internal fun calendarEventPlacement(
    event: EventResponse,
    displayZone: ZoneId,
): CalendarEventPlacement? {
    val startInstant = parseEventInstant(event.dtStart) ?: return null
    val endInstant = parseEventInstant(event.dtEnd) ?: startInstant
    val allDay = event.isAllDay == true
    val (zone, resolved) = if (allDay) {
        allDayZone(event, displayZone)
    } else {
        displayZone to true
    }
    val startDate = startInstant.atZone(zone).toLocalDate()
    val endLocal = endInstant.atZone(zone)
    var endDateExclusive = if (endLocal.toLocalTime() == LocalTime.MIDNIGHT) {
        endLocal.toLocalDate()
    } else {
        endLocal.toLocalDate().plusDays(1)
    }
    if (!endDateExclusive.isAfter(startDate)) endDateExclusive = startDate.plusDays(1)
    return CalendarEventPlacement(
        event = event,
        startDate = startDate,
        endDateExclusive = endDateExclusive,
        allDay = allDay,
        zone = zone,
        zoneResolved = resolved,
        startInstant = startInstant,
        endInstant = endInstant,
    )
}

internal fun eventsForCalendarDate(
    events: List<EventResponse>,
    date: LocalDate,
    displayZone: ZoneId,
): List<CalendarEventPlacement> = events
    .mapNotNull { calendarEventPlacement(it, displayZone) }
    .filter { it.covers(date) }
    .sortedWith(
        compareByDescending<CalendarEventPlacement> { it.allDay }
            .thenBy { it.startInstant }
            .thenBy { it.event.title }
    )

internal fun calendarEventTimeLabel(
    placement: CalendarEventPlacement,
    displayZone: ZoneId,
): String {
    if (placement.allDay) {
        val lastDate = placement.endDateExclusive.minusDays(1)
        return if (lastDate == placement.startDate) {
            "全天 · ${monthDayLabel(placement.startDate)}"
        } else {
            "全天 · ${monthDayLabel(placement.startDate)} – ${monthDayLabel(lastDate)}"
        }
    }
    val start = placement.startInstant.atZone(displayZone)
    val end = placement.endInstant.atZone(displayZone)
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)
    return if (start.toLocalDate() == end.toLocalDate()) {
        "${start.format(time)}–${end.format(time)}"
    } else {
        "${start.format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE))} – " +
            end.format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE))
    }
}

private fun allDayZone(event: EventResponse, displayZone: ZoneId): Pair<ZoneId, Boolean> {
    val raw = event.timeZoneId?.trim()
    if (!raw.isNullOrEmpty()) {
        runCatching { ZoneId.of(raw) }.getOrNull()?.let { return it to true }
    }
    val recordedOffset = runCatching { OffsetDateTime.parse(event.dtStart).offset }.getOrNull()
    return (recordedOffset ?: displayZone) to false
}

private fun parseEventInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw) }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
        .getOrNull()
}

private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

private fun monthDayLabel(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

@Composable
internal fun CalendarNativeContent(
    state: NativeWorkbenchUiState,
    onViewChange: (CalendarViewMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onRestoreView: (CalendarViewMode) -> Unit,
    onRetry: () -> Unit,
    onNewEvent: () -> Unit,
    onCreateCalendar: () -> Unit,
    onEditEvent: (EventResponse) -> Unit,
    onDeleteEvent: (EventResponse) -> Unit,
) {
    val context = LocalContext.current
    val displayZone = remember { ZoneId.systemDefault() }
    var showDatePicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        CalendarViewPreference.read(context)?.let(onRestoreView)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CalendarHeader(
                state = state,
                onViewChange = { view ->
                    CalendarViewPreference.write(context, view)
                    onViewChange(view)
                },
                onPrevious = onPrevious,
                onNext = onNext,
                onToday = onToday,
                onOpenDatePicker = { showDatePicker = true },
                onNewEvent = onNewEvent,
                onCreateCalendar = onCreateCalendar,
            )
        }
        if (state.loading) {
            item { CalendarLoadingRow() }
        }
        val error = state.error
        if (!state.loading && error != null && !state.calendarLoaded) {
            item { CalendarErrorCard(error, onRetry) }
        }
        if (!state.loading && state.calendarLoaded) {
            when (state.calendarView) {
                CalendarViewMode.Day -> item {
                    CalendarDaySection(
                        state = state,
                        displayZone = displayZone,
                        onEditEvent = onEditEvent,
                        onDeleteEvent = onDeleteEvent,
                    )
                }
                CalendarViewMode.Week -> item {
                    CalendarWeekSection(
                        state = state,
                        displayZone = displayZone,
                        onDateSelected = onDateSelected,
                        onEditEvent = onEditEvent,
                        onDeleteEvent = onDeleteEvent,
                    )
                }
                CalendarViewMode.Month -> item {
                    CalendarMonthSection(
                        state = state,
                        displayZone = displayZone,
                        onOpenDay = onOpenDay,
                    )
                }
                CalendarViewMode.List -> item {
                    CalendarListSection(
                        state = state,
                        displayZone = displayZone,
                        onEditEvent = onEditEvent,
                        onDeleteEvent = onDeleteEvent,
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = calendarPickerMillis(state.calendarDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            onDateSelected(calendarDateFromPickerMillis(it))
                        }
                        showDatePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(
                state = pickerState,
                modifier = Modifier.testTag("calendar-date-picker-dialog"),
            )
        }
    }
}

@Composable
private fun CalendarHeader(
    state: NativeWorkbenchUiState,
    onViewChange: (CalendarViewMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onNewEvent: () -> Unit,
    onCreateCalendar: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.testTag("calendar-prev"),
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一页")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    calendarViewTitle(state.calendarView, state.calendarDate),
                    modifier = Modifier.testTag("calendar-title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "日历 · 本机时区 ${ZoneId.systemDefault().id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.testTag("calendar-next"),
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下一页")
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onToday,
                modifier = Modifier.testTag("calendar-today"),
            ) { Text("今天") }
            OutlinedButton(
                onClick = onOpenDatePicker,
                modifier = Modifier.testTag("calendar-date-picker"),
            ) { Text("选择日期") }
            TextButton(onClick = onCreateCalendar) { Text("新建日历") }
            Button(
                onClick = onNewEvent,
                enabled = state.calendars.isNotEmpty(),
            ) { Text("新建日程") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CalendarViewMode.entries.forEach { view ->
                FilterChip(
                    selected = state.calendarView == view,
                    onClick = { onViewChange(view) },
                    label = { Text(view.label) },
                    modifier = Modifier.testTag("calendar-view-${view.name.lowercase()}"),
                )
            }
            if (state.calendars.isEmpty()) {
                Text(
                    "没有可写日历，请先新建。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        Text(
            "日程按本机时区显示；全天事件优先使用事件时区，时区未知时保留记录偏移。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CalendarDaySection(
    state: NativeWorkbenchUiState,
    displayZone: ZoneId,
    onEditEvent: (EventResponse) -> Unit,
    onDeleteEvent: (EventResponse) -> Unit,
) {
    val placements = eventsForCalendarDate(state.events, state.calendarDate, displayZone)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (placements.isEmpty()) {
            CalendarEmptyCard("当天暂无日程")
        } else {
            placements.forEach { placement ->
                CalendarEventRow(
                    placement = placement,
                    displayZone = displayZone,
                    onEdit = { onEditEvent(placement.event) },
                    onDelete = { onDeleteEvent(placement.event) },
                )
            }
        }
    }
}

@Composable
private fun CalendarWeekSection(
    state: NativeWorkbenchUiState,
    displayZone: ZoneId,
    onDateSelected: (LocalDate) -> Unit,
    onEditEvent: (EventResponse) -> Unit,
    onDeleteEvent: (EventResponse) -> Unit,
) {
    val dates = calendarRangeDates(CalendarViewMode.Week, state.calendarDate)
    val today = LocalDate.now()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dates.forEach { date ->
                    CalendarDayChip(
                        date = date,
                        selected = date == state.calendarDate,
                        today = date == today,
                        modifier = Modifier.weight(1f),
                        onClick = { onDateSelected(date) },
                    )
                }
            }
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    dates.forEach { date ->
                        val placements = eventsForCalendarDate(state.events, date, displayZone)
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "${date.dayOfMonth}日",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            placements.take(3).forEach { placement ->
                                CalendarWeekChip(
                                    placement = placement,
                                    onClick = { onEditEvent(placement.event) },
                                )
                            }
                            if (placements.size > 3) {
                                Text(
                                    "+${placements.size - 3}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            } else {
                CalendarDaySection(
                    state = state,
                    displayZone = displayZone,
                    onEditEvent = onEditEvent,
                    onDeleteEvent = onDeleteEvent,
                )
            }
        }
    }
}

@Composable
private fun CalendarMonthSection(
    state: NativeWorkbenchUiState,
    displayZone: ZoneId,
    onOpenDay: (LocalDate) -> Unit,
) {
    val dates = calendarGridDates(state.calendarDate)
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        dates.chunked(7).forEach { week ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { date ->
                    CalendarMonthCell(
                        date = date,
                        selected = date == state.calendarDate,
                        today = date == today,
                        inCurrentMonth = date.month == state.calendarDate.month,
                        placements = eventsForCalendarDate(state.events, date, displayZone),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenDay(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarListSection(
    state: NativeWorkbenchUiState,
    displayZone: ZoneId,
    onEditEvent: (EventResponse) -> Unit,
    onDeleteEvent: (EventResponse) -> Unit,
) {
    val rangeDates = calendarRangeDates(CalendarViewMode.List, state.calendarDate)
    val byStartDate = state.events
        .mapNotNull { calendarEventPlacement(it, displayZone) }
        .filter { it.startDate in rangeDates }
        .groupBy { it.startDate }
        .mapValues { (_, placements) ->
            placements.sortedWith(
                compareByDescending<CalendarEventPlacement> { it.allDay }
                    .thenBy { it.startInstant }
            )
        }
    val groups = rangeDates.map { date -> date to byStartDate[date].orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (groups.all { it.second.isEmpty() }) {
            CalendarEmptyCard("该范围暂无日程")
        } else {
            groups.forEach { (date, placements) ->
                if (placements.isNotEmpty()) {
                    Text(
                        "${date.year}年${date.monthValue}月${date.dayOfMonth}日 ${weekdayLabel(date)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    placements.forEach { placement ->
                        CalendarEventRow(
                            placement = placement,
                            displayZone = displayZone,
                            onEdit = { onEditEvent(placement.event) },
                            onDelete = { onDeleteEvent(placement.event) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEventRow(
    placement: CalendarEventPlacement,
    displayZone: ZoneId,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val event = placement.event
    val blocked = event.isOutlookMirror() || event.hasRecurrenceMetadata()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    calendarEventTimeLabel(placement, displayZone),
                    Modifier.width(112.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!event.location.isNullOrBlank()) {
                        Text(
                            "地点 ${event.location}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "来源 ${event.source}" +
                            (event.timeZoneId?.let { " · 时区 $it" } ?: " · 时区未知"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (blocked) {
                Text(
                    if (event.isOutlookMirror()) {
                        "Outlook 镜像需通过同步写回"
                    } else {
                        "重复日程需确认编辑范围，请暂用日历网页"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayChip(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        today -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val foreground = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .testTag("calendar-week-day-$date"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            weekdayLabel(date).removePrefix("周"),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
        )
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

@Composable
private fun CalendarWeekChip(
    placement: CalendarEventPlacement,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                placement.event.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CalendarMonthCell(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    inCurrentMonth: Boolean,
    placements: List<CalendarEventPlacement>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        today -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }
    Column(
        modifier
            .heightIn(min = 92.dp)
            .clip(shape)
            .background(background)
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(4.dp)
            .testTag("calendar-day-$date"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Normal,
            color = if (inCurrentMonth) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
        )
        placements.take(2).forEach { placement ->
            Text(
                placement.event.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (placement.allDay) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (placements.size > 2) {
            Text(
                "+${placements.size - 2}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CalendarEmptyCard(text: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CalendarLoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("正在读取日程…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CalendarErrorCard(message: String, onRetry: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "日程读取失败：$message",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

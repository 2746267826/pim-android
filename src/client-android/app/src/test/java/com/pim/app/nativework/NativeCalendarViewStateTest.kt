package com.pim.app.nativework

import com.pim.core.contract.EventResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCalendarViewStateTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun dayAndWeekRangesUseLocalCalendarBoundaries() {
        val saturday = LocalDate.of(2026, 1, 31)

        assertEquals(
            NativeCalendarDateRange(saturday, LocalDate.of(2026, 2, 1)),
            NativeCalendarViewState(NativeCalendarViewMode.Day, saturday).dateRange(),
        )
        assertEquals(
            NativeCalendarDateRange(LocalDate.of(2026, 1, 26), LocalDate.of(2026, 2, 2)),
            NativeCalendarViewState(NativeCalendarViewMode.Week, saturday).dateRange(),
        )
        assertEquals(
            "2026年1月31日 周六",
            NativeCalendarViewState(NativeCalendarViewMode.Day, saturday).periodTitle(),
        )
    }

    @Test
    fun monthRangeCoversTheCompleteMondayToSundayGrid() {
        val february = LocalDate.of(2026, 2, 15)
        val range = NativeCalendarViewState(NativeCalendarViewMode.Month, february).dateRange()

        assertEquals(LocalDate.of(2026, 1, 26), range.start)
        assertEquals(LocalDate.of(2026, 3, 2), range.endExclusive)
        assertEquals(35, range.dates.size)
        assertTrue(range.contains(LocalDate.of(2026, 2, 1)))
        assertTrue(range.contains(LocalDate.of(2026, 2, 28)))
        assertFalse(range.contains(range.endExclusive))
        assertEquals(
            "2026年2月",
            NativeCalendarViewState(NativeCalendarViewMode.Month, february).periodTitle(),
        )
    }

    @Test
    fun monthNavigationClampsToTargetMonthEndAndLeapFebruary() {
        val january31 = NativeCalendarViewState(
            NativeCalendarViewMode.Month,
            LocalDate.of(2026, 1, 31),
        )
        assertEquals(LocalDate.of(2026, 2, 28), january31.shift(1).anchorDate)
        assertEquals(
            LocalDate.of(2026, 3, 28),
            january31.shift(1).shift(1).anchorDate,
        )

        val leapJanuary31 = january31.copy(anchorDate = LocalDate.of(2028, 1, 31))
        assertEquals(LocalDate.of(2028, 2, 29), leapJanuary31.shift(1).anchorDate)
        assertEquals(
            LocalDate.of(2028, 3, 29),
            leapJanuary31.shift(1).shift(1).anchorDate,
        )

        val march31 = january31.copy(anchorDate = LocalDate.of(2026, 3, 31))
        assertEquals(LocalDate.of(2026, 2, 28), march31.shift(-1).anchorDate)
        assertEquals(
            LocalDate.of(2026, 1, 28),
            march31.shift(-1).shift(-1).anchorDate,
        )

        val february28 = january31.copy(anchorDate = LocalDate.of(2026, 2, 28))
        assertEquals(LocalDate.of(2026, 1, 28), february28.shift(-1).anchorDate)
        assertEquals(LocalDate.of(2026, 3, 28), february28.shift(1).anchorDate)
    }

    @Test
    fun monthNavigationFromANonMonthEndClampsButDoesNotForceMonthEnd() {
        val january30 = NativeCalendarViewState(
            NativeCalendarViewMode.Month,
            LocalDate.of(2026, 1, 30),
        )
        assertEquals(LocalDate.of(2026, 2, 28), january30.shift(1).anchorDate)

        val february15 = january30.copy(anchorDate = LocalDate.of(2026, 2, 15))
        assertEquals(LocalDate.of(2026, 3, 15), february15.shift(1).anchorDate)
        assertEquals(LocalDate.of(2026, 1, 15), february15.shift(-1).anchorDate)
    }

    @Test
    fun dayWeekAndMonthNavigationCrossYearBoundaries() {
        val state = NativeCalendarViewState(
            NativeCalendarViewMode.Day,
            LocalDate.of(2026, 12, 31),
        )
        assertEquals(LocalDate.of(2027, 1, 1), state.shift(1).anchorDate)
        assertEquals(
            LocalDate.of(2027, 1, 7),
            state.withMode(NativeCalendarViewMode.Week).shift(1).anchorDate,
        )
        assertEquals(
            LocalDate.of(2027, 1, 31),
            state.withMode(NativeCalendarViewMode.Month).shift(1).anchorDate,
        )
    }

    @Test
    fun todayAndDatePickerNavigationKeepTheSelectedViewMode() {
        val state = NativeCalendarViewState(
            NativeCalendarViewMode.Week,
            LocalDate.of(2026, 1, 31),
        )
        val today = LocalDate.of(2026, 11, 25)

        assertEquals(state.copy(anchorDate = today), state.goToToday(today))
        assertEquals(
            NativeCalendarViewMode.Week,
            state.goToToday(today).mode,
        )
        assertEquals(
            state.copy(anchorDate = LocalDate.of(2026, 7, 4)),
            state.goToDate(LocalDate.of(2026, 7, 4)),
        )
    }

    @Test
    fun openingAMonthCellSwitchesToTheSelectedDay() {
        val state = NativeCalendarViewState(
            NativeCalendarViewMode.Month,
            LocalDate.of(2026, 2, 15),
        )
        val selected = LocalDate.of(2026, 3, 2)

        assertEquals(
            NativeCalendarViewState(NativeCalendarViewMode.Day, selected),
            state.openDay(selected),
        )
    }

    @Test
    fun datePickerMillisRoundTripIsStableAcrossDstAndOffsets() {
        val date = LocalDate.of(2026, 3, 8)

        assertEquals(date, datePickerMillisToLocalDate(localDateToDatePickerMillis(date)))
        assertEquals(
            "2026-03-08T00:00:00Z",
            Instant.ofEpochMilli(localDateToDatePickerMillis(date)).toString(),
        )
    }

    @Test
    fun springForwardTimedEventsUseInstantThenDisplayZone() {
        val beforeGap = timedEvent(
            id = "before-gap",
            start = "2026-03-08T06:30:00Z",
            end = "2026-03-08T07:00:00Z",
        )
        val afterGap = timedEvent(
            id = "after-gap",
            start = "2026-03-08T07:30:00Z",
            end = "2026-03-08T08:30:00Z",
        )
        val date = LocalDate.of(2026, 3, 8)

        assertEquals(
            listOf("before-gap", "after-gap"),
            eventsForLocalDate(listOf(afterGap, beforeGap), date, newYork).map { it.id },
        )
        assertEquals(
            "03-08 03:30 – 03-08 04:30",
            formatCalendarEventRange(afterGap, newYork),
        )
    }

    @Test
    fun fallBackKeepsBothRepeatedLocalHoursAsDistinctOccurrences() {
        val first = timedEvent(
            id = "first-0130",
            start = "2026-11-01T05:30:00Z",
            end = "2026-11-01T06:30:00Z",
        )
        val second = timedEvent(
            id = "second-0130",
            start = "2026-11-01T06:30:00Z",
            end = "2026-11-01T07:30:00Z",
        )
        val date = LocalDate.of(2026, 11, 1)

        val events = eventsForLocalDate(listOf(second, first), date, newYork)

        assertEquals(listOf("first-0130", "second-0130"), events.map { it.id })
        assertEquals(
            "01:30",
            Instant.parse(first.dtStart).atZone(newYork).toLocalTime().toString().take(5),
        )
        assertEquals(
            "01:30",
            Instant.parse(second.dtStart).atZone(newYork).toLocalTime().toString().take(5),
        )
    }

    @Test
    fun offsetTimestampsAreAcceptedForTimedEvents() {
        val event = timedEvent(
            id = "offset",
            start = "2026-11-01T01:30:00-04:00",
            end = "2026-11-01T02:30:00-04:00",
        )

        assertEquals(
            listOf("offset"),
            eventsForLocalDate(listOf(event), LocalDate.of(2026, 11, 1), newYork).map { it.id },
        )
        assertEquals("2026-11-01T05:30:00Z", eventStartInstant(event).toString())
    }

    @Test
    fun allDayEventsKeepTheirSerializedLocalDateAcrossOffsetsAndDst() {
        val allDay = event(
            id = "all-day",
            start = "2026-03-08T00:00:00Z",
            end = "2026-03-10T00:00:00Z",
            isAllDay = true,
        )

        val newYorkDates = requireNotNull(allDay.nativeCalendarDates(newYork))
        val tokyoDates = requireNotNull(
            allDay.nativeCalendarDates(ZoneId.of("Asia/Tokyo"))
        )

        assertEquals(LocalDate.of(2026, 3, 8), newYorkDates.start)
        assertEquals(LocalDate.of(2026, 3, 10), newYorkDates.endExclusive)
        assertEquals(newYorkDates, tokyoDates)
        assertTrue(eventsForLocalDate(listOf(allDay), LocalDate.of(2026, 3, 9), newYork).isNotEmpty())
        assertFalse(eventsForLocalDate(listOf(allDay), LocalDate.of(2026, 3, 10), newYork).isNotEmpty())
    }

    @Test
    fun allDayEventsUseTheirOwnDateBoundaryInsteadOfTheDisplayZone() {
        val allDay = event(
            id = "all-day-shanghai",
            start = "2026-03-08T00:00:00+08:00",
            end = "2026-03-10T00:00:00+08:00",
            isAllDay = true,
        )

        val newYorkDates = requireNotNull(allDay.nativeCalendarDates(newYork))

        assertEquals(LocalDate.of(2026, 3, 8), newYorkDates.start)
        assertEquals(LocalDate.of(2026, 3, 10), newYorkDates.endExclusive)
        assertTrue(
            eventsForLocalDate(listOf(allDay), LocalDate.of(2026, 3, 8), newYork).isNotEmpty(),
        )
        assertFalse(
            eventsForLocalDate(listOf(allDay), LocalDate.of(2026, 3, 10), newYork).isNotEmpty(),
        )
    }

    @Test
    fun allDayEventsWithoutAnExplicitEndCoverOnlyTheirStartDate() {
        val allDay = event(
            id = "single-all-day",
            start = "2026-12-31T00:00:00+08:00",
            end = "2026-12-31T00:00:00+08:00",
            isAllDay = true,
        )

        val dates = requireNotNull(allDay.nativeCalendarDates(ZoneId.of("Asia/Shanghai")))

        assertEquals(LocalDate.of(2026, 12, 31), dates.start)
        assertEquals(LocalDate.of(2027, 1, 1), dates.endExclusive)
    }

    @Test
    fun recurringOccurrencesFollowTheirLocalStartOnBothSidesOfDst() {
        val spring = event(
            id = "spring-occurrence",
            start = "2026-03-08T14:00:00Z",
            end = "2026-03-08T15:00:00Z",
        ).copy(rRule = "FREQ=WEEKLY", recurrenceId = "2026-03-08T14:00:00Z")
        val fall = event(
            id = "fall-occurrence",
            start = "2026-11-01T14:00:00Z",
            end = "2026-11-01T15:00:00Z",
        ).copy(rRule = "FREQ=WEEKLY", recurrenceId = "2026-11-01T14:00:00Z")

        assertEquals(
            listOf("spring-occurrence"),
            eventsForLocalDate(listOf(spring), LocalDate.of(2026, 3, 8), newYork).map { it.id },
        )
        assertEquals(
            "10:00",
            Instant.parse(spring.dtStart).atZone(newYork).toLocalTime().toString().take(5),
        )
        assertEquals(
            listOf("fall-occurrence"),
            eventsForLocalDate(listOf(fall), LocalDate.of(2026, 11, 1), newYork).map { it.id },
        )
        assertEquals(
            "09:00",
            Instant.parse(fall.dtStart).atZone(newYork).toLocalTime().toString().take(5),
        )
    }

    @Test
    fun timedEventCrossingMidnightAppearsOnBothLocalDates() {
        val event = timedEvent(
            id = "overnight",
            start = "2026-11-01T23:30:00Z",
            end = "2026-11-02T00:30:00Z",
        )
        val range = NativeCalendarDateRange(
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 3),
        )

        val grouped = calendarEventsByDate(listOf(event), range, ZoneId.of("UTC"))

        assertEquals(listOf("overnight"), grouped[LocalDate.of(2026, 11, 1)]?.map { it.id })
        assertEquals(listOf("overnight"), grouped[LocalDate.of(2026, 11, 2)]?.map { it.id })
    }

    @Test
    fun recurringOccurrencesAreGroupedByServerExpandedStartWithoutLocalRruleExpansion() {
        val first = event(
            id = "occurrence-1",
            start = "2026-11-01T09:00:00Z",
            end = "2026-11-01T10:00:00Z",
        ).copy(rRule = "FREQ=DAILY", recurrenceId = "2026-11-01T09:00:00Z")
        val second = event(
            id = "occurrence-2",
            start = "2026-11-02T09:00:00Z",
            end = "2026-11-02T10:00:00Z",
        ).copy(rRule = "FREQ=DAILY", recurrenceId = "2026-11-02T09:00:00Z")

        val range = NativeCalendarDateRange(
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 5),
        )
        val grouped = calendarEventsByDate(listOf(second, first), range, ZoneId.of("UTC"))

        assertEquals(listOf("occurrence-1"), grouped[LocalDate.of(2026, 11, 1)]?.map { it.id })
        assertEquals(listOf("occurrence-2"), grouped[LocalDate.of(2026, 11, 2)]?.map { it.id })
        assertTrue(grouped[LocalDate.of(2026, 11, 3)].orEmpty().isEmpty())
    }

    @Test
    fun queryWindowUsesLocalMidnightAndIncludesBothDstTransitions() {
        val spring = nativeWorkbenchRange(
            LocalDate.of(2026, 3, 8),
            LocalDate.of(2026, 3, 9),
            newYork,
        )
        val fall = nativeWorkbenchRange(
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2),
            newYork,
        )

        assertEquals(Duration.ofHours(23), Duration.between(
            Instant.parse(spring.start),
            Instant.parse(spring.endExclusive),
        ))
        assertEquals(Duration.ofHours(25), Duration.between(
            Instant.parse(fall.start),
            Instant.parse(fall.endExclusive),
        ))
    }

    private fun timedEvent(id: String, start: String, end: String) = event(
        id = id,
        start = start,
        end = end,
    )

    private fun event(
        id: String,
        start: String,
        end: String,
        isAllDay: Boolean = false,
    ) = EventResponse(
        calendarId = "cal-1",
        dtEnd = end,
        dtStart = start,
        id = id,
        isAllDay = isAllDay,
        source = "local",
        status = "CONFIRMED",
        title = id,
        uid = "uid-$id",
    )
}

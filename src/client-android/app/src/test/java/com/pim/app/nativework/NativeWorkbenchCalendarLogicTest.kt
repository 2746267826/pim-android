package com.pim.app.nativework

import com.pim.core.contract.EventResponse
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchCalendarLogicTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun dayWeekMonthAndListRangesShareTheSelectedDate() {
        val selected = LocalDate.of(2026, 11, 24)

        val day = calendarRangeFor(CalendarViewMode.Day, selected, ZoneOffset.UTC)
        val week = calendarRangeFor(CalendarViewMode.Week, selected, ZoneOffset.UTC)
        val month = calendarRangeFor(CalendarViewMode.Month, selected, ZoneOffset.UTC)
        val list = calendarRangeFor(CalendarViewMode.List, selected, ZoneOffset.UTC)

        assertEquals("2026-11-24T00:00:00Z", day.start)
        assertEquals("2026-11-25T00:00:00Z", day.endExclusive)
        assertEquals("2026-11-23T00:00:00Z", week.start)
        assertEquals("2026-11-30T00:00:00Z", week.endExclusive)
        assertEquals("2026-10-26T00:00:00Z", month.start)
        assertEquals("2026-12-07T00:00:00Z", month.endExclusive)
        assertEquals("2026-11-24T00:00:00Z", list.start)
        assertEquals("2026-12-01T00:00:00Z", list.endExclusive)
    }

    @Test
    fun monthNavigationClampsJanuary31ToTargetMonthEnd() {
        assertEquals(
            LocalDate.of(2027, 2, 28),
            moveCalendarDate(CalendarViewMode.Month, LocalDate.of(2027, 1, 31), 1),
        )
        assertEquals(
            LocalDate.of(2028, 2, 29),
            moveCalendarDate(CalendarViewMode.Month, LocalDate.of(2028, 1, 31), 1),
        )
        assertEquals(
            LocalDate.of(2026, 2, 28),
            moveCalendarDate(CalendarViewMode.Month, LocalDate.of(2026, 3, 31), -1),
        )
        assertEquals(
            LocalDate.of(2027, 1, 31),
            moveCalendarDate(CalendarViewMode.Month, LocalDate.of(2026, 12, 31), 1),
        )
    }

    @Test
    fun weekAndDayNavigationPreserveTheSelectedWeekday() {
        val tuesday = LocalDate.of(2026, 11, 24)

        assertEquals(
            LocalDate.of(2026, 12, 1),
            moveCalendarDate(CalendarViewMode.Week, tuesday, 1),
        )
        assertEquals(
            LocalDate.of(2026, 11, 25),
            moveCalendarDate(CalendarViewMode.Day, tuesday, 1),
        )
        assertEquals(
            LocalDate.of(2026, 12, 1),
            moveCalendarDate(CalendarViewMode.List, tuesday, 1),
        )
    }

    @Test
    fun monthGridContainsCompleteWeeksIncludingAdjacentMonths() {
        val dates = calendarGridDates(LocalDate.of(2026, 11, 15))

        assertEquals(42, dates.size)
        assertEquals(LocalDate.of(2026, 10, 26), dates.first())
        assertEquals(LocalDate.of(2026, 12, 6), dates.last())
        assertTrue(dates.contains(LocalDate.of(2026, 11, 1)))
        assertTrue(dates.contains(LocalDate.of(2026, 11, 30)))
    }

    @Test
    fun allDayEventUsesItsEventZoneLocalDate() {
        val event = event(
            id = "all-day",
            dtStart = "2026-11-14T16:00:00Z",
            dtEnd = "2026-11-16T16:00:00Z",
            isAllDay = true,
            timeZoneId = "Asia/Shanghai",
        )

        val placement = calendarEventPlacement(event, ZoneOffset.UTC)

        assertNotNull(placement)
        assertEquals(LocalDate.of(2026, 11, 15), placement!!.startDate)
        assertEquals(LocalDate.of(2026, 11, 17), placement.endDateExclusive)
        assertTrue(placement.zoneResolved)
        assertTrue(placement.covers(LocalDate.of(2026, 11, 15)))
        assertTrue(placement.covers(LocalDate.of(2026, 11, 16)))
        assertFalse(placement.covers(LocalDate.of(2026, 11, 17)))
    }

    @Test
    fun allDayEventWithoutResolvableZoneFallsBackToTheRecordedOffsetDate() {
        val event = event(
            id = "offset-all-day",
            dtStart = "2026-11-15T00:00:00+08:00",
            dtEnd = "2026-11-16T00:00:00+08:00",
            isAllDay = true,
            timeZoneId = null,
        )

        val placement = calendarEventPlacement(event, ZoneOffset.UTC)

        assertNotNull(placement)
        assertEquals(LocalDate.of(2026, 11, 15), placement!!.startDate)
        assertEquals(LocalDate.of(2026, 11, 16), placement.endDateExclusive)
        assertFalse(placement.zoneResolved)
        assertFalse(placement.covers(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun eventsOnDatePutAllDayItemsFirstAndKeepTimedOrder() {
        val allDay = event(
            id = "all-day",
            dtStart = "2026-11-23T16:00:00Z",
            dtEnd = "2026-11-24T16:00:00Z",
            isAllDay = true,
            timeZoneId = "Asia/Shanghai",
        )
        val late = event(
            id = "late",
            dtStart = "2026-11-24T10:00:00Z",
            dtEnd = "2026-11-24T11:00:00Z",
        )
        val early = event(
            id = "early",
            dtStart = "2026-11-24T01:00:00Z",
            dtEnd = "2026-11-24T02:00:00Z",
        )

        val placements = eventsForCalendarDate(
            listOf(late, allDay, early),
            LocalDate.of(2026, 11, 24),
            shanghai,
        )

        assertEquals(listOf("all-day", "early", "late"), placements.map { it.event.id })
    }

    @Test
    fun midnightEndDoesNotLeakIntoTheNextDay() {
        val placement = calendarEventPlacement(
            event(
                id = "midnight",
                dtStart = "2026-11-24T16:00:00Z",
                dtEnd = "2026-11-25T16:00:00Z",
                isAllDay = true,
                timeZoneId = "Asia/Shanghai",
            ),
            shanghai,
        )

        assertNotNull(placement)
        assertTrue(placement!!.covers(LocalDate.of(2026, 11, 25)))
        assertFalse(placement.covers(LocalDate.of(2026, 11, 26)))
    }

    @Test
    fun datePickerMillisRoundTripsAsUtcLocalDate() {
        val date = LocalDate.of(2027, 1, 31)

        assertEquals(date, calendarDateFromPickerMillis(calendarPickerMillis(date)))
    }

    @Test
    fun invalidEventTimestampCannotBePlaced() {
        assertNull(
            calendarEventPlacement(
                event(id = "bad", dtStart = "not-a-date", dtEnd = "not-a-date"),
                shanghai,
            )
        )
    }

    private fun event(
        id: String,
        dtStart: String,
        dtEnd: String,
        isAllDay: Boolean? = null,
        timeZoneId: String? = null,
    ) = EventResponse(
        calendarId = "cal-1",
        dtEnd = dtEnd,
        dtStart = dtStart,
        id = id,
        isAllDay = isAllDay,
        source = "local",
        status = "CONFIRMED",
        timeZoneId = timeZoneId,
        title = id,
        uid = "uid-$id",
    )
}

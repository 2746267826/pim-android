package com.pim.app.notes

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTaskSuggestionEventTimeTest {
    @Test
    fun ianaZoneIdsAreAcceptedAndNonIanaIdsFailClosed() {
        assertTrue(isIanaTimeZone("Asia/Shanghai"))
        assertTrue(isIanaTimeZone("America/New_York"))
        assertFalse(isIanaTimeZone(null))
        assertFalse(isIanaTimeZone(""))
        assertFalse(isIanaTimeZone("China Standard Time"))
        assertFalse(isIanaTimeZone("Pacific Standard Time"))
        assertFalse(isIanaTimeZone("+08:00"))
        assertFalse(isIanaTimeZone("UTC+8"))
        assertFalse(isIanaTimeZone("Mars/Olympus"))
    }

    @Test
    fun wallClockConversionUsesTheDeclaredIanaZoneNotTheDeviceZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(
                "2026-11-21T11:00:00Z",
                suggestionEventWire("2026-11-21T19:00", "Asia/Shanghai"),
            )
            assertEquals(
                "2026-11-21T19:00",
                suggestionEventInput("2026-11-21T11:00:00Z", "Asia/Shanghai"),
            )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun dstGapAndAmbiguousWallClockTimesFailClosed() {
        val gap = runCatching {
            suggestionEventWire("2026-03-08T02:30", "America/New_York")
        }.exceptionOrNull()
        val ambiguous = runCatching {
            suggestionEventWire("2026-11-01T01:30", "America/New_York")
        }.exceptionOrNull()

        assertTrue(gap?.message.orEmpty().contains("不存在"))
        assertTrue(ambiguous?.message.orEmpty().contains("重复"))
    }

    @Test
    fun invalidCalendarDatesFailClosedInsteadOfRollingOver() {
        val invalid = runCatching {
            suggestionEventWire("2026-02-30T10:00", "Asia/Shanghai")
        }.exceptionOrNull()

        assertTrue(invalid is java.time.format.DateTimeParseException)
    }

    @Test
    fun eventRangeRenderingUsesTheEventZone() {
        assertEquals(
            "2026-11-21 19:00–20:00 · Asia/Shanghai",
            formatEventRange(
                "2026-11-21T11:00:00Z",
                "2026-11-21T12:00:00Z",
                "Asia/Shanghai",
            ),
        )
        assertTrue(
            formatEventRange("2026-11-21T11:00:00Z", "2026-11-21T12:00:00Z", null)
                .contains("时区未知"),
        )
    }
}

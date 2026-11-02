package com.pim.app.nativework

import com.pim.core.contract.EventResponse
import com.pim.core.contract.TaskResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchTodayEditorTest {
    @Test
    fun unchangedTaskDraftIsNotDirtyAndEditsAreDetected() {
        val task = TaskResponse(
            id = "task-1",
            isInbox = false,
            priority = 3,
            sortOrder = 0,
            status = "NEEDS-ACTION",
            subTasks = emptyList(),
            title = "Plan",
            uid = "task-uid",
        )

        assertFalse(taskEditorHasChanges(task, "Plan", "", "3", "", "", null))
        assertTrue(taskEditorHasChanges(task, "Plan revised", "", "3", "", "", null))
    }

    @Test
    fun unchangedEventDraftIsNotDirtyAndEditsAreDetected() {
        val event = EventResponse(
            calendarId = "calendar-1",
            dtEnd = "2026-11-03T11:00:00Z",
            dtStart = "2026-11-03T10:00:00Z",
            id = "event-1",
            source = "local",
            status = "confirmed",
            title = "Review",
            uid = "event-uid",
        )
        val start = localDateTime("2026-11-03T10:00:00Z")
        val end = localDateTime("2026-11-03T11:00:00Z")

        assertFalse(
            eventEditorHasChanges(
                event, "calendar-1", "Review", "", start, end, "",
                "calendar-1", start, end,
            )
        )
        assertTrue(
            eventEditorHasChanges(
                event, "calendar-1", "Review updated", "", start, end, "",
                "calendar-1", start, end,
            )
        )
    }

    @Test
    fun newEventDefaultsAreCleanUntilUserChangesAField() {
        assertFalse(
            eventEditorHasChanges(
                event = null,
                calendarId = "calendar-1",
                title = "",
                description = "",
                start = "2026-11-03T10:00",
                end = "2026-11-03T11:00",
                location = "",
                initialCalendarId = "calendar-1",
                initialStart = "2026-11-03T10:00",
                initialEnd = "2026-11-03T11:00",
            )
        )
        assertTrue(
            eventEditorHasChanges(
                event = null,
                calendarId = "calendar-1",
                title = "Review",
                description = "",
                start = "2026-11-03T10:00",
                end = "2026-11-03T11:00",
                location = "",
                initialCalendarId = "calendar-1",
                initialStart = "2026-11-03T10:00",
                initialEnd = "2026-11-03T11:00",
            )
        )
    }

    private fun localDateTime(utc: String): String =
        java.time.LocalDateTime.ofInstant(
            java.time.Instant.parse(utc),
            java.time.ZoneId.systemDefault(),
        ).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
}

package com.pim.app.nativework

import com.pim.app.nativework.quicknotes.legacyQuickNoteUpdateRequest
import com.pim.core.contract.EventAttendeeDto
import com.pim.core.contract.EventResponse
import com.pim.core.contract.QuickNoteAttachmentDto
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.TaskResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NativeWorkbenchMappingTest {
    @Test
    fun taskEditPreservesReplacementOnlyFields() {
        val task = task()
        val request = taskUpdateRequest(
            task,
            NativeTaskDraft(
                title = "新标题",
                description = "新描述",
                priority = 1,
                due = "2026-11-02T09:00:00Z",
                dtStart = null,
            )
        )

        assertEquals("新标题", request.title)
        assertEquals("新描述", request.description)
        assertEquals(1, request.priority)
        assertEquals("cal-1", request.calendarId)
        assertEquals("PT1H", request.estimatedDuration)
        assertEquals("PT15M", request.minimumSegment)
        assertEquals("book-1", request.taskBookId)
        assertEquals("2026-11-01T12:00:00Z", request.plannedEnd)
        assertEquals("NEEDS-ACTION", request.status)
        assertEquals(30, request.percentComplete)
    }

    @Test
    fun completingTaskDoesNotErasePlanningMetadata() {
        val request = taskUpdateRequest(
            task = task(),
            status = "COMPLETED",
            percentComplete = 100,
        )

        assertEquals("COMPLETED", request.status)
        assertEquals(100, request.percentComplete)
        assertEquals("PT1H", request.estimatedDuration)
        assertEquals("book-1", request.taskBookId)
    }

    @Test
    fun taskEditCanMoveTaskIntoSelectedTaskBook() {
        val request = taskUpdateRequest(
            task = task(),
            draft = NativeTaskDraft(
                title = "任务",
                priority = 2,
                due = "2026-11-01T11:00:00Z",
                dtStart = "2026-11-01T09:00:00Z",
                taskBookId = "book-2",
            ),
        )

        assertEquals("book-2", request.taskBookId)
    }

    @Test
    fun eventEditPreservesRecurrenceAttendeesAndOutlookIdentity() {
        val event = event()
        val request = eventUpdateRequest(
            event,
            NativeEventDraft(
                calendarId = "cal-1",
                title = "改后日程",
                description = "说明",
                dtStart = "2026-11-02T09:00:00Z",
                dtEnd = "2026-11-02T10:00:00Z",
                location = "教室",
            )
        )

        assertEquals("改后日程", request.title)
        assertEquals("教室", request.location)
        assertEquals("FREQ=WEEKLY", request.rRule)
        assertEquals(listOf("课程"), request.categories)
        assertEquals("student@example.test", request.attendees?.single()?.email)
        assertEquals("outlook-event-id", request.onlineMeetingProvider)
        assertEquals("source-uid", request.uid)
    }

    @Test
    fun quickNoteEditKeepsAttachmentIdsAndDoesNotInventMetadataField() {
        val detail = quickNote()
        val request = legacyQuickNoteUpdateRequest(detail, "新的正文")

        assertEquals("新的正文", request.contentMarkdown)
        assertEquals(listOf("attachment-1", "attachment-2"), request.attachmentIds)
        assertEquals("inbox", request.status)
        assertTrue(detail.metadataJson.contains("legacyHtml"))
    }

    @Test
    fun dateTimeInputAcceptsContractInstantAndLocalEditorFormat() {
        assertEquals("2026-11-01T01:02:03Z", normalizeDateTimeInput("2026-11-01T01:02:03Z"))
        assertNotNull(normalizeDateTimeInput("2026-11-01T09:30"))
        assertNull(normalizeDateTimeInput("not-a-date"))
        assertNull(normalizeDateTimeInput(" "))
    }

    @Test
    fun todayFilterUsesBusinessRelevantTaskDatesAndExcludesCompleted() {
        val dueToday = task().copy(id = "due", due = "2026-11-01T11:00:00Z", dtStart = null)
        val plannedToday = task().copy(id = "planned", due = null, dtStart = "2026-11-01T09:00:00Z")
        val completed = dueToday.copy(id = "done", status = "COMPLETED")
        val tomorrow = dueToday.copy(id = "tomorrow", due = "2026-11-02T11:00:00Z")

        val result = todayTasks(
            listOf(dueToday, plannedToday, completed, tomorrow),
            LocalDate.of(2026, 11, 1),
            ZoneOffset.UTC,
        )

        assertEquals(listOf("due", "planned"), result.map { it.id })
    }

    @Test
    fun todayEventCountIncludesOnlyEventsStartingOnRequestedDate() {
        val today = LocalDate.of(2026, 11, 1)
        val events = listOf(
            event().copy(id = "today", dtStart = "2026-11-01T09:00:00Z"),
            event().copy(id = "tomorrow", dtStart = "2026-11-02T09:00:00Z"),
            event().copy(id = "invalid", dtStart = "not-a-date"),
        )

        assertEquals(1, eventCountOnDate(events, today, ZoneOffset.UTC))
    }

    private fun task() = TaskResponse(
        calendarId = "cal-1",
        description = "原描述",
        dtStart = "2026-11-01T09:00:00Z",
        due = "2026-11-01T11:00:00Z",
        estimatedDuration = "PT1H",
        id = "task-1",
        isInbox = false,
        minimumSegment = "PT15M",
        percentComplete = 30,
        plannedEnd = "2026-11-01T12:00:00Z",
        priority = 2,
        sortOrder = 4,
        status = "NEEDS-ACTION",
        subTasks = emptyList(),
        taskBookId = "book-1",
        title = "原任务",
        uid = "task-uid",
    )

    private fun event() = EventResponse(
        attendees = listOf(EventAttendeeDto(email = "student@example.test", name = "学生")),
        calendarId = "cal-1",
        categories = listOf("课程"),
        dtEnd = "2026-11-01T10:00:00Z",
        dtStart = "2026-11-01T09:00:00Z",
        id = "event-1",
        onlineMeetingProvider = "outlook-event-id",
        rRule = "FREQ=WEEKLY",
        source = "local",
        status = "CONFIRMED",
        title = "原日程",
        uid = "source-uid",
    )

    private fun quickNote() = QuickNoteDetailDto(
        attachments = listOf(
            QuickNoteAttachmentDto(
                contentType = "text/html",
                createdAt = "2026-10-01T00:00:00Z",
                downloadUrl = "/attachment-1",
                fileName = "legacy.html",
                id = "attachment-1",
                sizeBytes = 100,
            ),
            QuickNoteAttachmentDto(
                contentType = "image/png",
                createdAt = "2026-10-01T00:00:00Z",
                downloadUrl = "/attachment-2",
                fileName = "image.png",
                id = "attachment-2",
                sizeBytes = 200,
            ),
        ),
        contentMarkdown = "旧正文",
        createdAt = "2026-10-01T00:00:00Z",
        id = "note-1",
        metadataJson = """{"legacyHtml":"<p>旧正文</p>","identity":"quick-1"}""",
        source = "quick-html",
        status = "inbox",
        updatedAt = "2026-10-02T00:00:00Z",
    )
}

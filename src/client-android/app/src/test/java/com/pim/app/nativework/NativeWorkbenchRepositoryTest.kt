package com.pim.app.nativework

import com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository
import com.pim.core.contract.CreateTaskRequest
import com.pim.core.contract.CreateCalendarRequest
import com.pim.core.contract.CreateQuickNoteRequest
import com.pim.core.contract.CreateTaskBookRequest
import com.pim.core.contract.CalendarResponse
import com.pim.core.contract.CalendarResponseApiResponse
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.EventResponse
import com.pim.core.contract.EventResponseApiResponse
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.QuickNoteDetailDtoApiResponse
import com.pim.core.contract.QuickNoteListItemDtoPagedResult
import com.pim.core.contract.QuickNoteListItemDtoPagedResultApiResponse
import com.pim.core.contract.ReminderResponse
import com.pim.core.contract.ReminderResponseApiResponse
import com.pim.core.contract.ReminderResponseIReadOnlyListApiResponse
import com.pim.core.contract.RestoreQuickNoteRequest
import com.pim.core.contract.StringApiResponse
import com.pim.core.contract.TaskResponse
import com.pim.core.contract.TaskResponseApiResponse
import com.pim.core.contract.TaskResponsePagedResult
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.contract.TaskBookDto
import com.pim.core.contract.TaskBookDtoApiResponse
import com.pim.core.contract.UpdateQuickNoteRequest
import com.pim.core.contract.UpdateTaskRequest
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeWorkbenchRepositoryTest {
    @Test
    fun taskListSendsBoundedPageAndTrimmedSearch() = runBlocking {
        val calls = mutableListOf<List<Any?>>()
        val api = fakeApi { method, args ->
            when (method) {
                "getTasksPaged" -> {
                    calls += args.dropLast(1)
                    taskPage()
                }
                else -> error("Unexpected $method")
            }
        }

        NativeWorkbenchRepository(api).loadTasks("  设计  ")

        assertEquals(listOf(null, "设计", null, null, null, null, null, null, null, 1, 100), calls.single())
    }

    @Test
    fun createTaskSendsExplicitNeedsActionAndTrimsOptionalFields() = runBlocking {
        var request: CreateTaskRequest? = null
        val api = fakeApi { method, args ->
            when (method) {
                "createTask" -> {
                    request = args[0] as CreateTaskRequest
                    TaskResponseApiResponse(0, task(), "ok", "now")
                }
                else -> error("Unexpected $method")
            }
        }

        NativeWorkbenchRepository(api).createTask(
            NativeTaskDraft(title = " 新任务 ", description = " ", priority = 2)
        )

        assertEquals("新任务", request?.title)
        assertNull(request?.description)
        assertEquals("NEEDS-ACTION", request?.status)
        assertEquals(2, request?.priority)
        assertNull(request?.taskBookId)
    }

    @Test
    fun updateTaskReusesCompleteReplacementPayload() = runBlocking {
        var request: UpdateTaskRequest? = null
        val original = task()
        val api = fakeApi { method, args ->
            when (method) {
                "updateTask" -> {
                    request = args[1] as UpdateTaskRequest
                    TaskResponseApiResponse(0, original, "ok", "now")
                }
                else -> error("Unexpected $method")
            }
        }

        NativeWorkbenchRepository(api).updateTask(
            original,
            NativeTaskDraft(title = "改后", priority = 1, due = null, dtStart = null)
        )

        assertEquals("PT1H", request?.estimatedDuration)
        assertEquals("book-1", request?.taskBookId)
        assertEquals("cal-1", request?.calendarId)
        assertEquals(1, request?.priority)
    }

    @Test
    fun eventMutationsRejectOutlookAndRecurringRecordsBeforeCallingApi() = runBlocking {
        val calls = mutableListOf<String>()
        val api = fakeApi { method, _ ->
            calls += method
            error("Mutation must not reach the API")
        }
        val repository = NativeWorkbenchRepository(api)
        val draft = NativeEventDraft(
            calendarId = "cal-1",
            title = "改后日程",
            dtStart = "2026-11-02T09:00:00Z",
            dtEnd = "2026-11-02T10:00:00Z",
        )

        assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                repository.updateEvent(
                    event().copy(source = "local", rRule = null, outlookEventId = "mirror-1"),
                    draft
                )
            }
        }
        assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                repository.deleteEvent(event())
            }
        }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun eventTimesAreComparedAsInstantsNotOffsets() {
        runBlocking {
            val api = fakeApi { method, _ ->
                when (method) {
                    "createEvent" -> EventResponseApiResponse(
                        0,
                        event().copy(rRule = null),
                        "ok",
                        "now"
                    )
                    else -> error("Unexpected $method")
                }
            }

            NativeWorkbenchRepository(api).createEvent(
                NativeEventDraft(
                    calendarId = "cal-1",
                    title = "跨时区日程",
                    dtStart = "2026-11-02T09:00:00+08:00",
                    dtEnd = "2026-11-02T02:00:00Z",
                )
            )
        }
    }

    @Test
    fun taskBookAndCalendarCreatesUseOnlyDeclaredFields() = runBlocking {
        var taskBookRequest: CreateTaskBookRequest? = null
        var calendarRequest: CreateCalendarRequest? = null
        val api = fakeApi { method, args ->
            when (method) {
                "createTaskBook" -> {
                    taskBookRequest = args[0] as CreateTaskBookRequest
                    TaskBookDtoApiResponse(
                        0,
                        TaskBookDto(id = "book", name = "工作", kind = "task", status = "active"),
                        "ok",
                        "now"
                    )
                }
                "createCalendar" -> {
                    calendarRequest = args[0] as CreateCalendarRequest
                    CalendarResponseApiResponse(
                        0,
                        CalendarResponse(
                            color = "#2563EB",
                            eventCount = 0,
                            id = "cal",
                            isDefault = false,
                            kind = "calendar",
                            name = "课程",
                        ),
                        "ok",
                        "now"
                    )
                }
                else -> error("Unexpected $method")
            }
        }
        val repository = NativeWorkbenchRepository(api)

        repository.createTaskBook(" 工作 ")
        repository.createCalendar(" 课程 ", "#2563EB")

        assertEquals("工作", taskBookRequest?.name)
        assertNull(taskBookRequest?.kind)
        assertNull(taskBookRequest?.status)
        assertEquals("课程", calendarRequest?.name)
        assertEquals("#2563EB", calendarRequest?.color)
        assertNull(calendarRequest?.kind)
    }

    @Test
    fun calendarPickerExcludesReadOnlySources() = runBlocking {
        val api = fakeApi { method, _ ->
            when (method) {
                "getCalendars" -> CalendarResponseListApiResponse(
                    0,
                    listOf(
                        CalendarResponse(
                            canEdit = true,
                            color = "#2563EB",
                            eventCount = 0,
                            id = "writable",
                            isDefault = true,
                            kind = "calendar",
                            name = "本地",
                        ),
                        CalendarResponse(
                            canEdit = false,
                            color = "#7C3AED",
                            eventCount = 2,
                            id = "mirror",
                            isDefault = false,
                            kind = "calendar",
                            name = "Outlook",
                        ),
                    ),
                    "ok",
                    "now",
                )
                else -> error("Unexpected $method")
            }
        }

        assertEquals(listOf("writable"), NativeWorkbenchRepository(api).loadCalendars().map { it.id })
    }

    @Test
    fun quickNotePagingIsBoundedAndUpdateKeepsAttachments() = runBlocking {
        var pageArgs: List<Any?>? = null
        var create: CreateQuickNoteRequest? = null
        var update: UpdateQuickNoteRequest? = null
        val detail = quickNote().copy(source = "manual", metadataJson = "{}")
        val api = fakeApi { method, args ->
            when (method) {
                "getQuickNotes" -> {
                    pageArgs = args.dropLast(1)
                    QuickNoteListItemDtoPagedResultApiResponse(
                        0,
                        QuickNoteListItemDtoPagedResult(emptyList(), 1, 50, 0, 0),
                        "ok",
                        "now"
                    )
                }
                "createQuickNote" -> {
                    create = args[0] as CreateQuickNoteRequest
                    QuickNoteDetailDtoApiResponse(0, detail, "ok", "now")
                }
                "getQuickNote" -> QuickNoteDetailDtoApiResponse(0, detail, "ok", "now")
                "updateQuickNote" -> {
                    update = args[1] as UpdateQuickNoteRequest
                    QuickNoteDetailDtoApiResponse(0, detail, "ok", "now")
                }
                else -> error("Unexpected $method")
            }
        }
        val repository = LegacyQuickNoteRepository(api)

        repository.create("新记录")
        repository.load("旧")
        val loaded = repository.get(detail.id)
        repository.update(loaded, "改后正文")

        assertEquals(listOf(null, "旧", 1, 50), pageArgs)
        assertEquals("新记录", create?.contentMarkdown)
        assertNull(create?.source)
        assertEquals(listOf("attachment-1"), update?.attachmentIds)
        assertEquals("改后正文", update?.contentMarkdown)
        assertEquals("inbox", update?.status)
    }

    @Test
    fun legacyQuickNoteLifecycleUsesDedicatedContractActions() = runBlocking {
        val detail = quickNote()
        val methods = mutableListOf<String>()
        var restoreRequest: RestoreQuickNoteRequest? = null
        val api = fakeApi { method, args ->
            methods += method
            when (method) {
                "archiveQuickNote" -> QuickNoteDetailDtoApiResponse(
                    0,
                    detail.copy(status = "archived"),
                    "ok",
                    "now",
                )
                "processQuickNote" -> QuickNoteDetailDtoApiResponse(
                    0,
                    detail.copy(status = "processed"),
                    "ok",
                    "now",
                )
                "restoreQuickNote" -> {
                    restoreRequest = args[1] as RestoreQuickNoteRequest
                    QuickNoteDetailDtoApiResponse(0, detail, "ok", "now")
                }
                "deleteQuickNote" -> StringApiResponse(0, "deleted", "ok", "now")
                else -> error("Unexpected $method")
            }
        }
        val repository = LegacyQuickNoteRepository(api)

        repository.archive(detail)
        repository.process(detail)
        repository.restore(detail)
        repository.delete(detail)

        assertEquals(
            listOf("archiveQuickNote", "processQuickNote", "restoreQuickNote", "deleteQuickNote"),
            methods,
        )
        assertEquals("inbox", restoreRequest?.status)
    }

    @Test
    fun reminderListMapsTheCompleteContractPayload() = runBlocking {
        val reminder = reminder()
        val api = fakeApi { method, _ ->
            when (method) {
                "getReminders" -> ReminderResponseIReadOnlyListApiResponse(
                    0,
                    listOf(reminder),
                    "ok",
                    "now",
                )
                else -> error("Unexpected $method")
            }
        }

        val result = NativeWorkbenchRepository(api).loadReminders().single()

        assertEquals(reminder.id, result.id)
        assertEquals(reminder.title, result.title)
        assertEquals(reminder.scheduledAt, result.scheduledAt)
        assertEquals(reminder.riskLevel, result.riskLevel)
        assertEquals(reminder.relatedObjectType, result.relatedObjectType)
        assertEquals(reminder.relatedObjectId, result.relatedObjectId)
    }

    @Test
    fun reminderActionsCallOnlyTheSelectedContractOperation() = runBlocking {
        val calls = mutableListOf<List<Any?>>()
        val api = fakeApi { method, args ->
            calls += mutableListOf<Any?>(method).apply { addAll(args.dropLast(1)) }
            when (method) {
                "dismissReminder", "snoozeReminder" ->
                    ReminderResponseApiResponse(0, reminder(), "ok", "now")
                else -> error("Unexpected $method")
            }
        }
        val repository = NativeWorkbenchRepository(api)
        val future = Instant.now().plusSeconds(3_600).toString()

        repository.dismissReminder("reminder-1")
        repository.snoozeReminder("reminder-1", future)

        assertEquals(
            listOf(
                listOf("dismissReminder", "reminder-1"),
                listOf("snoozeReminder", "reminder-1", future),
            ),
            calls,
        )
    }

    @Test
    fun reminderSnoozeRejectsPastAndMalformedTimesBeforeCallingApi() = runBlocking {
        val calls = mutableListOf<String>()
        val api = fakeApi { method, _ ->
            calls += method
            error("Snooze must not reach the API")
        }
        val repository = NativeWorkbenchRepository(api)

        assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                repository.snoozeReminder(
                    "reminder-1",
                    Instant.now().minusSeconds(60).toString(),
                )
            }
        }
        assertThrows(NativeWorkbenchRequestException::class.java) {
            runBlocking {
                repository.snoozeReminder("reminder-1", "tomorrow")
            }
        }
        assertEquals(emptyList<String>(), calls)
    }

    private fun fakeApi(handler: (String, List<Any?>) -> Any): ApiService =
        Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "toString" -> "NativeWorkbenchFakeApi"
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                else -> handler(method.name, args?.toList().orEmpty())
            }
        } as ApiService

    private fun taskPage() = TaskResponsePagedResultApiResponse(
        code = 0,
        data = TaskResponsePagedResult(
            items = listOf(task()),
            page = 1,
            pageSize = 100,
            totalCount = 1,
            totalPages = 1,
        ),
        message = "ok",
        timestamp = "now",
    )

    private fun task() = TaskResponse(
        calendarId = "cal-1",
        description = "desc",
        dtStart = null,
        due = null,
        estimatedDuration = "PT1H",
        id = "task-1",
        isInbox = true,
        minimumSegment = "PT15M",
        percentComplete = 0,
        plannedEnd = null,
        priority = 3,
        sortOrder = 0,
        status = "NEEDS-ACTION",
        subTasks = emptyList(),
        taskBookId = "book-1",
        title = "任务",
        uid = "uid",
    )

    private fun quickNote() = QuickNoteDetailDto(
        attachments = listOf(
            com.pim.core.contract.QuickNoteAttachmentDto(
                contentType = "image/png",
                createdAt = "then",
                downloadUrl = "/a",
                fileName = "receipt.png",
                id = "attachment-1",
                sizeBytes = 10,
            )
        ),
        contentMarkdown = "旧",
        createdAt = "then",
        id = "note-1",
        metadataJson = """{"legacyHtml":"<p>旧</p>"}""",
        source = "quick-html",
        status = "inbox",
        updatedAt = "then",
    )

    private fun reminder() = ReminderResponse(
        body = "该开始工作了",
        channels = listOf("in_app"),
        id = "reminder-1",
        relatedObjectId = "task-1",
        relatedObjectType = "task",
        riskLevel = "medium",
        scheduledAt = "2026-11-01T09:00:00Z",
        status = "pending",
        title = "任务提醒",
        triggerReason = "due_soon",
    )

    private fun event() = EventResponse(
        calendarId = "cal-1",
        dtEnd = "2026-11-02T10:00:00Z",
        dtStart = "2026-11-02T09:00:00Z",
        id = "event-1",
        rRule = "FREQ=WEEKLY",
        source = "local",
        status = "CONFIRMED",
        title = "日程",
        uid = "event-uid",
    )
}

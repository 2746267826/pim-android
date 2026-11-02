package com.pim.app.nativework

import com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository
import com.pim.core.contract.HabitRoutineDto
import com.pim.core.contract.HabitRoutineDtoApiResponse
import com.pim.core.contract.HabitRoutineDtoIReadOnlyListApiResponse
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.EventResponsePagedResult
import com.pim.core.contract.EventResponsePagedResultApiResponse
import com.pim.core.contract.MobileUsageSummaryResponse
import com.pim.core.contract.MobileUsageSummaryResponseApiResponse
import com.pim.core.contract.OperationConfirmationDto
import com.pim.core.contract.OperationConfirmationDtoApiResponse
import com.pim.core.contract.OperationConfirmationDtoIReadOnlyListApiResponse
import com.pim.core.contract.TaskResponse
import com.pim.core.contract.TaskBookDtoIReadOnlyListApiResponse
import com.pim.core.contract.TaskResponsePagedResult
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchViewModelSessionTest {
    private val ownerA = "11111111-1111-4111-8111-111111111111"
    private val ownerB = "22222222-2222-4222-8222-222222222222"

    @Test
    fun delayedOwnerAResponseCannotPopulateAfterOwnerSwitch() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            var pendingTasks: Continuation<Any?>? = null
            val ownerAApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getTasksPaged" -> {
                        pendingTasks = args.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    }
                    else -> error("Unexpected owner-A call ${method.name}")
                }
            } as ApiService
            val transport = ControllableTransport(
                current = session(ownerA, generation = 1L),
                api = ownerAApi,
            )
            val unusedApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, _, _ -> error("Template API must not be called") } as ApiService
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(unusedApi),
                legacyQuickNotes = LegacyQuickNoteRepository(unusedApi),
                transport = transport,
            )

            runCurrent()
            assertTrue(pendingTasks != null)

            transport.current = session(ownerB, generation = 2L)
            pendingTasks!!.resumeWith(
                Result.success(
                    TaskResponsePagedResultApiResponse(
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
                )
            )
            runCurrent()

            assertTrue(viewModel.state.value.tasks.isEmpty())
            assertTrue(
                viewModel.state.value.error.orEmpty().contains("账号或服务器已切换")
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun delayedConfirmationDetailCannotPopulateAfterOwnerSwitch() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            var pendingDetail: Continuation<Any?>? = null
            val ownerAApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getPendingOperationConfirmations" ->
                        OperationConfirmationDtoIReadOnlyListApiResponse(
                            code = 0,
                            data = listOf(confirmation("owner-a")),
                            message = "ok",
                            timestamp = "now",
                        )
                    "getOperationConfirmation" -> {
                        pendingDetail = args.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    }
                    else -> error("Unexpected owner-A call ${method.name}")
                }
            } as ApiService
            val transport = ControllableTransport(
                current = session(ownerA, generation = 1L),
                api = ownerAApi,
            )
            val unusedApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, _, _ -> error("Template API must not be called") } as ApiService
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(unusedApi),
                legacyQuickNotes = LegacyQuickNoteRepository(unusedApi),
                transport = transport,
            )

            viewModel.selectSection(NativeWorkbenchSection.Confirmations)
            runCurrent()
            viewModel.openConfirmation("owner-a")
            runCurrent()
            assertTrue(pendingDetail != null)

            transport.current = session(ownerB, generation = 2L)
            pendingDetail!!.resumeWith(
                Result.success(
                    OperationConfirmationDtoApiResponse(
                        code = 0,
                        data = confirmation("owner-a"),
                        message = "ok",
                        timestamp = "now",
                    )
                )
            )
            runCurrent()

            assertTrue(viewModel.state.value.confirmationDetail == null)
            assertTrue(viewModel.state.value.confirmations.isEmpty())
            assertTrue(viewModel.state.value.error.orEmpty().contains("账号或服务器已切换"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun delayedHabitArchiveCannotUpdateStateAfterOwnerSwitch() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            var archiveResponse: Continuation<Any?>? = null
            var archiveCalls = 0
            val ownerAApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getHabits" -> HabitRoutineDtoIReadOnlyListApiResponse(
                        code = 0,
                        data = listOf(habit()),
                        message = "ok",
                        timestamp = "now",
                    )
                    "archiveHabit" -> {
                        archiveCalls += 1
                        archiveResponse = args.last() as Continuation<Any?>
                        COROUTINE_SUSPENDED
                    }
                    else -> error("Unexpected owner-A call ${method.name}")
                }
            } as ApiService
            val transport = ControllableTransport(
                current = session(ownerA, generation = 1L),
                api = ownerAApi,
            )
            val unusedApi = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, _, _ -> error("Template API must not be called") } as ApiService
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(unusedApi),
                legacyQuickNotes = LegacyQuickNoteRepository(unusedApi),
                transport = transport,
            )

            viewModel.selectSection(NativeWorkbenchSection.Habits)
            runCurrent()
            viewModel.archiveHabit(viewModel.state.value.habits.single())
            runCurrent()
            assertTrue(archiveResponse != null)

            transport.current = session(ownerB, generation = 2L)
            archiveResponse!!.resumeWith(
                Result.success(
                    HabitRoutineDtoApiResponse(
                        code = 0,
                        data = habit(status = "archived"),
                        message = "ok",
                        timestamp = "now",
                    )
                )
            )
            runCurrent()

            assertTrue(viewModel.state.value.habits.isEmpty())
            assertTrue(viewModel.state.value.feedback == null)
            assertTrue(viewModel.state.value.error.orEmpty().contains("账号或服务器已切换"))
            assertEquals(1, archiveCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun enteringTodayRestoresTodaySectionAndFiltersTaskListAfterTasksVisit() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val today = java.time.LocalDate.now()
            val todayDue = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
            val tomorrowDue = today.plusDays(1).atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
            val api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getTasksPaged" -> TaskResponsePagedResultApiResponse(
                        code = 0,
                        data = TaskResponsePagedResult(
                            items = listOf(
                                task(id = "today", title = "Due today", due = todayDue),
                                task(id = "later", title = "Due tomorrow", due = tomorrowDue),
                            ),
                            page = 1,
                            pageSize = 100,
                            totalCount = 2,
                            totalPages = 1,
                        ),
                        message = "ok",
                        timestamp = "now",
                    )
                    "getTaskBooks" -> TaskBookDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                    "getEvents" -> EventResponsePagedResultApiResponse(
                        0,
                        EventResponsePagedResult(emptyList(), 1, 100, 0, 0),
                        "ok",
                        "now",
                    )
                    "getCalendars" -> CalendarResponseListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(
                        0,
                        MobileUsageSummaryResponse(
                            appRanking = emptyList(),
                            appSwitchCount = 0,
                            appsUsed = 0,
                            completeness = 1.0,
                            date = today.toString(),
                            fallbackForegroundSeconds = 0,
                            generatedAt = "now",
                            qualityIssueCount = 0,
                            syncBatches = emptyList(),
                            totalForegroundSeconds = 0,
                        ),
                        "ok",
                        "now",
                    )
                    else -> error("Unexpected API call ${method.name}")
                }
            } as ApiService
            val transport = ControllableTransport(session(ownerA, 1L), api)
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(api),
                legacyQuickNotes = LegacyQuickNoteRepository(api),
                transport = transport,
            )

            runCurrent()
            viewModel.selectSection(NativeWorkbenchSection.Tasks)
            runCurrent()
            assertEquals(listOf("today", "later"), viewModel.state.value.tasks.map { it.id })

            viewModel.enterToday()
            assertEquals(NativeWorkbenchSection.Today, viewModel.state.value.section)
            assertTrue("Old all-task list must clear before Today refresh", viewModel.state.value.tasks.isEmpty())
            runCurrent()

            assertEquals(listOf("today"), viewModel.state.value.tasks.map { it.id })

            transport.current = session(ownerB, generation = 2L)
            viewModel.refresh()
            assertTrue("Account-scoped Today rows clear before the new session responds", viewModel.state.value.tasks.isEmpty())
            assertTrue(viewModel.state.value.calendars.isEmpty())
            runCurrent()
            assertEquals(listOf("today"), viewModel.state.value.tasks.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun taskAndEventDraftsCapturedForOldSessionCannotSubmitAfterRefresh() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        try {
            val today = java.time.LocalDate.now()
            val api = Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getTasksPaged" -> TaskResponsePagedResultApiResponse(
                        0,
                        TaskResponsePagedResult(emptyList(), 1, 100, 0, 0),
                        "ok",
                        "now",
                    )
                    "getEvents" -> EventResponsePagedResultApiResponse(
                        0,
                        EventResponsePagedResult(emptyList(), 1, 100, 0, 0),
                        "ok",
                        "now",
                    )
                    "getCalendars" -> CalendarResponseListApiResponse(0, emptyList(), "ok", "now")
                    "getMobileSummary" -> MobileUsageSummaryResponseApiResponse(
                        0,
                        MobileUsageSummaryResponse(
                            appRanking = emptyList(), appSwitchCount = 0, appsUsed = 0,
                            completeness = 1.0, date = today.toString(), fallbackForegroundSeconds = 0,
                            generatedAt = "now", qualityIssueCount = 0, syncBatches = emptyList(),
                            totalForegroundSeconds = 0,
                        ),
                        "ok",
                        "now",
                    )
                    "createTask", "createEvent" -> error("Stale editor must not invoke ${method.name}")
                    else -> error("Unexpected API call ${method.name}")
                }
            } as ApiService
            val transport = ControllableTransport(session(ownerA, 1L), api)
            val viewModel = NativeWorkbenchViewModel(
                repository = NativeWorkbenchRepository(api),
                legacyQuickNotes = LegacyQuickNoteRepository(api),
                transport = transport,
            )
            runCurrent()
            val capturedSession = requireNotNull(viewModel.currentEditorSession())

            transport.current = session(ownerB, 2L)
            viewModel.refresh()
            assertTrue("Old editor scope must be stale", !viewModel.isCurrentEditorSession(capturedSession))
            viewModel.createTask(NativeTaskDraft(title = "private old task"), capturedSession)
            viewModel.createEvent(
                NativeEventDraft(
                    calendarId = "old-calendar",
                    title = "private old event",
                    dtStart = "2026-11-03T10:00:00Z",
                    dtEnd = "2026-11-03T11:00:00Z",
                ),
                capturedSession,
            )

            assertTrue(viewModel.state.value.error.orEmpty().contains("旧草稿已保留且不能提交"))
            runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun session(ownerId: String, generation: Long) = NativeWorkbenchSession(
        binding = BoundUploadSession("https://pim.example", ownerId),
        identityGeneration = generation,
    )

    private fun task(
        id: String = "task-a",
        title: String = "Owner A only",
        due: String? = null,
    ) = TaskResponse(
        calendarId = "cal-1",
        description = null,
        dtStart = null,
        due = due,
        estimatedDuration = null,
        id = id,
        isInbox = false,
        minimumSegment = null,
        percentComplete = 0,
        plannedEnd = null,
        priority = 3,
        sortOrder = 0,
        status = "NEEDS-ACTION",
        subTasks = emptyList(),
        taskBookId = null,
        title = title,
        uid = "task-uid-$id",
    )

    private fun confirmation(summary: String) = OperationConfirmationDto(
        createdAt = "2026-11-01T10:00:00Z",
        expiresAt = "2026-11-02T10:00:00Z",
        id = "confirmation-1",
        operationType = "DeleteItem",
        payloadJson = "private-$summary",
        previewJson = "private-preview-$summary",
        riskLevel = 14,
        source = "test",
        status = 0,
        summary = summary,
    )

    private fun habit(status: String = "active") = HabitRoutineDto(
        cadence = 1,
        description = null,
        id = "habit-a",
        source = "test",
        status = status,
        title = "Owner A habit",
    )

    private class ControllableTransport(
        var current: NativeWorkbenchSession?,
        val api: ApiService,
    ) : NativeWorkbenchTransport {
        override fun currentSession(): NativeWorkbenchSession? = current

        override fun isCurrent(session: NativeWorkbenchSession): Boolean = current == session

        override fun apiFor(session: NativeWorkbenchSession): ApiService {
            check(isCurrent(session))
            return api
        }
    }
}

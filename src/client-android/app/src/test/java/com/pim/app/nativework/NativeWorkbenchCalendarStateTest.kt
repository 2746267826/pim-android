package com.pim.app.nativework

import com.pim.app.nativework.quicknotes.LegacyQuickNoteRepository
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.EventResponse
import com.pim.core.contract.EventResponsePagedResult
import com.pim.core.contract.EventResponsePagedResultApiResponse
import com.pim.core.contract.TaskBookDtoIReadOnlyListApiResponse
import com.pim.core.contract.TaskResponsePagedResult
import com.pim.core.contract.TaskResponsePagedResultApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchCalendarStateTest {
    private val owner = "11111111-1111-4111-8111-111111111111"

    @Test
    fun calendarViewAndDateSurviveSectionSwitchAndEditorRefresh() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            val api = fakeApi { _, _ ->
                eventPage(event(id = "loaded", dtStart = "2026-11-24T09:00:00Z"))
            }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()

            assertEquals(CalendarViewMode.Day, viewModel.state.value.calendarView)
            assertEquals(LocalDate.now(), viewModel.state.value.calendarDate)
            assertTrue(viewModel.state.value.calendarLoaded)
            assertEquals(listOf("loaded"), viewModel.state.value.events.map { it.id })

            viewModel.setCalendarView(CalendarViewMode.Week)
            runCurrent()
            assertEquals(CalendarViewMode.Week, viewModel.state.value.calendarView)

            viewModel.moveCalendar(1)
            runCurrent()
            val selectedWeekDate = viewModel.state.value.calendarDate
            assertEquals(LocalDate.now().plusWeeks(1), selectedWeekDate)

            viewModel.selectSection(NativeWorkbenchSection.Tasks)
            runCurrent()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()

            assertEquals(CalendarViewMode.Week, viewModel.state.value.calendarView)
            assertEquals(selectedWeekDate, viewModel.state.value.calendarDate)
            assertEquals(listOf("loaded"), viewModel.state.value.events.map { it.id })
        }
    }

    @Test
    fun datePickerJumpCanKeepTheCurrentViewOrOpenDayView() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            val api = fakeApi { _, _ -> eventPage() }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()
            viewModel.setCalendarView(CalendarViewMode.Month)
            runCurrent()

            val target = LocalDate.of(2027, 1, 31)
            viewModel.selectCalendarDate(target)
            runCurrent()
            assertEquals(target, viewModel.state.value.calendarDate)
            assertEquals(CalendarViewMode.Month, viewModel.state.value.calendarView)

            viewModel.selectCalendarDate(LocalDate.of(2027, 2, 28), openDay = true)
            runCurrent()
            assertEquals(LocalDate.of(2027, 2, 28), viewModel.state.value.calendarDate)
            assertEquals(CalendarViewMode.Day, viewModel.state.value.calendarView)
        }
    }

    @Test
    fun monthPagingFromJanuary31LandsOnFebruaryEnd() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            val api = fakeApi { _, _ -> eventPage() }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()
            viewModel.setCalendarView(CalendarViewMode.Month)
            runCurrent()
            viewModel.selectCalendarDate(LocalDate.of(2027, 1, 31))
            runCurrent()

            viewModel.moveCalendar(1)
            runCurrent()

            assertEquals(LocalDate.of(2027, 2, 28), viewModel.state.value.calendarDate)
        }
    }

    @Test
    fun todayKeepsTheSelectedViewAndReturnsToCurrentDate() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            val api = fakeApi { _, _ -> eventPage() }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()
            viewModel.setCalendarView(CalendarViewMode.Month)
            runCurrent()
            viewModel.selectCalendarDate(LocalDate.of(2026, 1, 1))
            runCurrent()

            viewModel.goToCalendarToday()
            runCurrent()

            assertEquals(LocalDate.now(), viewModel.state.value.calendarDate)
            assertEquals(CalendarViewMode.Month, viewModel.state.value.calendarView)
        }
    }

    @Test
    fun loadedViewCanBeRestoredFromDevicePreference() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            val api = fakeApi { _, _ -> eventPage() }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()

            viewModel.restoreCalendarView(CalendarViewMode.Week)
            runCurrent()

            assertEquals(CalendarViewMode.Week, viewModel.state.value.calendarView)
        }
    }

    @Test
    fun emptyRangeIsMarkedLoadedAndShowsNoEvents() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            val api = fakeApi { _, _ -> eventPage() }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()

            assertTrue(viewModel.state.value.calendarLoaded)
            assertTrue(viewModel.state.value.events.isEmpty())
            assertNull(viewModel.state.value.error)
        }
    }

    @Test
    fun readFailureKeepsSelectionAndRetryRecovers() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            var fail = true
            val api = fakeApi { _, _ ->
                if (fail) throw IllegalStateException("offline")
                eventPage(event(id = "recovered", dtStart = "2026-11-24T09:00:00Z"))
            }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()

            assertEquals(LocalDate.now(), viewModel.state.value.calendarDate)
            assertFalse(viewModel.state.value.calendarLoaded)
            assertTrue(viewModel.state.value.error.orEmpty().contains("offline"))

            fail = false
            viewModel.refresh()
            runCurrent()

            assertNull(viewModel.state.value.error)
            assertTrue(viewModel.state.value.calendarLoaded)
            assertEquals(listOf("recovered"), viewModel.state.value.events.map { it.id })
        }
    }

    @Test
    fun delayedResponseForThePreviousDateCannotReplaceTheCurrentRange() = runTest {
        withMainDispatcher {
            val transport = TestTransport()
            var pending: Continuation<Any?>? = null
            var calls = 0
            val api = fakeApi { _, _ ->
                calls++
                if (calls == 1) {
                    pending = pendingContinuation
                    COROUTINE_SUSPENDED
                } else {
                    eventPage()
                }
            }
            val viewModel = viewModel(api, transport)
            transport.current = session()
            viewModel.selectSection(NativeWorkbenchSection.Calendar)
            runCurrent()
            assertTrue(pending != null)

            viewModel.moveCalendar(1)
            runCurrent()
            val currentDate = viewModel.state.value.calendarDate

            pending!!.resumeWith(
                Result.success(eventPage(event(id = "stale", dtStart = "2026-11-24T09:00:00Z")))
            )
            runCurrent()

            assertEquals(currentDate, viewModel.state.value.calendarDate)
            assertTrue(viewModel.state.value.events.none { it.id == "stale" })
        }
    }

    private var pendingContinuation: Continuation<Any?>? = null

    private fun fakeApi(
        events: (String, String) -> Any,
    ): ApiService {
        lateinit var api: ApiService
        api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getCalendars" -> CalendarResponseListApiResponse(0, emptyList(), "ok", "now")
                "getEvents" -> {
                    val values = args!!.toList()
                    val start = values[0] as String
                    val end = values[1] as String
                    pendingContinuation = values.last() as Continuation<Any?>
                    events(start, end)
                }
                "getTasksPaged" -> TaskResponsePagedResultApiResponse(
                    0,
                    TaskResponsePagedResult(emptyList(), 1, 100, 0, 0),
                    "ok",
                    "now",
                )
                "getTaskBooks" -> TaskBookDtoIReadOnlyListApiResponse(0, emptyList(), "ok", "now")
                "toString" -> "CalendarStateFakeApi"
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                else -> error("Unexpected call ${method.name}")
            }
        } as ApiService
        return api
    }

    private fun eventPage(vararg events: EventResponse) = EventResponsePagedResultApiResponse(
        0,
        EventResponsePagedResult(
            items = events.toList(),
            page = 1,
            pageSize = 100,
            totalCount = events.size,
            totalPages = if (events.isEmpty()) 0 else 1,
        ),
        "ok",
        "now",
    )

    private fun event(id: String, dtStart: String) = EventResponse(
        calendarId = "cal-1",
        dtEnd = "2026-11-24T10:00:00Z",
        dtStart = dtStart,
        id = id,
        source = "local",
        status = "CONFIRMED",
        title = id,
        uid = "uid-$id",
    )

    private fun viewModel(api: ApiService, transport: TestTransport) =
        NativeWorkbenchViewModel(
            repository = NativeWorkbenchRepository(api),
            legacyQuickNotes = LegacyQuickNoteRepository(api),
            transport = transport.also { it.api = api },
        )

    private fun session() = NativeWorkbenchSession(
        binding = BoundUploadSession("https://pim.example", owner),
        identityGeneration = 1L,
    )

    private suspend fun TestScope.withMainDispatcher(block: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class TestTransport : NativeWorkbenchTransport {
        var current: NativeWorkbenchSession? = null
        lateinit var api: ApiService

        override fun currentSession(): NativeWorkbenchSession? = current

        override fun isCurrent(session: NativeWorkbenchSession): Boolean = current == session

        override fun apiFor(session: NativeWorkbenchSession): ApiService {
            check(isCurrent(session))
            return api
        }
    }
}

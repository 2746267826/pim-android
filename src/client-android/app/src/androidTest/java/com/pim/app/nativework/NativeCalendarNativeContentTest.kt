package com.pim.app.nativework

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.core.contract.CalendarResponse
import com.pim.core.contract.EventResponse
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCalendarNativeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val calendar = CalendarResponse(
        canEdit = true,
        color = "#2563EB",
        eventCount = 0,
        id = "cal-1",
        isDefault = true,
        kind = "calendar",
        name = "本地",
    )

    @Test
    fun dayViewKeepsEditAndDeleteForWritableEvents() {
        setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Day,
                    LocalDate.of(2026, 3, 8),
                ),
                calendarViewEvents = listOf(
                    timedEvent(
                        id = "morning",
                        title = "晨会",
                        start = "2026-03-08T10:00:00Z",
                        end = "2026-03-08T11:00:00Z",
                    ),
                ),
                calendarViewEventTotal = 1,
            ),
        )

        compose.onNodeWithText("上一日").assertIsDisplayed()
        compose.onNodeWithText("下一日").assertIsDisplayed()
        compose.onNodeWithText("3月8日 周日").assertIsDisplayed()
        compose.onNodeWithTag("native-calendar-event-morning").performScrollTo()
        compose.onNodeWithText("晨会").assertExists()
        compose.onNodeWithText("编辑").assertExists()
        compose.onNodeWithText("删除").assertExists()
    }

    @Test
    fun switchingViewsKeepsTheSelectedDateAndShowsMatchingPageLabels() {
        val capture = setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Day,
                    LocalDate.of(2026, 1, 31),
                ),
            ),
        )

        compose.onNodeWithTag("native-calendar-mode-week").performClick()
        compose.onNodeWithText("上一周").assertIsDisplayed()
        compose.onNodeWithText("下一周").assertIsDisplayed()
        compose.onNodeWithTag("native-calendar-week-day-2026-01-26").assertExists()
        compose.onNodeWithTag("native-calendar-week-day-2026-02-01").assertExists()
        assertEquals(
            NativeCalendarViewMode.Week,
            capture().calendarView.mode,
        )
        assertEquals(LocalDate.of(2026, 1, 31), capture().calendarView.anchorDate)

        compose.onNodeWithTag("native-calendar-mode-month").performClick()
        compose.onNodeWithText("上一月").assertIsDisplayed()
        compose.onNodeWithText("下一月").assertIsDisplayed()
        compose.onNodeWithTag("native-calendar-date-2026-02-01").assertExists()
        assertEquals(
            NativeCalendarViewMode.Month,
            capture().calendarView.mode,
        )
        assertEquals(LocalDate.of(2026, 1, 31), capture().calendarView.anchorDate)
    }

    @Test
    fun narrowWeekShowsSevenDaySelectorAndSelectedDayEvents() {
        setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Week,
                    LocalDate.of(2026, 2, 15),
                ),
                calendarViewEvents = listOf(
                    timedEvent(
                        id = "sunday",
                        title = "周日安排",
                        start = "2026-02-15T10:00:00Z",
                        end = "2026-02-15T11:00:00Z",
                    ),
                ),
                calendarViewEventTotal = 1,
            ),
            weekBreakpoint = 10_000.dp,
        )

        listOf(
            "2026-02-09",
            "2026-02-10",
            "2026-02-11",
            "2026-02-12",
            "2026-02-13",
            "2026-02-14",
            "2026-02-15",
        ).forEach { date ->
            compose.onNodeWithTag("native-calendar-week-day-$date").assertExists()
        }
        compose.onNodeWithText("周日安排").performScrollTo().assertExists()
    }

    @Test
    fun wideWeekShowsSevenColumns() {
        setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Week,
                    LocalDate.of(2026, 2, 15),
                ),
            ),
            weekBreakpoint = 0.dp,
        )

        listOf(
            "2026-02-09",
            "2026-02-10",
            "2026-02-11",
            "2026-02-12",
            "2026-02-13",
            "2026-02-14",
            "2026-02-15",
        ).forEach { date ->
            compose.onNodeWithTag("native-calendar-week-column-$date").assertExists()
        }
        compose.onNodeWithText("周日").assertExists()
    }

    @Test
    fun monthCellOpensTheSelectedDayView() {
        val capture = setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Month,
                    LocalDate.of(2026, 2, 15),
                ),
            ),
            monthBreakpoint = 0.dp,
        )

        compose.onNodeWithTag("native-calendar-date-2026-02-15")
            .performScrollTo()
            .performClick()

        assertEquals(
            NativeCalendarViewState(
                NativeCalendarViewMode.Day,
                LocalDate.of(2026, 2, 15),
            ),
            capture().calendarView,
        )
        compose.onNodeWithText("上一日").assertIsDisplayed()
        compose.onNodeWithText("2月15日 周日").assertExists()
    }

    @Test
    fun todayReturnsToTheCurrentDateWithoutLeavingTheWeekView() {
        val capture = setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Week,
                    LocalDate.of(2026, 1, 31),
                ),
            ),
            today = LocalDate.of(2026, 11, 25),
        )

        compose.onNodeWithTag("native-calendar-today").performClick()

        assertEquals(
            NativeCalendarViewState(
                NativeCalendarViewMode.Week,
                LocalDate.of(2026, 11, 25),
            ),
            capture().calendarView,
        )
        compose.onNodeWithText("11月23日", substring = true).assertExists()
        compose.onNodeWithText("上一周").assertIsDisplayed()
    }

    @Test
    fun monthNavigationClampsToTargetMonthEnd() {
        val capture = setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Month,
                    LocalDate.of(2026, 1, 31),
                ),
            ),
        )

        compose.onNodeWithTag("native-calendar-next").performClick()
        assertEquals(LocalDate.of(2026, 2, 28), capture().calendarView.anchorDate)
        compose.onNodeWithText("2026年2月").assertIsDisplayed()

        compose.onNodeWithTag("native-calendar-next").performClick()
        assertEquals(LocalDate.of(2026, 3, 28), capture().calendarView.anchorDate)
        compose.onNodeWithText("2026年3月").assertIsDisplayed()
    }

    @Test
    fun allDayAndTimedEventsAppearOnTheSameLocalDay() {
        setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Day,
                    LocalDate.of(2026, 3, 8),
                ),
                calendarViewEvents = listOf(
                    timedEvent(
                        id = "timed",
                        title = "上午日程",
                        start = "2026-03-08T10:00:00Z",
                        end = "2026-03-08T11:00:00Z",
                    ),
                    allDayEvent(
                        id = "all-day",
                        title = "全天安排",
                        start = "2026-03-08T00:00:00+08:00",
                        end = "2026-03-09T00:00:00+08:00",
                    ),
                ),
                calendarViewEventTotal = 2,
            ),
        )

        compose.onNodeWithText("全天安排").performScrollTo().assertExists()
        compose.onNodeWithText("上午日程").performScrollTo().assertExists()
        compose.onNodeWithText("全天 · 3月8日").assertExists()
    }

    @Test
    fun datePickerEntryOpensTheDirectDatePicker() {
        setCalendarContent(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendars = listOf(calendar),
                calendarView = NativeCalendarViewState(
                    NativeCalendarViewMode.Day,
                    LocalDate.of(2026, 3, 8),
                ),
            ),
        )

        compose.onNodeWithTag("native-calendar-date-picker").performClick()
        compose.onNodeWithText("确定").assertExists()
        compose.onNodeWithText("取消").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("确定").assertDoesNotExist()
    }

    private fun setCalendarContent(
        initial: NativeWorkbenchUiState,
        width: Dp = 360.dp,
        weekBreakpoint: Dp = 720.dp,
        monthBreakpoint: Dp = 600.dp,
        today: LocalDate = LocalDate.of(2026, 11, 25),
    ): () -> NativeWorkbenchUiState {
        var state by mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width).fillMaxHeight()) {
                    CalendarNativeContent(
                        state = state,
                        onSelectMode = { mode ->
                            state = state.copy(
                                calendarView = state.calendarView.withMode(mode),
                            )
                        },
                        onPreviousPage = {
                            state = state.copy(
                                calendarView = state.calendarView.shift(-1),
                            )
                        },
                        onNextPage = {
                            state = state.copy(
                                calendarView = state.calendarView.shift(1),
                            )
                        },
                        onToday = {
                            state = state.copy(
                                calendarView = state.calendarView.goToToday(today),
                            )
                        },
                        onSelectDate = { date ->
                            state = state.copy(
                                calendarView = state.calendarView.goToDate(date),
                            )
                        },
                        onOpenDateInDay = { date ->
                            state = state.copy(
                                calendarView = state.calendarView.openDay(date),
                            )
                        },
                        onNewEvent = {},
                        onCreateCalendar = {},
                        onEditEvent = {},
                        onDeleteEvent = {},
                        weekColumnsBreakpoint = weekBreakpoint,
                        monthTitlesBreakpoint = monthBreakpoint,
                    )
                }
            }
        }
        return { state }
    }

    private fun timedEvent(
        id: String,
        title: String,
        start: String,
        end: String,
    ) = EventResponse(
        calendarId = "cal-1",
        dtEnd = end,
        dtStart = start,
        id = id,
        rRule = null,
        source = "local",
        status = "CONFIRMED",
        title = title,
        uid = "uid-$id",
    )

    private fun allDayEvent(
        id: String,
        title: String,
        start: String,
        end: String,
    ) = EventResponse(
        calendarId = "cal-1",
        dtEnd = end,
        dtStart = start,
        id = id,
        isAllDay = true,
        rRule = null,
        source = "local",
        status = "CONFIRMED",
        title = title,
        uid = "uid-$id",
    )
}

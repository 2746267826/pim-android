package com.pim.app.nativework

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.EventResponse
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun calendarExposesDayWeekMonthAndNavigationControls() {
        var selectedView: CalendarViewMode? = null
        var previous = 0
        var next = 0
        var today = 0
        val state = mutableStateOf(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendarView = CalendarViewMode.Day,
                calendarDate = LocalDate.of(2026, 11, 24),
                calendarLoaded = true,
                events = emptyList(),
            )
        )
        composeTestRule.setContent {
            PimTheme {
                CalendarNativeContent(
                    state = state.value,
                    onViewChange = {
                        selectedView = it
                        state.value = state.value.copy(calendarView = it)
                    },
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    onToday = { today++ },
                    onDateSelected = {},
                    onOpenDay = {},
                    onRestoreView = {},
                    onRetry = {},
                    onNewEvent = {},
                    onCreateCalendar = {},
                    onEditEvent = {},
                    onDeleteEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("calendar-title").assertTextContains("2026年11月24日")
        composeTestRule.onNodeWithTag("calendar-view-week").performClick()
        assertEquals(CalendarViewMode.Week, selectedView)
        composeTestRule.onNodeWithTag("calendar-title")
            .assertTextContains("2026年11月23日 – 11月29日")
        composeTestRule.onNodeWithTag("calendar-view-month").performClick()
        composeTestRule.onNodeWithTag("calendar-title").assertTextContains("2026年11月")
        composeTestRule.onNodeWithTag("calendar-view-day").performClick()
        composeTestRule.onNodeWithTag("calendar-title").assertTextContains("2026年11月24日")

        composeTestRule.onNodeWithTag("calendar-prev").performClick()
        composeTestRule.onNodeWithTag("calendar-next").performClick()
        composeTestRule.onNodeWithTag("calendar-today").performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
        assertEquals(1, today)
        composeTestRule.onNodeWithTag("calendar-date-picker").assertIsDisplayed()
    }

    @Test
    fun monthCellOpensTheSelectedDay() {
        var opened: LocalDate? = null
        val state = NativeWorkbenchUiState(
            section = NativeWorkbenchSection.Calendar,
            calendarView = CalendarViewMode.Month,
            calendarDate = LocalDate.of(2026, 11, 24),
            calendarLoaded = true,
        )
        composeTestRule.setContent {
            PimTheme {
                CalendarNativeContent(
                    state = state,
                    onViewChange = {},
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onDateSelected = {},
                    onOpenDay = { opened = it },
                    onRestoreView = {},
                    onRetry = {},
                    onNewEvent = {},
                    onCreateCalendar = {},
                    onEditEvent = {},
                    onDeleteEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("calendar-day-2026-11-24")
            .performScrollTo()
            .performClick()

        assertEquals(LocalDate.of(2026, 11, 24), opened)
    }

    @Test
    fun datePickerConfirmationReturnsTheSelectedDate() {
        var selected: LocalDate? = null
        val state = NativeWorkbenchUiState(
            section = NativeWorkbenchSection.Calendar,
            calendarView = CalendarViewMode.Day,
            calendarDate = LocalDate.of(2026, 11, 24),
            calendarLoaded = true,
        )
        composeTestRule.setContent {
            PimTheme {
                CalendarNativeContent(
                    state = state,
                    onViewChange = {},
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onDateSelected = { selected = it },
                    onOpenDay = {},
                    onRestoreView = {},
                    onRetry = {},
                    onNewEvent = {},
                    onCreateCalendar = {},
                    onEditEvent = {},
                    onDeleteEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("calendar-date-picker").performClick()
        composeTestRule.onNodeWithTag("calendar-date-picker-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("确定").performClick()

        assertEquals(LocalDate.of(2026, 11, 24), selected)
    }

    @Test
    fun loadingEmptyAndErrorStatesAreVisibleAndRetryable() {
        var retry = 0
        val state = mutableStateOf(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Calendar,
                calendarView = CalendarViewMode.Day,
                calendarDate = LocalDate.of(2026, 11, 24),
                loading = true,
                calendarLoaded = false,
            )
        )
        composeTestRule.setContent {
            PimTheme {
                CalendarNativeContent(
                    state = state.value,
                    onViewChange = {},
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onDateSelected = {},
                    onOpenDay = {},
                    onRestoreView = {},
                    onRetry = { retry++ },
                    onNewEvent = {},
                    onCreateCalendar = {},
                    onEditEvent = {},
                    onDeleteEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("正在读取日程…").assertIsDisplayed()

        state.value = state.value.copy(loading = false, calendarLoaded = true)
        composeTestRule.onNodeWithText("当天暂无日程").assertIsDisplayed()

        state.value = state.value.copy(calendarLoaded = false, error = "offline")
        composeTestRule.onNodeWithText("日程读取失败：offline").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").performClick()
        assertEquals(1, retry)
    }

    @Test
    fun allDayEventUsesTheEventZoneLocalDate() {
        val allDay = EventResponse(
            calendarId = "cal-1",
            dtEnd = "2026-11-24T16:00:00Z",
            dtStart = "2026-11-23T16:00:00Z",
            id = "all-day",
            isAllDay = true,
            source = "local",
            status = "CONFIRMED",
            timeZoneId = "Asia/Shanghai",
            title = "全天事项",
            uid = "uid-all-day",
        )
        val state = NativeWorkbenchUiState(
            section = NativeWorkbenchSection.Calendar,
            calendarView = CalendarViewMode.Day,
            calendarDate = LocalDate.of(2026, 11, 24),
            calendarLoaded = true,
            events = listOf(allDay),
        )
        composeTestRule.setContent {
            PimTheme {
                CalendarNativeContent(
                    state = state,
                    onViewChange = {},
                    onPrevious = {},
                    onNext = {},
                    onToday = {},
                    onDateSelected = {},
                    onOpenDay = {},
                    onRestoreView = {},
                    onRetry = {},
                    onNewEvent = {},
                    onCreateCalendar = {},
                    onEditEvent = {},
                    onDeleteEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("全天 · 11月24日").assertIsDisplayed()
    }
}

package com.pim.app.nativework

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pim.app.ui.theme.PimTheme
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
                    onViewChange = { selectedView = it },
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

        composeTestRule.onNodeWithTag("calendar-day-2026-11-24").performClick()

        assertEquals(LocalDate.of(2026, 11, 24), opened)
    }
}

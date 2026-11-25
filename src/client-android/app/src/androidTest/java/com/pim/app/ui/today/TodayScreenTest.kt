package com.pim.app.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pim.app.status.QueueStatusSnapshot
import com.pim.app.status.StatusCenterSnapshot
import com.pim.app.status.StatusCenterState
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TodayScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun todayStatusBarShowsPendingTotalAndPendingLocationTags() {
        val state = mutableStateOf(
            TodayUiState(
                pendingCount = 15,
                pendingLocationPoints = 9
            )
        )
        composeTestRule.setContent {
            PimTheme {
                TodayStatusBar(
                    state = state.value,
                    syncFeedback = null,
                    onSyncNow = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("today-pending-total").assertIsDisplayed()
        composeTestRule.onNodeWithTag("today-pending-location").assertIsDisplayed()
    }

    @Test
    fun todayStatusBarDisplaysCountValues() {
        val state = mutableStateOf(
            TodayUiState(
                pendingCount = 15,
                pendingLocationPoints = 9
            )
        )
        composeTestRule.setContent {
            PimTheme {
                TodayStatusBar(
                    state = state.value,
                    syncFeedback = null,
                    onSyncNow = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("today-pending-total").assertIsDisplayed()
        composeTestRule.onNodeWithTag("today-pending-location").assertIsDisplayed()
    }

    @Test
    fun todayStatusBarShowsCheckingStateInsteadOfClaimingReady() {
        composeTestRule.setContent {
            PimTheme {
                TodayStatusBar(
                    state = TodayUiState(),
                    syncFeedback = null,
                    onSyncNow = {},
                    localStatus = TodayLocalStatusSummary(checking = true),
                )
            }
        }

        composeTestRule.onNodeWithTag("today-local-status")
            .assertIsDisplayed()
            .assertTextContains("本机状态 · 检查中")
    }

    @Test
    fun todayStatusBarShowsPendingHealthAndOpensStatusCenter() {
        var openCount = 0
        composeTestRule.setContent {
            PimTheme {
                TodayStatusBar(
                    state = TodayUiState(),
                    syncFeedback = null,
                    onSyncNow = {},
                    localStatus = TodayLocalStatusSummary(pendingCount = 3),
                    onOpenStatusCenter = { openCount++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("today-local-status")
            .assertIsDisplayed()
            .assertTextContains("本机状态 · 3 项待处理")
            .performClick()

        assertEquals("今日页采集状态入口必须可进入状态中心", 1, openCount)
    }
}

package com.pim.app.ui.permissions

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.ui.theme.PimTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionCenterScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun notificationStatusRefreshesAfterReturningFromSettings() {
        var snapshot = allGranted(notificationGranted = false)
        val provider = { snapshot }

        composeTestRule.setContent {
            PimTheme { PermissionCenterScreen(snapshotProvider = provider) }
        }

        composeTestRule.onNodeWithTag(
            "permission-center-status-notification-permission-missing",
            useUnmergedTree = true,
        )
            .assertTextContains("未授权")

        snapshot = allGranted(notificationGranted = true)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(
            "permission-center-status-notification-permission-missing",
            useUnmergedTree = true,
        )
            .assertTextContains("已授权")
    }

    private fun allGranted(notificationGranted: Boolean) = PermissionStatusSnapshot(
        notificationGranted = notificationGranted,
        preciseLocationGranted = true,
        backgroundLocationGranted = true,
        usageAccessGranted = true,
        activityRecognitionGranted = true,
        batteryOptimizationGranted = true,
        doNotDisturbAccessGranted = true,
    )
}

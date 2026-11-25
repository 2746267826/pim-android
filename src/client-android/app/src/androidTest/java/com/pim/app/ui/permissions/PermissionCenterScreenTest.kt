package com.pim.app.ui.permissions

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.ui.theme.PimTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionCenterScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun notificationPermissionRefreshAfterReturningFromSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        runCatching {
            instrumentation.uiAutomation.revokeRuntimePermission(
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }

        composeTestRule.setContent {
            PimTheme { PermissionCenterScreen() }
        }

        composeTestRule.onNodeWithTag("permission-center-status-notification-permission-missing")
            .assertTextContains("未授权")

        instrumentation.uiAutomation.grantRuntimePermission(
            packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("permission-center-status-notification-permission-missing")
            .assertTextContains("已授权")
    }
}

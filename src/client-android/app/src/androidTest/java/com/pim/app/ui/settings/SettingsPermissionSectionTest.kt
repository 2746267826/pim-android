package com.pim.app.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pim.app.status.CollectionPermissionAccess
import com.pim.app.status.CollectionPermissionKind
import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsPermissionSectionTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun notificationMasterSwitchOffShowsRestrictedInsteadOfGranted() {
        render(
            allGranted().copy(
                restrictedPermissions = mapOf(
                    CollectionPermissionKind.NOTIFICATION to "系统通知已关闭",
                ),
            )
        )

        composeTestRule.onNodeWithTag(
            "settings-permission-status-notification-permission-missing",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed().assertTextContains("受限：系统通知已关闭")
    }

    @Test
    fun permissionCheckFailureIsNotDisplayedAsGranted() {
        render(
            allGranted().copy(
                backgroundLocationGranted = false,
                failedPermissionChecks = setOf(CollectionPermissionKind.BACKGROUND_LOCATION),
            )
        )

        composeTestRule.onNodeWithTag(
            "settings-permission-status-background-location-missing",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed().assertTextContains("检查失败")
    }

    @Test
    fun grantedBatteryOptimizationPassesGrantedAccessToTheSettingsAction() {
        var openedCode: String? = null
        var openedAccess: CollectionPermissionAccess? = null
        render(allGranted()) { code, access ->
            openedCode = code
            openedAccess = access
        }

        composeTestRule.onNodeWithTag("settings-permission-row-battery-optimization-missing")
            .performScrollTo()
            .performClick()

        assertEquals("battery-optimization-missing", openedCode)
        assertEquals(CollectionPermissionAccess.GRANTED, openedAccess)
    }

    private fun render(
        permissions: PermissionStatusSnapshot,
        onOpenPermission: (String, CollectionPermissionAccess?) -> Unit = { _, _ -> },
    ) {
        composeTestRule.setContent {
            PimTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsPermissionSection(
                        permissions = permissions,
                        onOpenPermission = onOpenPermission,
                    )
                }
            }
        }
    }

    private fun allGranted() = PermissionStatusSnapshot(
        notificationGranted = true,
        preciseLocationGranted = true,
        backgroundLocationGranted = true,
        usageAccessGranted = true,
        activityRecognitionGranted = true,
        batteryOptimizationGranted = true,
        doNotDisturbAccessGranted = true,
    )
}

package com.pim.app.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pim.app.ui.components.PimButton
import com.pim.app.ui.components.PimFactChip
import com.pim.app.ui.components.PimSection
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PimThemeComponentsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun oneThemeReplacementUpdatesMultipleRenderedComponents() {
        val colors = mutableStateOf(PimLightColorScheme)
        val status = mutableStateOf(PimLightStatusColors)
        composeRule.setContent {
            PimTheme(colorScheme = colors.value, statusColors = status.value) {
                Column {
                    PimButton(onClick = {}, modifier = Modifier.testTag("button")) { Text("保存") }
                    PimSection("本机材料", modifier = Modifier.testTag("section")) { Text("正文") }
                    PimFactChip("待传总数", "15", modifier = Modifier.testTag("chip"))
                    PimStatusBanner("等待上传", PimStatusTone.Warning, Modifier.testTag("banner"))
                }
            }
        }
        assertSurfaceColor("button", colors.value.primary)
        assertSurfaceColor("section", colors.value.surface)
        assertSurfaceColor("chip", colors.value.surfaceVariant)
        assertSurfaceColor("banner", status.value.warningContainer)

        composeRule.runOnIdle {
            colors.value = colors.value.copy(
                primary = colors.value.secondary,
                surface = colors.value.primaryContainer,
                surfaceVariant = status.value.successContainer
            )
            status.value = status.value.copy(warningContainer = status.value.successContainer)
        }
        assertSurfaceColor("button", colors.value.primary)
        assertSurfaceColor("section", colors.value.surface)
        assertSurfaceColor("chip", colors.value.surfaceVariant)
        assertSurfaceColor("banner", status.value.warningContainer)
    }

    @Test
    fun oneDimensionReplacementUpdatesSectionPaddingAndButtonTouchTarget() {
        val dimensions = mutableStateOf(PimDefaultDimensions)
        composeRule.setContent {
            PimTheme(dimensions = dimensions.value) {
                Column {
                    PimSection("本机材料", modifier = Modifier.testTag("section")) { Text("正文") }
                    PimButton(onClick = {}, modifier = Modifier.testTag("button")) { Text("保存") }
                }
            }
        }
        fun titleOffset(): Float = composeRule.onNodeWithText("本机材料").fetchSemanticsNode().boundsInRoot.top -
            composeRule.onNodeWithTag("section").fetchSemanticsNode().boundsInRoot.top
        val initial = titleOffset()
        composeRule.onNodeWithTag("button").assertHeightIsAtLeast(48.dp)
        composeRule.runOnIdle {
            dimensions.value = dimensions.value.copy(sectionPadding = 24.dp, minimumTouchTarget = 56.dp)
        }
        assertTrue(titleOffset() > initial)
        composeRule.onNodeWithTag("button").assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun bothPalettesRenderAllStatusTones() {
        val dark = mutableStateOf(false)
        composeRule.setContent {
            PimTheme(darkTheme = dark.value) {
                Column {
                    PimStatusTone.values().forEach { tone ->
                        PimStatusBanner(tone.name, tone, Modifier.testTag(tone.name))
                    }
                }
            }
        }
        for (value in listOf(false, true)) {
            composeRule.runOnIdle { dark.value = value }
            val colors = if (value) PimDarkColorScheme else PimLightColorScheme
            val status = if (value) PimDarkStatusColors else PimLightStatusColors
            assertSurfaceColor("Info", colors.primaryContainer)
            assertSurfaceColor("Success", status.successContainer)
            assertSurfaceColor("Warning", status.warningContainer)
            assertSurfaceColor("Error", colors.errorContainer)
        }
    }

    @Test
    fun statusTextWrapsAtLargeFontScaleInNarrowContent() {
        val message = "连接未加密，数据可能被第三方查看。请确认服务器地址后再继续。"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                PimTheme {
                    Box(Modifier.width(280.dp)) {
                        PimStatusBanner(message, PimStatusTone.Warning, Modifier.testTag("banner"))
                    }
                }
            }
        }
        composeRule.onNodeWithTag("banner").assertIsDisplayed()
        val text = composeRule.onNodeWithText(message, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val banner = composeRule.onNodeWithTag("banner").fetchSemanticsNode().boundsInRoot
        assertTrue(text.left >= banner.left && text.right <= banner.right)
        assertTrue(text.top >= banner.top && text.bottom <= banner.bottom)
    }

    @Test
    fun sharedButtonRetainsDisabledAndClickSemantics() {
        val enabled = mutableStateOf(false)
        var clicks = 0
        composeRule.setContent {
            PimTheme {
                PimButton(onClick = { clicks++ }, enabled = enabled.value, modifier = Modifier.testTag("button")) {
                    Text("保存")
                }
            }
        }
        composeRule.onNodeWithTag("button").assertIsNotEnabled()
        composeRule.runOnIdle { enabled.value = true }
        composeRule.onNodeWithTag("button").performClick()
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun factChipKeepsLabelAndValueInOneAccessibleNode() {
        composeRule.setContent {
            PimTheme { PimFactChip("待传总数", "15", Modifier.testTag("chip")) }
        }
        composeRule.onNodeWithTag("chip").assertTextContains("待传总数").assertTextContains("15")
    }

    private fun assertSurfaceColor(tag: String, expected: Color) {
        val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val actual = pixels[pixels.width / 2, 3]
        assertEquals("$tag red", expected.red, actual.red, 0.01f)
        assertEquals("$tag green", expected.green, actual.green, 0.01f)
        assertEquals("$tag blue", expected.blue, actual.blue, 0.01f)
    }
}

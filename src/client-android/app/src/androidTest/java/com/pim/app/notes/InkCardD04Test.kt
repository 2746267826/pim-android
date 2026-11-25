package com.pim.app.notes

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.ui.theme.PimTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class InkCardD04Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearBrushPreferenceBeforeEachCase() {
        preferences().edit().clear().commit()
    }

    @After fun clearBrushPreferenceAfterEachCase() {
        preferences().edit().clear().commit()
    }

    @Test fun brushPanelExposesSelectedColorAndWidthAndRemembersTheChoice() {
        val id = newNoteId()
        val ink = mutableStateOf(InkData())
        compose.setContent {
            PimTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InkCard(id, ink.value, readOnly = false) { ink.value = it }
                }
            }
        }

        compose.onNodeWithTag("ink-draw-$id").performScrollTo().performClick()
        compose.onNodeWithTag("ink-brush-$id").performScrollTo().performClick()
        compose.onNodeWithTag("ink-color-ink-$id").assertIsSelected()
        compose.onNodeWithTag("ink-width-medium-$id").assertIsSelected()

        compose.onNodeWithTag("ink-color-blue-$id").performClick()
        compose.onNodeWithTag("ink-color-blue-$id").assertIsSelected()
        compose.onNodeWithTag("ink-width-coarse-$id").performClick()
        compose.onNodeWithTag("ink-width-coarse-$id").assertIsSelected()
        compose.onNodeWithTag("ink-brush-done-$id").performClick()

        compose.onNodeWithTag("ink-brush-$id").assertContentDescriptionEquals("画笔设置：蓝，粗")
        val saved = InkPenPreferenceStore(preferences()).load()
        org.junit.Assert.assertEquals(InkPenPreference(INK_COLOR_BLUE, INK_WIDTH_COARSE), saved)
    }

    @Test fun eraserStartsWithPartialModeAndWholeStrokeIsAnExplicitChoice() {
        val id = newNoteId()
        val ink = mutableStateOf(InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(30f, 40f), InkPoint(280f, 40f)), id = newNoteId()),
        )))
        compose.setContent {
            PimTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InkCard(id, ink.value, readOnly = false) { ink.value = it }
                }
            }
        }

        compose.onNodeWithTag("ink-draw-$id").performScrollTo().performClick()
        if (compose.onAllNodesWithText("仅手写笔").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("ink-stylus-only-$id").performScrollTo().performClick()
        }
        compose.onNodeWithTag("ink-eraser-partial-$id").performScrollTo().performClick()
        compose.onNodeWithTag("ink-eraser-partial-$id").assertIsOn()
        compose.onNodeWithTag("ink-eraser-whole-$id").assertIsOff()

        compose.onNodeWithTag("ink-eraser-whole-$id").performClick()
        compose.onNodeWithTag("ink-eraser-whole-$id").assertIsOn()
        compose.onNodeWithTag("ink-eraser-partial-$id").assertIsOff()
    }

    @Test fun noMoveSingleTapEraserKeepsPartialAndWholeStrokeSemantics() {
        val id = newNoteId()
        val initial = InkData(width = 320f, height = 240f, strokes = listOf(
            InkStroke(
                listOf(InkPoint(40f, 120f), InkPoint(280f, 120f)),
                width = INK_WIDTH_COARSE,
                color = INK_COLOR_BLUE,
                id = newNoteId(),
            ),
        ))
        val ink = mutableStateOf(initial)
        compose.setContent {
            PimTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InkCard(id, ink.value, readOnly = false) { ink.value = it }
                }
            }
        }

        compose.onNodeWithTag("ink-draw-$id").performScrollTo().performClick()
        compose.onNodeWithTag("ink-eraser-partial-$id").performScrollTo().performClick()
        compose.onNodeWithTag("ink-canvas-$id").performScrollTo().performTouchInput {
            down(center)
            up()
        }
        compose.waitUntil(5_000) { ink.value.strokes.size == 2 }
        val split = ink.value.strokes
        assertTrue(split.all { it.color == INK_COLOR_BLUE && it.width == INK_WIDTH_COARSE })
        assertEquals(2, split.mapNotNull { it.id }.distinct().size)
        assertTrue(split[0].points.last().x < 160f)
        assertTrue(split[1].points.first().x > 160f)

        ink.value = initial
        compose.waitForIdle()
        compose.onNodeWithTag("ink-eraser-whole-$id").performClick()
        compose.onNodeWithTag("ink-canvas-$id").performScrollTo().performTouchInput {
            down(center)
            up()
        }
        compose.waitUntil(5_000) { ink.value.strokes.isEmpty() }
    }

    private fun preferences() = context.getSharedPreferences(
        InkPenPreferenceStore.PREFERENCES,
        android.content.Context.MODE_PRIVATE,
    )
}

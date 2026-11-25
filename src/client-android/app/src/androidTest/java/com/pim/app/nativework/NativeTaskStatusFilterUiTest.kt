package com.pim.app.nativework

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeTaskStatusFilterUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun taskPageShowsMultiSelectStatusChipsAndClearAction() {
        val state = mutableStateOf(
            NativeWorkbenchUiState(
                section = NativeWorkbenchSection.Tasks,
                taskStatuses = listOf("IN-PROCESS"),
            )
        )
        var toggled: String? = null
        var cleared = 0

        compose.setContent {
            PimTheme {
                NativeTaskStatusFilterRow(
                    selected = state.value.taskStatuses,
                    onToggle = {
                        toggled = it
                        state.value = state.value.copy(
                            taskStatuses = state.value.taskStatuses + it
                        )
                    },
                    onClear = {
                        cleared++
                        state.value = state.value.copy(taskStatuses = emptyList())
                    },
                )
            }
        }

        compose.onNodeWithText("状态").assertIsDisplayed()
        compose.onNodeWithTag("native-task-status-all").assertIsNotSelected()
        compose.onNodeWithTag("native-task-status-NEEDS-ACTION").assertIsNotSelected()
        compose.onNodeWithTag("native-task-status-IN-PROCESS").assertIsSelected()

        compose.onNodeWithTag("native-task-status-NEEDS-ACTION").performClick()
        assertEquals("NEEDS-ACTION", toggled)
        compose.onNodeWithTag("native-task-status-NEEDS-ACTION").assertIsSelected()

        compose.onNodeWithTag("native-task-status-all").performClick()
        assertEquals(1, cleared)
        compose.onNodeWithTag("native-task-status-all").assertIsSelected()
    }
}

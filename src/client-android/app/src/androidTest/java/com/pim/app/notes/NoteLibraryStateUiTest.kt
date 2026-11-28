package com.pim.app.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.pim.app.recording.RecordingState
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoteLibraryStateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingAndSuccessfulEmptyLibraryUseDistinctStates() {
        var state by mutableStateOf(NotesState(loaded = false))
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(state, null, RecordingState(), busy = false, onCreate = {}, onOpen = {}, onExit = null)
            }
        }
        compose.onNodeWithTag("notes-loading").assertIsDisplayed()
        compose.onNodeWithText("正在读取本机记录…").assertIsDisplayed()

        compose.runOnIdle { state = NotesState(loaded = true) }
        compose.onNodeWithTag("notes-loading").assertDoesNotExist()
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("notes-empty"))
        compose.onNodeWithTag("notes-empty").assertIsDisplayed()
        compose.onNodeWithText("还没有笔记").assertIsDisplayed()
        compose.onNodeWithText("从一页空白开始", substring = true).assertIsDisplayed()
    }

    @Test fun firstLoadFailureShowsReasonAndRetryEntersLoadingBeforeEmptyState() {
        var state by mutableStateOf(NotesState(loaded = false, loadFailed = true, loadError = "存储目录不可读"))
        var retries = 0
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = state,
                    selectedId = null,
                    recording = RecordingState(),
                    busy = false,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                    onRetryLoad = {
                        retries += 1
                        state = state.copy(loadFailed = false, loadError = null)
                    },
                )
            }
        }
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("notes-load-error"))
        compose.onNodeWithTag("notes-load-error").assertIsDisplayed()
        compose.onNodeWithText("存储目录不可读", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("notes-load-retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("notes-load-error").assertDoesNotExist()
        compose.onNodeWithTag("notes-loading").assertIsDisplayed()

        compose.runOnIdle { state = NotesState(loaded = true) }
        compose.onNodeWithTag("notes-loading").assertDoesNotExist()
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("notes-empty"))
        compose.onNodeWithTag("notes-empty").assertIsDisplayed()
    }

    @Test fun searchNoMatchKeepsTheLibraryAndShowsAConcreteEmptyResult() {
        val entry = NoteEntry("00000000-0000-0000-0000-000000000001", "课程笔记")
        val state = NotesState(loaded = true, entries = listOf(entry))
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = state,
                    selectedId = null,
                    recording = RecordingState(),
                    busy = false,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                )
            }
        }
        compose.onNodeWithTag("notes-empty").assertDoesNotExist()
        compose.onNodeWithTag("notes-search").performTextInput("不存在的关键词")
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("notes-no-results"))
        compose.onNodeWithTag("notes-no-results").assertIsDisplayed()
        compose.onNodeWithText("没有匹配的笔记").assertIsDisplayed()
        compose.onNodeWithTag("notes-search").assertIsDisplayed()
    }

    @Test fun refreshFailureKeepsExistingRowsAndScrollPositionAndRetriesInPlace() {
        val entries = (1..24).map { number ->
            NoteEntry("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}", "记录 $number")
        }
        var state by mutableStateOf(NotesState(loaded = true, entries = entries))
        var retries = 0
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = state,
                    selectedId = null,
                    recording = RecordingState(),
                    busy = false,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                    onRetryLoad = {
                        retries += 1
                        state = state.copy(refreshing = true, refreshFailed = false, refreshError = null)
                    },
                )
            }
        }
        val visibleDuringRefresh = entries[20]
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("note-open-${visibleDuringRefresh.id}"))
        compose.onNodeWithTag("note-open-${visibleDuringRefresh.id}").assertIsDisplayed()

        compose.runOnIdle { state = state.copy(refreshing = true) }
        compose.onNodeWithTag("note-open-${visibleDuringRefresh.id}").assertIsDisplayed()

        compose.runOnIdle {
            state = state.copy(refreshing = false, refreshFailed = true, refreshError = "存储目录暂时不可读")
        }
        compose.onNodeWithTag("note-open-${visibleDuringRefresh.id}").assertIsDisplayed()
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("notes-refresh-error"))
        compose.onNodeWithTag("notes-refresh-error").assertIsDisplayed()
        compose.onNodeWithText("存储目录暂时不可读", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("notes-refresh-retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithTag("notes-refresh-error").assertDoesNotExist()
        compose.onNodeWithTag("notes-refreshing").assertIsDisplayed()
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("note-open-${visibleDuringRefresh.id}"))
        compose.onNodeWithTag("note-open-${visibleDuringRefresh.id}").assertIsDisplayed()
    }
}

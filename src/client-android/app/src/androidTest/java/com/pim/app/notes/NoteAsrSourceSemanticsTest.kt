package com.pim.app.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.NoteAsrSourceDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteAsrSourceSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sourceCardExposesOneMergedSemanticsNode() {
        val versionId = "source-semantics-version"
        compose.setContent {
            PimTheme {
                FixedSourceCard(
                    versionId = versionId,
                    source = NoteAsrSourceDto(
                        createdAt = "2026-10-29T00:00:00Z",
                        hasConflict = false,
                        isCurrent = false,
                        noteId = "source-semantics-note",
                        recordings = 1,
                        sampleRate = 16_000,
                        title = "固定版本语义测试",
                        totalSamples = 16_000L * 121,
                        versionId = versionId,
                    ),
                )
            }
        }

        compose.onNodeWithTag("note-asr-source-$versionId").assertExists()
    }

    @Test
    fun unavailableSourceUsesASeparateSemanticsNode() {
        val versionId = "source-unavailable-version"
        compose.setContent {
            PimTheme {
                FixedSourceCard(versionId = versionId, source = null)
            }
        }

        compose.onNodeWithTag("note-asr-source-unavailable-$versionId").assertExists()
        compose.onNodeWithTag("note-asr-source-$versionId").assertDoesNotExist()
    }

    @Test
    fun taskListKeepsOneNoticeAndSourceAcrossAsyncHeaderChanges() {
        val versionId = "source-list-transition-version"
        var state by mutableStateOf(
            NoteAsrRepositoryState(
                syncing = true,
                newTaskAvailable = true,
            )
        )
        compose.setContent {
            PimTheme {
                NoteAsrTaskList(
                    state = state,
                    currentTarget = null,
                    initialVersionId = versionId,
                    onDismiss = {},
                    onRefresh = {},
                    onOpen = {},
                    onRefreshTask = {},
                )
            }
        }
        compose.onAllNodesWithTag("note-asr-after-upload-notice").assertCountEquals(1)
        compose.onAllNodesWithTag("note-asr-source-unavailable-$versionId").assertCountEquals(1)

        compose.runOnUiThread {
            state = state.copy(sources = listOf(source(versionId)))
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("note-asr-after-upload-notice").assertCountEquals(1)
        compose.onAllNodesWithTag("note-asr-source-$versionId").assertCountEquals(1)
        compose.onAllNodesWithTag("note-asr-source-unavailable-$versionId").assertCountEquals(0)

        compose.runOnUiThread {
            state = state.copy(
                syncing = false,
                sourceProblem = "QA transient source problem",
                message = "QA transient message",
            )
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("note-asr-after-upload-notice").assertCountEquals(1)
        compose.onAllNodesWithTag("note-asr-source-$versionId").assertCountEquals(1)
    }

    private fun source(versionId: String) = NoteAsrSourceDto(
        createdAt = "2026-10-29T00:00:00Z",
        hasConflict = false,
        isCurrent = false,
        noteId = "source-list-note",
        recordings = 1,
        sampleRate = 16_000,
        title = "固定版本列表过渡测试",
        totalSamples = 16_000L * 121,
        versionId = versionId,
    )
}

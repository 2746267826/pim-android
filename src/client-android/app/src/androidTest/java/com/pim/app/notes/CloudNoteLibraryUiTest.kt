package com.pim.app.notes

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pim.app.recording.RecordingState
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.NativeNoteSummaryDto
import org.junit.Rule
import org.junit.Test

class CloudNoteLibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cloudCardUnlocksAfterRestoreFinishesAndMoreActionsStartCollapsed() {
        val noteId = "00000000-0000-0000-0000-000000000001"
        var busy by mutableStateOf(false)
        var restoreCalls = 0
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = NotesState(loaded = true),
                    selectedId = null,
                    recording = RecordingState(),
                    busy = busy,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                    cloudNotes = CloudNotesState(
                        loaded = true,
                        entries = listOf(CloudNoteEntry(NativeNoteSummaryDto("v1", false, noteId, "云端测试笔记"))),
                    ),
                    onOpenCloudNote = { restoreCalls++; busy = true },
                )
            }
        }
        compose.onNodeWithTag("notes-more-toggle").assertExists()
        compose.onNodeWithTag("note-asr-open").assertDoesNotExist()
        compose.onNodeWithTag("cloud-note-open-$noteId").performClick()
        compose.runOnIdle { busy = false }
        compose.waitForIdle()
        compose.onNodeWithTag("cloud-note-open-$noteId").assertIsEnabled()
        compose.runOnIdle { assert(restoreCalls == 1) }
    }
}

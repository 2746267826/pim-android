package com.pim.app.notes

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

    @Test fun cloudDownloadAndOfflinePackageAreClearlyDifferentFlows() {
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = NotesState(loaded = true),
                    selectedId = null,
                    recording = RecordingState(),
                    busy = false,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                    cloudNotes = CloudNotesState(loaded = true),
                )
            }
        }

        compose.onNodeWithText("云端笔记").assertExists()
        compose.onNodeWithText("列表会自动查找云端副本；点选后才下载并校验到本机。本机已有同一笔记时不会自动覆盖。").assertExists()
        compose.onNodeWithText("导入可编辑文档包").assertDoesNotExist()
        compose.onNodeWithTag("notes-more-toggle").performClick()
        compose.onNodeWithText("离线文档包仅用于文件交换、备份或恢复旧包，不是日常云端笔记列表的同步步骤。包内可能含录音；网页端导入不支持录音，不能作为通用互导格式。").assertExists()
        compose.onNodeWithText("导入可编辑文档包").assertExists()
    }

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

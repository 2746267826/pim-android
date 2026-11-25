package com.pim.app.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pim.app.recording.RecordingPermissionSetting
import com.pim.app.recording.RecordingPermissions
import com.pim.app.recording.RecordingSession
import com.pim.app.recording.RecordingState
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecordingPermissionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingMicrophoneShowsRequestInEditorAndSettingsEntry() {
        var requested = 0
        var settings: RecordingPermissionSetting? = null
        compose.setContent {
            PimTheme {
                RecordingPermissionNotice(
                    permissions = RecordingPermissions(microphoneGranted = false),
                    onRequest = { requested++ },
                    onOpenSettings = { settings = it },
                )
            }
        }

        compose.onNodeWithTag("recording-permission-notice").assertIsDisplayed()
        compose.onNodeWithText("麦克风权限", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-permission-request").performClick()
        compose.onNodeWithTag("recording-permission-microphone-settings").performClick()
        assertEquals(1, requested)
        assertEquals(RecordingPermissionSetting.AppSettings, settings)
    }

    @Test fun missingNotificationPermissionOffersRequestAndNotificationSettings() {
        var requested = 0
        var settings: RecordingPermissionSetting? = null
        compose.setContent {
            PimTheme {
                RecordingPermissionNotice(
                    permissions = RecordingPermissions(notificationPermissionGranted = false),
                    onRequest = { requested++ },
                    onOpenSettings = { settings = it },
                )
            }
        }

        compose.onNodeWithText("通知权限", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-permission-request").performClick()
        compose.onNodeWithTag("recording-permission-notification-settings").performClick()
        assertEquals(1, requested)
        assertEquals(RecordingPermissionSetting.NotificationSettings, settings)
    }

    @Test fun globalNotificationSwitchAndChannelHaveSeparateSettingsActions() {
        var settings: RecordingPermissionSetting? = null
        compose.setContent {
            PimTheme {
                RecordingPermissionNotice(
                    permissions = RecordingPermissions(notificationsEnabled = false, channelEnabled = false),
                    onRequest = {},
                    onOpenSettings = { settings = it },
                )
            }
        }

        compose.onNodeWithText("系统通知总开关", substring = true).assertIsDisplayed()
        compose.onNodeWithText("录音状态通知渠道", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-permission-request").assertDoesNotExist()
        compose.onNodeWithTag("recording-permission-notification-settings").performClick()
        assertEquals(RecordingPermissionSetting.NotificationSettings, settings)
        compose.onNodeWithTag("recording-permission-channel-settings").performClick()
        assertEquals(RecordingPermissionSetting.RecordingChannelSettings, settings)
    }

    @Test fun activeRecordingEntryUsesTheOtherNoteTitle() {
        var clicked = false
        compose.setContent {
            PimTheme {
                Box {
                    NoteEditorDock(
                        recording = RecordingState(RecordingSession("session-1", 1, noteId = "note-1")),
                        ownActive = false,
                        activeOtherTitle = "访谈原稿",
                        canRecord = true,
                        canInsert = true,
                        onRecord = { clicked = true },
                        onPauseResume = {},
                        onRecordings = {},
                        onPhoto = { _ -> },
                        onInk = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("note-start-recording").assertTextContains("访谈原稿", substring = true).performClick()
        assertTrue(clicked)
    }

    @Test fun conflictDialogOffersOpenOrContinueWithoutStartingAnotherRecording() {
        var opened = false
        var continued = false
        compose.setContent {
            PimTheme {
                ActiveRecordingConflictDialog(
                    title = "访谈原稿",
                    canOpenRecording = true,
                    onOpenRecording = { opened = true },
                    onContinueEditing = { continued = true },
                )
            }
        }

        compose.onNodeWithText("《访谈原稿》仍在录音", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-conflict-continue").performClick()
        assertTrue(continued)
        assertFalse(opened)
    }

    @Test fun conflictDialogCanReturnToTheRecordingNote() {
        var opened = false
        compose.setContent {
            PimTheme {
                ActiveRecordingConflictDialog(
                    title = "访谈原稿",
                    canOpenRecording = true,
                    onOpenRecording = { opened = true },
                    onContinueEditing = {},
                )
            }
        }

        compose.onNodeWithTag("recording-conflict-open").assertIsDisplayed().performClick()
        assertTrue(opened)
    }
}

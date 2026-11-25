package com.pim.app.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.pim.app.recording.RecordingChunk
import com.pim.app.recording.RecordingPhase
import com.pim.app.recording.RecordingSession
import com.pim.app.recording.RecordingState
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoteA03CleanupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryLibraryHasNoRepeatedHeaderOrResidentUploadList() {
        val note = document("机器学习课堂")
        val job = uploadJob(note.id, NoteUploadPhase.Failed)
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = state(note),
                    selectedId = null,
                    recording = RecordingState(),
                    busy = false,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                    uploads = NoteUploadsState(loaded = true, jobs = listOf(job)),
                )
            }
        }

        compose.onNodeWithText("本机 · 离线记录").assertDoesNotExist()
        compose.onNodeWithText("笔记").assertDoesNotExist()
        compose.onNodeWithText("1 篇记录，保存在这台设备").assertDoesNotExist()
        compose.onNodeWithText(
            "笔记和原始材料保存在本机。上传只保存固定材料；逐段转写需单独核对 ASR 目标并明确同意。",
        ).assertDoesNotExist()
        compose.onNodeWithTag("note-upload-${job.id}").assertDoesNotExist()

        compose.onNodeWithTag("notes-top-more").performClick()
        compose.onNodeWithTag("notes-upload-history-open").performClick()

        compose.onNodeWithTag("note-upload-history").assertIsDisplayed()
        compose.onNodeWithText("上传记录").assertExists()
        compose.onNodeWithTag("note-upload-${job.id}").assertExists()
    }

    @Test fun singleNoteFailureOpensOnlyItsAssociatedUploadRecord() {
        val first = document("项目周会")
        val second = document("阅读随记")
        val failed = uploadJob(first.id, NoteUploadPhase.Failed)
        val uploaded = uploadJob(second.id, NoteUploadPhase.Uploaded)
        compose.setContent {
            PimTheme {
                NoteLibraryScreen(
                    state = state(first, second),
                    selectedId = null,
                    recording = RecordingState(),
                    busy = false,
                    onCreate = {},
                    onOpen = {},
                    onExit = null,
                    uploads = NoteUploadsState(loaded = true, jobs = listOf(failed, uploaded)),
                )
            }
        }

        compose.onNodeWithTag("note-upload-notice-${first.id}").assertTextContains("上传未完成", substring = true)
        compose.onNodeWithTag("note-upload-issue-${first.id}").assertIsDisplayed().performClick()

        compose.onNodeWithText("仅显示《项目周会》关联的上传任务").assertExists()
        compose.onNodeWithTag("note-upload-${failed.id}").assertExists()
        compose.onNodeWithTag("note-upload-${uploaded.id}").assertDoesNotExist()

        compose.onNodeWithTag("note-upload-history-all").performClick()
        compose.onNodeWithTag("note-upload-${uploaded.id}").assertExists()
    }

    @Test fun saveFailureThenReviewThenLocalSavedIsTheOnlyTopStatusPriority() {
        var saved by mutableStateOf(true)
        var saveFailed by mutableStateOf(true)
        var needsReview by mutableStateOf(false)
        var openedDetails = 0
        compose.setContent {
            PimTheme {
                NoteEditorTopBar(
                    saved = saved,
                    saveFailed = saveFailed,
                    busy = false,
                    preview = false,
                    onBack = {},
                    onSaveStatus = { openedDetails++ },
                    onPreview = {},
                    onFinish = {},
                    onMaterials = {},
                    onRecordings = {},
                    onExport = {},
                    onImport = {},
                    onAbout = {},
                    needsReview = needsReview,
                )
            }
        }

        compose.onNodeWithTag("note-save-status", useUnmergedTree = true).assertTextEquals("保存失败")
        compose.onNodeWithTag("note-save-status-open").performClick()
        compose.runOnIdle { assertEquals(1, openedDetails) }
        compose.runOnIdle {
            saveFailed = false
            needsReview = true
        }
        compose.onNodeWithTag("note-save-status", useUnmergedTree = true).assertTextEquals("待核对正文")
        compose.runOnIdle { needsReview = false }
        compose.onNodeWithTag("note-save-status", useUnmergedTree = true).assertTextEquals("已保存本机")
        compose.runOnIdle { saved = false }
        compose.onNodeWithTag("note-save-status", useUnmergedTree = true).assertTextEquals("保存中")
    }

    @Test fun saveFailureDetailsShowLocalReasonAndRetryWithoutClaimingCloudSync() {
        var retries = 0
        compose.setContent {
            PimTheme {
                NoteSaveStatusDialog(
                    state = NoteSaveState.SaveFailed,
                    saveProblem = "本机存储空间不足",
                    latestUpload = uploadJob(newNoteId(), NoteUploadPhase.Failed),
                    onRetrySave = { retries++ },
                    onReview = {},
                    onOpenMaterials = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag("note-save-status-local").assertTextContains("保存失败", substring = true)
        compose.onNodeWithText("本机存储空间不足").assertExists()
        compose.onNodeWithText("材料上传：上传未完成，请核对后重试").assertExists()
        compose.onNode(hasText("正文已同步", substring = true)).assertDoesNotExist()
        compose.onNodeWithTag("note-save-status-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun editorCleanupRemovesLocalDraftAndStaleCloudCopy() {
        val note = document("研究方法")
        compose.setContent {
            PimTheme {
                Column {
                    NoteDocumentHeading(
                        note = note,
                        enabled = true,
                        preview = false,
                        latest = null,
                        changed = false,
                        recordingCount = 0,
                        onTitle = {},
                        onMaterials = {},
                        onRecordings = {},
                    )
                    NoteMaterialsRail(note, emptyList(), {}, {}, {})
                    NoteDocumentEnd(preview = false)
                }
            }
        }

        compose.onNode(hasText("本机原稿", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("正文支持 Markdown", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("云端处理尚未接入", substring = true)).assertDoesNotExist()
        compose.onNodeWithTag("note-document-end").assertExists()
    }

    @Test fun recordingStateStaysInTheBottomDockInsteadOfReplacingSaveStatus() {
        val noteId = newNoteId()
        val recording = RecordingState(
            session = RecordingSession(
                id = newNoteId(),
                createdAtMillis = 1,
                phase = RecordingPhase.Recording,
                currentSegment = 1,
                chunks = listOf(RecordingChunk(segment = 1, index = 1, samples = 16_000)),
                noteId = noteId,
            ),
        )
        compose.setContent {
            PimTheme {
                Column {
                    NoteEditorTopBar(
                        saved = true,
                        saveFailed = false,
                        busy = false,
                        preview = false,
                        onBack = {},
                        onSaveStatus = {},
                        onPreview = {},
                        onFinish = {},
                        onMaterials = {},
                        onRecordings = {},
                        onExport = {},
                        onImport = {},
                        onAbout = {},
                    )
                    NoteEditorDock(
                        recording = recording,
                        ownActive = true,
                        activeOtherTitle = null,
                        canRecord = true,
                        canInsert = true,
                        onRecord = {},
                        onPauseResume = {},
                        onRecordings = {},
                        onPhoto = {},
                        onInk = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("note-save-status", useUnmergedTree = true).assertTextEquals("已保存本机")
        compose.onNode(hasTestTag("recording-pill") and hasAnyAncestor(hasTestTag("note-dock"))).assertExists()
        compose.onNode(
            hasTestTag("note-top-bar") and hasAnyDescendant(hasText("录音中", substring = true)),
        ).assertDoesNotExist()
    }

    private fun document(title: String): NoteDocument = NoteDocument.create().withTitle(title)

    private fun state(vararg documents: NoteDocument) = NotesState(
        loaded = true,
        entries = documents.map { NoteEntry(it.id, it.title) },
        documents = documents.associateBy { it.id },
    )

    private fun uploadJob(noteId: String, phase: NoteUploadPhase): NoteUploadJob = NoteUploadJob(
        id = newNoteId(),
        versionId = newNoteId(),
        noteId = noteId,
        revision = 1,
        serverIdentity = "https://pim.example.test",
        ownerId = "owner",
        ownerName = "测试账号",
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        baseVersionId = null,
        createdAtMillis = 1,
        phase = phase,
        totalBytes = 1024,
        problem = "测试网络错误",
    )
}

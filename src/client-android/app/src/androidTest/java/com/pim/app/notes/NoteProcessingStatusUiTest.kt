package com.pim.app.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.NoteProcessingChainDto
import com.pim.core.contract.NoteProcessingChainState
import com.pim.core.contract.NoteProcessingState
import com.pim.core.contract.NoteProcessingStatusDto
import com.pim.core.contract.NoteProcessingTaskKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NoteProcessingStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun panelSeparatesMaterialReceiptAsrAndDerivedStatesAndExposesRefresh() {
        var refreshes = 0
        compose.setContent {
            PimTheme {
                NoteProcessingStatusDetails(
                    tag = "ui",
                    materialReceipt = "整版已确认；尚无服务器分块回执",
                    localAsr = "本机任务 · 转写：处理中",
                    localDerived = "本机任务 · 整理：等待转写完成",
                    server = NoteProcessingStatusDto(
                        artifacts = emptyList(),
                        asr = chain(NoteProcessingTaskKind.ASR, NoteProcessingChainState.RUNNING, 2, 5),
                        autoProcessingEnabled = true,
                        autoStarted = true,
                        createdAt = "2026-11-23T00:00:00Z",
                        derived = chain(
                            NoteProcessingTaskKind.DERIVED,
                            NoteProcessingChainState.WAITINGFORASR,
                            0,
                            2,
                            waitingReason = "等待转写完成",
                        ),
                        incrementalScopeSupported = true,
                        issues = emptyList(),
                        manualStartAvailable = true,
                        noteId = "11111111-1111-4111-8111-111111111111",
                        processingId = "22222222-2222-4222-8222-222222222222",
                        scope = "incremental",
                        state = NoteProcessingState.PROCESSING,
                        updatedAt = "2026-11-23T00:01:00Z",
                        versionId = "33333333-3333-4333-8333-333333333333",
                    ).toNoteProcessingStatusView(
                        "11111111-1111-4111-8111-111111111111",
                        "33333333-3333-4333-8333-333333333333",
                    ),
                    problem = null,
                    syncing = false,
                    onRefresh = { refreshes++ },
                )
            }
        }

        compose.onNodeWithTag("note-processing-overall-ui").assertIsDisplayed()
        compose.onNodeWithTag("note-processing-material-ui").assertTextContains("材料上传回执", substring = true)
        compose.onNodeWithTag("note-processing-asr-ui").assertTextContains("本机任务 · 转写：处理中", substring = true)
        compose.onNodeWithTag("note-processing-derived-ui").assertTextContains("本机任务 · 整理：等待转写完成", substring = true)
        compose.onNodeWithTag("note-processing-server-asr-ui").assertTextContains("转写（ASR）：处理中", substring = true)
        compose.onNodeWithTag("note-processing-server-derived-ui").assertTextContains("整理（派生）：等待转写完成", substring = true)
        compose.onNodeWithTag("note-processing-refresh-ui").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    private fun chain(
        kind: NoteProcessingTaskKind,
        state: NoteProcessingChainState,
        completed: Int,
        total: Int,
        waitingReason: String? = null,
    ) = NoteProcessingChainDto(
        completedItems = completed,
        errors = emptyList(),
        failedItems = 0,
        hasResult = false,
        kind = kind,
        phase = null,
        revision = null,
        state = state,
        taskCreated = true,
        taskId = null,
        totalItems = total,
        waitingReason = waitingReason,
    )
}

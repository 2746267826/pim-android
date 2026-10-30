package com.pim.app.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.app.ui.theme.PimTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteArtifactScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fixedSourceCoverageAndIndependentKindsAreExposed() {
        var selectedKind: NoteArtifactKind? = null
        compose.setContent {
            PimTheme {
                NoteArtifactScreen(
                    state = sampleState(),
                    actions = NoteArtifactScreenActions(
                        selectKind = { selectedKind = it },
                    ),
                )
            }
        }

        compose.onNodeWithTag("note-artifact-material").assertIsDisplayed()
        compose.onNodeWithText("https://pim.example").assertIsDisplayed()
        compose.onNodeWithText("44444444-4444-4444-8444-444444444444").assertIsDisplayed()
        compose.onNodeWithTag("note-artifact-coverage").assertIsDisplayed()
        compose.onNodeWithText("转写缺少 2/4").assertIsDisplayed()
        compose.onNodeWithText("照片缺少 2/3").assertIsDisplayed()
        compose.onNodeWithTag("note-artifact-kind-FullRecord").assertIsSelected()
        compose.onNodeWithTag("note-artifact-kind-Analysis").performClick()
        assertEquals(NoteArtifactKind.Analysis, selectedKind)
    }

    @Test
    fun dirtyTextAndBaseSurviveCandidateViewSelectionAndRefresh() {
        var state by mutableStateOf(sampleState())
        compose.setContent {
            PimTheme {
                NoteArtifactScreen(
                    state = state,
                    actions = NoteArtifactScreenActions(
                        changeContentView = { _, view ->
                            state = state.withEditor(
                                state.fullRecord.copy(contentView = view),
                            )
                        },
                        viewCandidateSource = { _, candidateId ->
                            state = state.withEditor(
                                state.fullRecord.withViewedCandidate(candidateId),
                            )
                        },
                        selectCandidate = { _, candidateId ->
                            state = state.withEditor(
                                state.fullRecord
                                    .withSelectedCandidate(candidateId)
                                    .withRefreshedCandidates(
                                        updatedCandidates = state.fullRecord.candidates,
                                    ),
                            )
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("note-artifact-editor-FullRecord", useUnmergedTree = true)
            .assertTextContains("本机未同步草稿")
        compose.onNodeWithTag("note-artifact-base-FullRecord")
            .assertTextContains("server-base-1")
        compose.onNodeWithTag("note-artifact-view-source-FullRecord-candidate-1")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("note-artifact-server-text-FullRecord-candidate-1")
            .assertTextContains("服务端候选原文一")
        compose.onNodeWithText("此区域只显示服务端候选原文，不包含本机未同步修改。")
            .assertIsDisplayed()

        compose.onNodeWithTag("note-artifact-select-FullRecord-candidate-2")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("note-artifact-view-local").performScrollTo().performClick()
        compose.onNodeWithTag("note-artifact-editor-FullRecord", useUnmergedTree = true)
            .assertTextContains("本机未同步草稿")
        compose.onNodeWithTag("note-artifact-base-FullRecord")
            .assertTextContains("server-base-1")
        compose.onNodeWithTag("note-artifact-dirty-state-FullRecord")
            .assertTextContains("本机未同步")
    }

    @Test
    fun explicitSaveRegenerateStopAndRecoveryCallbacksAreSeparate() {
        var state by mutableStateOf(
            sampleState().let { root ->
                root.withEditor(root.fullRecord.copy(workPhase = NoteArtifactWorkPhase.Running))
                    .copy(selectedKind = NoteArtifactKind.FullRecord)
            },
        )
        val events = mutableListOf<String>()
        compose.setContent {
            PimTheme {
                NoteArtifactScreen(
                    state = state,
                    actions = NoteArtifactScreenActions(
                        changeDraftText = {
                            events += "edit:${it.kind.name}:${it.expectedLocalRevision}"
                        },
                        saveLocalDraft = { events += "save:${it.kind.name}" },
                        regenerate = { events += "regenerate:${it.name}" },
                        stop = { events += "stop:${it.name}" },
                        recover = { events += "recover:${it.name}" },
                    ),
                )
            }
        }

        compose.onNodeWithTag("note-artifact-editor-FullRecord", useUnmergedTree = true)
            .performScrollTo()
            .performTextReplacement("编辑后的本机稿")
        compose.onNodeWithTag("note-artifact-save-FullRecord").performScrollTo().performClick()
        compose.onNodeWithTag("note-artifact-regenerate-FullRecord").assertIsNotEnabled()
        compose.onNodeWithTag("note-artifact-stop-FullRecord").performScrollTo().performClick()
        assertEquals(
            listOf("edit:FullRecord:3", "save:FullRecord", "stop:FullRecord"),
            events,
        )

        compose.runOnUiThread {
            state = state.withEditor(
                state.fullRecord.copy(workPhase = NoteArtifactWorkPhase.Unknown),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("note-artifact-regenerate-FullRecord")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("note-artifact-recover-FullRecord")
            .performScrollTo()
            .performClick()
        assertTrue(events.contains("regenerate:FullRecord"))
        assertTrue(events.contains("recover:FullRecord"))
    }

    @Test
    fun narrowLargeTextLayoutKeepsPrimaryActionsScrollable() {
        compose.setContent {
            PimTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 2f),
                ) {
                    Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                        NoteArtifactScreen(
                            state = sampleState().let { root ->
                                root.withEditor(
                                    root.fullRecord.copy(workPhase = NoteArtifactWorkPhase.Unknown),
                                )
                            },
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("note-artifact-kind-tabs").assertIsDisplayed()
        compose.onNodeWithTag("note-artifact-save-FullRecord").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("note-artifact-regenerate-FullRecord")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("note-artifact-recover-FullRecord")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("note-artifact-select-FullRecord-candidate-1")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun sampleState(): NoteArtifactUiState {
        val draft = NoteArtifactDraft(
            localDraftId = "11111111-1111-4111-8111-111111111111",
            serverIdentity = "https://pim.example",
            ownerId = "22222222-2222-4222-8222-222222222222",
            noteId = "33333333-3333-4333-8333-333333333333",
            materialVersionId = "44444444-4444-4444-8444-444444444444",
            kind = NoteArtifactKind.FullRecord,
            localRevision = 3,
            text = "本机未同步草稿",
            savedText = "上次保存文本",
            dirty = true,
            baseServerArtifactVersionId = "server-base-1",
            expectedServerRevision = "server-revision-1",
            createdAtMillis = 1_000,
            updatedAtMillis = 3_000,
            savedAtMillis = 2_000,
        )
        val candidates = listOf(
            candidate(
                id = "candidate-1",
                origin = NoteArtifactCandidateOrigin.ModelOriginal,
                text = "服务端候选原文一",
                isHead = true,
            ),
            candidate(
                id = "candidate-2",
                origin = NoteArtifactCandidateOrigin.ManualRevision,
                text = "服务端人工稿二",
            ),
            candidate(
                id = "candidate-3",
                origin = NoteArtifactCandidateOrigin.FollowUpCandidate,
                text = "服务端后续候选三",
            ),
        )
        return NoteArtifactUiState(
            material = NoteArtifactMaterialUi(
                title = "固定材料标题",
                summary = "2 次录音 · 固定版本，不随当前草稿变化",
                serverLabel = "https://pim.example",
                ownerLabel = "测试账号",
                noteId = "33333333-3333-4333-8333-333333333333",
                materialVersionId = "44444444-4444-4444-8444-444444444444",
                fixedAtLabel = "2026-10-29 10:00",
            ),
            coverage = NoteArtifactCoverageUi(
                textBlocks = 3,
                transcriptSegments = 4,
                completedTranscriptSegments = 2,
                photos = 3,
                completedPhotos = 1,
                inkPreviews = 2,
                completedInkPreviews = 0,
                visionBatches = 5,
                completedVisionBatches = 4,
                serverReportsComplete = false,
                issues = listOf(NoteArtifactIssueUi("ImageMissing", "照片文件不可读")),
            ),
            selectedKind = NoteArtifactKind.FullRecord,
            fullRecord = NoteArtifactEditorUiState(
                kind = NoteArtifactKind.FullRecord,
                draft = draft,
                candidates = candidates,
                selectedCandidateId = "candidate-1",
                viewedCandidateId = "candidate-1",
                contentView = NoteArtifactContentView.LocalDraft,
                workPhase = NoteArtifactWorkPhase.Ready,
                workDetail = "服务端已返回候选，等待明确选择或本机保存。",
            ),
            analysis = NoteArtifactEditorUiState(
                kind = NoteArtifactKind.Analysis,
                draft = draft.copy(
                    kind = NoteArtifactKind.Analysis,
                    text = "独立分析本机稿",
                    savedText = "独立分析本机稿",
                    dirty = false,
                ),
            ),
        )
    }

    private fun candidate(
        id: String,
        origin: NoteArtifactCandidateOrigin,
        text: String,
        isHead: Boolean = false,
    ) = NoteArtifactCandidateUi(
        candidateId = id,
        serverVersionId = "server-version-$id",
        origin = origin,
        text = text,
        createdAtLabel = "2026-10-29 10:00",
        baseServerArtifactVersionId = "server-base-1",
        expectedServerRevision = "server-revision-1",
        isServerHead = isHead,
    )
}

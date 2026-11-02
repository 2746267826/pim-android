package com.pim.app.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteTaskSuggestionsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun boundedListShowsEditableActionsAndRequiresExplicitCreateConfirmation() {
        val suggestion = suggestion()
        val source = NoteTaskSuggestionSource(
            noteId = suggestion.noteId,
            materialVersionId = MATERIAL_ID,
            artifactId = suggestion.artifactId,
            versionId = suggestion.versionId,
            contentSha256 = suggestion.sourceContentSha256,
            inputSha256 = suggestion.sourceInputSha256,
            manifestSha256 = suggestion.sourceManifestSha256,
            title = "长页面布局回归",
        )
        val state = NoteTaskSuggestionsState(
            loaded = true,
            source = source,
            binding = com.pim.core.network.BoundUploadSession("https://pim.example/", OWNER_ID),
            identityGeneration = 9,
            screenGeneration = 12,
            sourceVerified = true,
            sourceCharacterCount = 120,
            sourceContent = "固定服务端原文",
            items = listOf(NoteTaskSuggestionItem(suggestion)),
        )
        val confirmations = mutableListOf<String>()
        val scope = requireNotNull(state.actionScope)
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                    NoteTaskSuggestionsScreen(
                        state = state,
                        actions = NoteTaskSuggestionsActions(confirm = { actionScope, id ->
                            assertEquals(scope, actionScope)
                            confirmations += id
                        }),
                    )
                }
            }
        }

        compose.onNodeWithTag("task-suggestions-list").assertIsDisplayed()
        compose.onNodeWithTag("task-suggestion-confirm-$SUGGESTION_ID")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("task-suggestion-confirm-dialog-$SUGGESTION_ID")
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(SUGGESTION_ID), confirmations)
    }

    @Test
    fun priorityReplacementThenTitleAndDescriptionKeepSaveAndConfirmationConsistent() {
        val suggestion = suggestion()
        val renderedState = mutableStateOf(stateFor(suggestion))
        val edits = mutableListOf<Triple<String, String, Int>>()
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 360.dp, height = 800.dp)) {
                    NoteTaskSuggestionsScreen(
                        state = renderedState.value,
                        actions = NoteTaskSuggestionsActions(
                            edit = { _, _, title, description, priority, _ ->
                                edits += Triple(title, description, priority)
                                val draft = NoteTaskSuggestionDraft(
                                    suggestionId = SUGGESTION_ID,
                                    expectedRevision = suggestion.revision,
                                    title = title,
                                    description = description,
                                    priority = priority,
                                    due = "",
                                    dirty = true,
                                    updatedAtMillis = edits.size.toLong(),
                                )
                                renderedState.value = renderedState.value.copy(
                                    items = listOf(NoteTaskSuggestionItem(suggestion, draft)),
                                )
                            },
                            save = { _, _ ->
                                val item = renderedState.value.items.single()
                                renderedState.value = renderedState.value.copy(
                                    items = listOf(item.copy(draft = requireNotNull(item.draft).copy(dirty = false))),
                                )
                            },
                        ),
                    )
                }
            }
        }

        compose.onNodeWithTag("task-suggestion-priority-$SUGGESTION_ID")
            .performScrollTo()
            .performTextReplacement("9")
        compose.onNodeWithTag("task-suggestion-priority-$SUGGESTION_ID").assertTextEquals("9")
        compose.onNodeWithTag("task-suggestion-title-$SUGGESTION_ID")
            .performScrollTo()
            .performTextReplacement("修订标题")
        compose.onNodeWithTag("task-suggestion-description-$SUGGESTION_ID")
            .performScrollTo()
            .performTextReplacement("修订说明")

        assertEquals(Triple("修订标题", "修订说明", 9), edits.last())
        compose.onNodeWithTag("task-suggestion-save-$SUGGESTION_ID")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("task-suggestion-confirm-$SUGGESTION_ID")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("将使用当前保存版本创建任务：修订标题", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("优先级 9", substring = true).assertIsDisplayed()
    }

    @Test
    fun restoredAndRebasedDraftsSyncButDiscardResetsFieldsToServerValues() {
        val suggestion = suggestion()
        val renderedState = mutableStateOf(stateFor(suggestion))
        val discarded = mutableListOf<String>()
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 360.dp, height = 800.dp)) {
                    NoteTaskSuggestionsScreen(
                        state = renderedState.value,
                        actions = NoteTaskSuggestionsActions(
                            edit = { _, _, _, _, _, _ -> },
                            discard = { _, id ->
                                discarded += id
                                renderedState.value = renderedState.value.copy(
                                    items = listOf(NoteTaskSuggestionItem(suggestion)),
                                )
                            },
                        ),
                    )
                }
            }
        }

        val restored = draft(suggestion, "恢复标题", "恢复说明", 9, "2026-11-03T09:00")
        compose.runOnIdle {
            renderedState.value = renderedState.value.copy(
                items = listOf(NoteTaskSuggestionItem(suggestion, restored)),
            )
        }
        compose.onNodeWithTag("task-suggestion-title-$SUGGESTION_ID").assertTextEquals("恢复标题")
        compose.onNodeWithTag("task-suggestion-description-$SUGGESTION_ID").assertTextEquals("恢复说明")
        compose.onNodeWithTag("task-suggestion-priority-$SUGGESTION_ID").assertTextEquals("9")
        compose.onNodeWithTag("task-suggestion-due-$SUGGESTION_ID").assertTextEquals("2026-11-03T09:00")

        val rebased = restored.copy(
            expectedRevision = "revision-2",
            title = "重定基线标题",
            description = "重定基线说明",
            priority = 1,
            due = "2026-11-04T10:00",
        )
        compose.runOnIdle {
            renderedState.value = renderedState.value.copy(
                items = listOf(NoteTaskSuggestionItem(suggestion, rebased)),
            )
        }
        compose.onNodeWithTag("task-suggestion-title-$SUGGESTION_ID").assertTextEquals("重定基线标题")
        compose.onNodeWithTag("task-suggestion-description-$SUGGESTION_ID").assertTextEquals("重定基线说明")
        compose.onNodeWithTag("task-suggestion-priority-$SUGGESTION_ID").assertTextEquals("1")
        compose.onNodeWithTag("task-suggestion-due-$SUGGESTION_ID").assertTextEquals("2026-11-04T10:00")

        compose.onNodeWithTag("task-suggestion-title-$SUGGESTION_ID")
            .performTextReplacement("用户正在输入")
        compose.runOnIdle {
            renderedState.value = renderedState.value.copy(
                items = listOf(NoteTaskSuggestionItem(suggestion, rebased.copy(title = "迟到的旧回执"))),
            )
        }
        compose.onNodeWithTag("task-suggestion-title-$SUGGESTION_ID").assertTextEquals("用户正在输入")

        compose.onNodeWithTag("task-suggestion-discard-$SUGGESTION_ID")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("task-suggestion-title-$SUGGESTION_ID").assertTextEquals("建议任务")
        compose.onNodeWithTag("task-suggestion-description-$SUGGESTION_ID").assertTextEquals("")
        compose.onNodeWithTag("task-suggestion-priority-$SUGGESTION_ID").assertTextEquals("5")
        compose.onNodeWithTag("task-suggestion-due-$SUGGESTION_ID").assertTextEquals("")
        assertEquals(listOf(SUGGESTION_ID), discarded)

        compose.onNodeWithTag("task-suggestion-confirm-$SUGGESTION_ID")
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("将使用当前保存版本创建任务：建议任务", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("优先级 5", substring = true).assertIsDisplayed()
    }

    @Test
    fun confirmationDialogIsDiscardedWhenSourceOrSessionScopeChanges() {
        val suggestion = suggestion()
        val source = NoteTaskSuggestionSource(
            noteId = suggestion.noteId,
            materialVersionId = MATERIAL_ID,
            artifactId = suggestion.artifactId,
            versionId = suggestion.versionId,
            contentSha256 = suggestion.sourceContentSha256,
            inputSha256 = suggestion.sourceInputSha256,
            manifestSha256 = suggestion.sourceManifestSha256,
        )
        val binding = com.pim.core.network.BoundUploadSession("https://pim.example/", OWNER_ID)
        val initial = NoteTaskSuggestionsState(
            loaded = true,
            source = source,
            binding = binding,
            identityGeneration = 9,
            screenGeneration = 12,
            sourceVerified = true,
            items = listOf(NoteTaskSuggestionItem(suggestion)),
        )
        val confirmations = mutableListOf<String>()
        val renderedState = mutableStateOf(initial)
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                    NoteTaskSuggestionsScreen(
                        state = renderedState.value,
                        actions = NoteTaskSuggestionsActions(confirm = { _, id -> confirmations += id }),
                    )
                }
            }
        }

        compose.onNodeWithTag("task-suggestion-confirm-$SUGGESTION_ID")
            .performScrollTo()
            .performClick()
        val nextSource = source.copy(versionId = "77777777-7777-4777-8777-777777777777")
        val next = initial.copy(
            source = nextSource,
            binding = binding.copy(ownerId = OTHER_OWNER_ID),
            identityGeneration = 10,
            screenGeneration = 13,
            items = emptyList(),
        )
        compose.runOnIdle { renderedState.value = next }
        compose.onAllNodesWithTag("task-suggestion-confirm-dialog-$SUGGESTION_ID").assertCountEquals(0)
        assertEquals(emptyList<String>(), confirmations)
    }

    @Test
    fun destinationSelectionUsesModelRevisionAndEndpointNotOnlyModelId() {
        val suggestion = suggestion()
        val source = NoteTaskSuggestionSource(
            noteId = suggestion.noteId,
            materialVersionId = MATERIAL_ID,
            artifactId = suggestion.artifactId,
            versionId = suggestion.versionId,
            contentSha256 = suggestion.sourceContentSha256,
            inputSha256 = suggestion.sourceInputSha256,
            manifestSha256 = suggestion.sourceManifestSha256,
        )
        val first = NoteTaskSuggestionDestination(
            modelId = "model-profile",
            modelRevision = "revision-1",
            displayName = "Calendar",
            model = "text-model",
            endpoint = "https://models.example/v1/one",
        )
        val second = first.copy(
            modelRevision = "revision-2",
            endpoint = "https://models.example/v1/two",
        )
        val state = NoteTaskSuggestionsState(
            loaded = true,
            source = source,
            binding = com.pim.core.network.BoundUploadSession("https://pim.example/", OWNER_ID),
            identityGeneration = 9,
            screenGeneration = 12,
            destinations = listOf(first, second),
            destinationsBinding = com.pim.core.network.BoundUploadSession("https://pim.example/", OWNER_ID),
            sourceVerified = true,
            sourceCharacterCount = 120,
            sourceContent = "固定服务端原文",
        )
        var submitted: NoteTaskSuggestionDestination? = null
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 360.dp, height = 800.dp)) {
                    NoteTaskSuggestionsScreen(
                        state = state,
                        actions = NoteTaskSuggestionsActions(extract = { _, destination, acknowledged ->
                            assertEquals(true, acknowledged)
                            submitted = destination
                        }),
                    )
                }
            }
        }

        compose.onNodeWithTag("task-suggestions-destination").performClick()
        compose.onNodeWithText("Calendar · text-model · 修订 revision-2").performClick()
        compose.onNodeWithTag("task-suggestions-destination")
            .assertTextContains("修订 revision-2")
        compose.onNodeWithTag("task-suggestions-charge-acknowledgement").performClick()
        compose.onNodeWithTag("task-suggestions-extract").performScrollTo().performClick()

        assertEquals(second, submitted)
    }

    @Test
    fun dirtyDraftCannotBeDismissedAndTerminalRemoteStatusKeepsLocalDraftVisible() {
        val draft = NoteTaskSuggestionDraft(
            suggestionId = SUGGESTION_ID,
            expectedRevision = "revision-1",
            title = "未同步标题",
            description = "未同步说明",
            priority = 9,
            due = "2026-11-03T09:00",
            dirty = true,
            updatedAtMillis = 1_000,
        )
        val dirtyItem = NoteTaskSuggestionItem(suggestion(), draft)
        val state = NoteTaskSuggestionsState(items = listOf(dirtyItem))
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                    NoteTaskSuggestionsScreen(state = state)
                }
            }
        }

        compose.onNodeWithTag("task-suggestion-dismiss-$SUGGESTION_ID")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun terminalSuggestionExposesDirtyDraftUntilUserExplicitlyDeletesIt() {
        val draft = NoteTaskSuggestionDraft(
            suggestionId = SUGGESTION_ID,
            expectedRevision = "revision-1",
            title = "未同步标题",
            description = "未同步说明",
            priority = 9,
            due = "2026-11-03T09:00",
            dirty = true,
            updatedAtMillis = 1_000,
        )
        val discarded = mutableListOf<String>()
        val suggestion = suggestion().copy(status = NoteTaskSuggestionStatusDto.DISMISSED)
        val source = NoteTaskSuggestionSource(
            noteId = suggestion.noteId,
            materialVersionId = MATERIAL_ID,
            artifactId = suggestion.artifactId,
            versionId = suggestion.versionId,
            contentSha256 = suggestion.sourceContentSha256,
            inputSha256 = suggestion.sourceInputSha256,
            manifestSha256 = suggestion.sourceManifestSha256,
        )
        compose.setContent {
            PimTheme {
                Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                    NoteTaskSuggestionsScreen(
                        state = NoteTaskSuggestionsState(
                            loaded = true,
                            source = source,
                            binding = com.pim.core.network.BoundUploadSession("https://pim.example/", OWNER_ID),
                            identityGeneration = 9,
                            screenGeneration = 12,
                            sourceVerified = true,
                            items = listOf(NoteTaskSuggestionItem(suggestion, draft)),
                        ),
                        actions = NoteTaskSuggestionsActions(discard = { _, id -> discarded += id }),
                    )
                }
            }
        }

        compose.onNodeWithTag("task-suggestion-discard-terminal-$SUGGESTION_ID")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf(SUGGESTION_ID), discarded)
    }

    private fun suggestion() = NoteTaskSuggestionDto(
        artifactId = ARTIFACT_ID,
        createdAt = "2026-11-02T00:00:00Z",
        extractionId = EXTRACTION_ID,
        id = SUGGESTION_ID,
        noteId = NOTE_ID,
        ordinal = 0,
        priority = 5,
        revision = "revision-1",
        sourceContentSha256 = "a".repeat(64),
        sourceInputSha256 = "b".repeat(64),
        sourceManifestSha256 = "c".repeat(64),
        status = NoteTaskSuggestionStatusDto.DRAFT,
        title = "建议任务",
        updatedAt = "2026-11-02T00:00:00Z",
        versionId = VERSION_ID,
    )

    private fun stateFor(
        suggestion: NoteTaskSuggestionDto,
        draft: NoteTaskSuggestionDraft? = null,
    ) = NoteTaskSuggestionsState(
        loaded = true,
        source = NoteTaskSuggestionSource(
            noteId = suggestion.noteId,
            materialVersionId = MATERIAL_ID,
            artifactId = suggestion.artifactId,
            versionId = suggestion.versionId,
            contentSha256 = suggestion.sourceContentSha256,
            inputSha256 = suggestion.sourceInputSha256,
            manifestSha256 = suggestion.sourceManifestSha256,
        ),
        binding = com.pim.core.network.BoundUploadSession("https://pim.example/", OWNER_ID),
        identityGeneration = 9,
        screenGeneration = 12,
        sourceVerified = true,
        items = listOf(NoteTaskSuggestionItem(suggestion, draft)),
    )

    private fun draft(
        suggestion: NoteTaskSuggestionDto,
        title: String,
        description: String,
        priority: Int,
        due: String,
    ) = NoteTaskSuggestionDraft(
        suggestionId = suggestion.id,
        expectedRevision = suggestion.revision,
        title = title,
        description = description,
        priority = priority,
        due = due,
        dirty = true,
        updatedAtMillis = 2_000,
    )

    private companion object {
        const val OWNER_ID = "99999999-9999-4999-8999-999999999999"
        const val OTHER_OWNER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val NOTE_ID = "11111111-1111-4111-8111-111111111111"
        const val MATERIAL_ID = "22222222-2222-4222-8222-222222222222"
        const val ARTIFACT_ID = "33333333-3333-4333-8333-333333333333"
        const val VERSION_ID = "44444444-4444-4444-8444-444444444444"
        const val EXTRACTION_ID = "55555555-5555-4555-8555-555555555555"
        const val SUGGESTION_ID = "66666666-6666-4666-8666-666666666666"
    }
}

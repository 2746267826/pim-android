package com.pim.app.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        compose.onNodeWithTag("task-suggestion-confirm-dialog-$SUGGESTION_ID").assertDoesNotExist()
        assertEquals(emptyList<String>(), confirmations)
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

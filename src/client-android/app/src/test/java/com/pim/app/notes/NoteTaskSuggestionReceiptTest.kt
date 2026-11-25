package com.pim.app.notes

import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionKindDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteTaskSuggestionReceiptTest {
    @Test
    fun eventReceiptAcceptsAnEmptyTaskIdButRequiresAnEventId() {
        val draft = suggestion(NoteTaskSuggestionKindDto.EVENT)
        val confirmed = draft.copy(
            status = NoteTaskSuggestionStatusDto.CONFIRMED,
            taskId = null,
            eventId = EVENT_ID,
        )

        requireConfirmationReceipt(draft, confirmed)
        assertThrows(IllegalArgumentException::class.java) {
            requireConfirmationReceipt(draft, confirmed.copy(eventId = null))
        }
    }

    @Test
    fun taskReceiptStillRequiresATaskId() {
        val draft = suggestion(NoteTaskSuggestionKindDto.TASK)
        val confirmed = draft.copy(status = NoteTaskSuggestionStatusDto.CONFIRMED, taskId = null)

        assertThrows(IllegalArgumentException::class.java) {
            requireConfirmationReceipt(draft, confirmed)
        }
    }

    private fun suggestion(kind: NoteTaskSuggestionKindDto) = NoteTaskSuggestionDto(
        artifactId = ARTIFACT_ID,
        createdAt = "2026-11-20T00:00:00Z",
        extractionId = EXTRACTION_ID,
        id = SUGGESTION_ID,
        kind = kind,
        noteId = NOTE_ID,
        ordinal = 0,
        priority = 5,
        revision = "revision-1",
        sourceContentSha256 = "a".repeat(64),
        sourceInputSha256 = "b".repeat(64),
        sourceManifestSha256 = "c".repeat(64),
        status = NoteTaskSuggestionStatusDto.DRAFT,
        title = "建议",
        updatedAt = "2026-11-20T00:00:00Z",
        versionId = VERSION_ID,
        start = if (kind == NoteTaskSuggestionKindDto.EVENT) "2026-11-21T11:00:00Z" else null,
        end = if (kind == NoteTaskSuggestionKindDto.EVENT) "2026-11-21T12:00:00Z" else null,
        timeZoneId = if (kind == NoteTaskSuggestionKindDto.EVENT) "Asia/Shanghai" else null,
    )

    private companion object {
        const val NOTE_ID = "11111111-1111-4111-8111-111111111111"
        const val ARTIFACT_ID = "22222222-2222-4222-8222-222222222222"
        const val VERSION_ID = "33333333-3333-4333-8333-333333333333"
        const val EXTRACTION_ID = "44444444-4444-4444-8444-444444444444"
        const val SUGGESTION_ID = "55555555-5555-4555-8555-555555555555"
        const val EVENT_ID = "66666666-6666-4666-8666-666666666666"
    }
}

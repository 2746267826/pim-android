package com.pim.app.notes

import com.pim.core.contract.NoteDerivedGenerationConsent
import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionExtractionDto
import com.pim.core.contract.StartNoteTaskSuggestionExtractionRequest
import com.pim.core.network.BoundUploadSession
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

data class NoteTaskSuggestionSource(
    val noteId: String,
    val materialVersionId: String,
    val artifactId: String,
    val versionId: String,
    val contentSha256: String,
    val inputSha256: String,
    val manifestSha256: String,
    val generationTaskId: String? = null,
    val title: String = "",
)

data class NoteTaskSuggestionTarget(
    val binding: BoundUploadSession,
    val ownerName: String,
    val identityGeneration: Long,
)

data class NoteTaskSuggestionDestination(
    val modelId: String,
    val modelRevision: String,
    val displayName: String,
    val model: String,
    val endpoint: String,
)

data class NoteTaskSuggestionItem(
    val remote: NoteTaskSuggestionDto,
    val draft: NoteTaskSuggestionDraft? = null,
) {
    val hasConflict: Boolean
        get() = draft != null && draft.expectedRevision != remote.revision
    val title: String get() = draft?.title ?: remote.title
    val description: String get() = draft?.description ?: remote.description.orEmpty()
    val priority: Int get() = draft?.priority ?: remote.priority
    val due: String get() = draft?.due ?: suggestionDueInput(remote.due)
    val dirty: Boolean get() = draft?.dirty == true
    val confirmationPending: Boolean get() = draft?.confirmRequestId != null
    val confirmationBlocked: Boolean
        get() = confirmationPending && draft?.confirmRevision != remote.revision
}

data class NoteTaskSuggestionsState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val source: NoteTaskSuggestionSource? = null,
    val binding: BoundUploadSession? = null,
    val identityGeneration: Long? = null,
    val screenGeneration: Long? = null,
    val destinations: List<NoteTaskSuggestionDestination> = emptyList(),
    val destinationsBinding: BoundUploadSession? = null,
    val frozenDestination: NoteTaskSuggestionDestination? = null,
    val items: List<NoteTaskSuggestionItem> = emptyList(),
    val extraction: NoteTaskSuggestionExtractionDto? = null,
    val sourceVerified: Boolean = false,
    val sourceCharacterCount: Int = 0,
    val sourceContent: String? = null,
    val frozenExtraction: Boolean = false,
    val loadFailed: Boolean = false,
    val message: String? = null,
) {
    val actionScope: NoteTaskSuggestionActionScope?
        get() = if (loaded && !loading && !loadFailed && sourceVerified && source != null &&
            binding != null && identityGeneration != null && screenGeneration != null
        ) NoteTaskSuggestionActionScope(source, binding, identityGeneration, screenGeneration) else null
}

/** Snapshot carried by UI callbacks so stale screens cannot act on a newly selected source/session. */
data class NoteTaskSuggestionActionScope(
    val source: NoteTaskSuggestionSource,
    val binding: BoundUploadSession,
    val identityGeneration: Long,
    val screenGeneration: Long,
)

@Serializable
data class NoteTaskSuggestionDraft(
    val suggestionId: String,
    val expectedRevision: String,
    val title: String,
    val description: String,
    val priority: Int,
    val due: String,
    val dirty: Boolean,
    val confirmRequestId: String? = null,
    val confirmRevision: String? = null,
    val updatedAtMillis: Long,
)

@Serializable
data class NoteTaskSuggestionScopeRecord(
    val schemaVersion: Int = 1,
    val serverIdentity: String,
    val ownerId: String,
    val noteId: String,
    val materialVersionId: String,
    val artifactId: String,
    val versionId: String,
    val contentSha256: String,
    val inputSha256: String,
    val manifestSha256: String,
    val generationTaskId: String? = null,
    val frozenExtraction: StartNoteTaskSuggestionExtractionRequest? = null,
    val extractionId: String? = null,
    val drafts: List<NoteTaskSuggestionDraft> = emptyList(),
    val updatedAtMillis: Long,
)

data class NoteTaskSuggestionSourceContent(
    val source: NoteTaskSuggestionSource,
    val markdown: String,
)

data class NoteTaskSuggestionConsent(
    val destination: NoteTaskSuggestionDestination,
    val chargeAcknowledged: Boolean,
)

internal fun NoteTaskSuggestionDestination.toWire() = NoteDerivedGenerationConsent(
    confirmed = true,
    endpoint = endpoint,
    modelId = modelId,
    modelRevision = modelRevision,
)

internal fun suggestionDueInput(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}

internal fun suggestionDueWire(value: String): String? {
    if (value.isBlank()) return null
    val local = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    return local.atZone(ZoneId.systemDefault()).toInstant().toString()
}

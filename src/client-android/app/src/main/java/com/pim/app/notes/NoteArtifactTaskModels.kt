package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import kotlinx.serialization.Serializable

/** Local submission state. Server task phase remains a separate opaque wire value. */
@Serializable
enum class NoteArtifactIntentPhase {
    Prepared,
    Submitting,
    VerificationPending,
    Accepted,
    NeedsLogin,
    Unavailable,
    Rejected,
}

/**
 * Consent snapshot pinned when the user confirms a fixed destination. The wire
 * request shape is built only in the generated-contract client layer.
 */
@Serializable
data class NoteArtifactConsent(
    val confirmed: Boolean,
    val modelId: String,
    val modelRevision: String,
    val endpoint: String,
)

@Serializable
data class NoteArtifactRetryIntent(
    val retryId: String,
    val expectedServerRevision: String,
    val stepKeys: List<String>,
    val phase: NoteArtifactIntentPhase = NoteArtifactIntentPhase.Prepared,
    val createdAtMillis: Long,
)

internal fun NoteArtifactRetryIntent.requiresExplicitReplay(): Boolean =
    phase == NoteArtifactIntentPhase.Submitting || phase == NoteArtifactIntentPhase.VerificationPending

/**
 * Last confirmed server view of the task, kept as opaque strings. Generated
 * DTOs are mapped into this local projection by the client layer only, so
 * local persistence never redefines the wire contract.
 */
@Serializable
data class NoteArtifactRemoteTaskView(
    val revision: String,
    val phase: String,
    val inputSha256: String? = null,
    val manifestSha256: String? = null,
    val stopRequested: Boolean = false,
    val completeRecordArtifactId: String? = null,
    val analysisArtifactId: String? = null,
    val observedAtMillis: Long,
)

/**
 * Private durable task state. It never contains an access token, refresh token,
 * or Authorization header. One record covers one fixed material version, the
 * kinds requested by one confirmation, and one exact owner/server binding.
 * [kind] remains the primary/legacy kind for old task files; consumers must use
 * [supports] when matching a UI kind. Missing [kinds] means a pre-field task
 * whose server operation already produced both artifacts.
 */
@Serializable
data class NoteArtifactTaskRecord(
    val taskId: String,
    val noteId: String,
    val materialVersionId: String,
    val sourceTitle: String = "",
    val materialCreatedAtMillis: Long? = null,
    val asrTaskId: String? = null,
    val kind: NoteArtifactKind,
    val kinds: Set<NoteArtifactKind> = NoteArtifactKind.entries.toSet(),
    val inputSha256: String,
    val manifestSha256: String,
    val serverIdentity: String,
    val ownerId: String,
    val ownerName: String,
    val expectedUserId: String,
    val consent: NoteArtifactConsent,
    val destinationDisplayName: String = "",
    val destinationModel: String = "",
    val destinationCapabilities: List<String> = emptyList(),
    val destinationBackgroundAvailable: Boolean? = null,
    val localPhase: NoteArtifactIntentPhase,
    val remote: NoteArtifactRemoteTaskView? = null,
    val pendingRetry: NoteArtifactRetryIntent? = null,
    val stopPhase: NoteArtifactIntentPhase? = null,
    val lastProblem: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun supports(candidate: NoteArtifactKind): Boolean = candidate in kinds

    val binding: BoundUploadSession get() = BoundUploadSession(serverIdentity, ownerId)
    override fun toString() =
        "NoteArtifactTaskRecord(taskId=$taskId, kinds=$kinds, phase=$localPhase, wirePhase=${remote?.phase})"
}

data class NoteArtifactTasksState(
    val loaded: Boolean = false,
    val tasks: List<NoteArtifactTaskRecord> = emptyList(),
    val loadFailed: Boolean = false,
)

/** Local projection of one generated owner-scoped destination catalogue row. */
data class NoteArtifactDestination(
    val modelId: String,
    val modelRevision: String,
    val displayName: String,
    val model: String,
    val endpoint: String,
    val capabilities: List<String>,
    val backgroundProcessingAvailable: Boolean,
) {
    fun asConsent() = NoteArtifactConsent(
        confirmed = true,
        modelId = modelId,
        modelRevision = modelRevision,
        endpoint = endpoint,
    )
}

/** Local projection of the generated task coverage shape. */
data class NoteArtifactCoverageProjection(
    val textBlocks: Int,
    val transcriptSegments: Int,
    val completedTranscriptSegments: Int,
    val photos: Int,
    val completedPhotos: Int,
    val inkPreviews: Int,
    val completedInkPreviews: Int,
    val visionBatches: Int,
    val completedVisionBatches: Int,
    val complete: Boolean,
    val issues: List<NoteArtifactIssueProjection> = emptyList(),
)

data class NoteArtifactIssueProjection(
    val code: String,
    val message: String,
    val sourcePath: String? = null,
    val blockId: String? = null,
    val stepKey: String? = null,
)

data class NoteArtifactStepProjection(
    val key: String,
    val kind: String,
    val status: String,
    val attemptCount: Int,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
)

data class NoteArtifactArtifactRefProjection(
    val artifactId: String,
    val kind: NoteArtifactKind,
    val revision: String? = null,
    val headVersionId: String?,
)

/** Local projection of one task plus its bounded steps and artifact references. */
data class NoteArtifactTaskDetail(
    val view: NoteArtifactRemoteTaskView,
    val coverage: NoteArtifactCoverageProjection,
    val issues: List<NoteArtifactIssueProjection>,
    val steps: List<NoteArtifactStepProjection>,
    val artifacts: List<NoteArtifactArtifactRefProjection>,
)

enum class NoteArtifactVersionAuthor {
    Model,
    User,
}

/** Local projection of one generated artifact version row. */
data class NoteArtifactVersionProjection(
    val versionId: String,
    val artifactId: String,
    val kind: NoteArtifactKind,
    val author: NoteArtifactVersionAuthor,
    val parentVersionId: String?,
    val observedHeadVersionId: String?,
    val sourceCompleteRecordVersionId: String?,
    val sourceVersionId: String,
    val generationTaskId: String?,
    val contentMarkdown: String,
    val contentSha256: String,
    val hasConflict: Boolean,
    val createdAtLabel: String,
    val isHead: Boolean,
)

/** Local projection of the generated manual-revision mutation receipt. */
data class NoteArtifactMutationProjection(
    val artifactRevision: String,
    val versionId: String,
    val hasConflict: Boolean,
    val isHead: Boolean,
)

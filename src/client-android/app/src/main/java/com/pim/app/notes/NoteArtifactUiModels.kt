package com.pim.app.notes

import androidx.compose.runtime.Immutable

/**
 * Compose state owned by the local UI layer.
 *
 * These types are deliberately not serializable and are not wire DTOs. A future
 * network integration is responsible for mapping the generated contract into
 * this local projection before rendering it.
 */
@Immutable
data class NoteArtifactUiState(
    val material: NoteArtifactMaterialUi,
    val coverage: NoteArtifactCoverageUi,
    val selectedKind: NoteArtifactKind = NoteArtifactKind.FullRecord,
    val fullRecord: NoteArtifactEditorUiState =
        NoteArtifactEditorUiState(kind = NoteArtifactKind.FullRecord),
    val analysis: NoteArtifactEditorUiState =
        NoteArtifactEditorUiState(kind = NoteArtifactKind.Analysis),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val message: NoteArtifactMessageUi? = null,
) {
    fun editor(kind: NoteArtifactKind): NoteArtifactEditorUiState = when (kind) {
        NoteArtifactKind.FullRecord -> fullRecord
        NoteArtifactKind.Analysis -> analysis
    }

    fun withEditor(editor: NoteArtifactEditorUiState): NoteArtifactUiState = when (editor.kind) {
        NoteArtifactKind.FullRecord -> copy(fullRecord = editor)
        NoteArtifactKind.Analysis -> copy(analysis = editor)
    }
}

/**
 * Fixed source facts shown above both independent artifacts.
 *
 * Labels are presentation strings. The opaque IDs remain separate so the UI
 * never parses server identifiers into local values.
 */
@Immutable
data class NoteArtifactMaterialUi(
    val title: String,
    val summary: String,
    val serverLabel: String,
    val ownerLabel: String,
    val noteId: String,
    val materialVersionId: String,
    val fixedAtLabel: String,
)

/**
 * Full material coverage is shown even when a count is zero. [gapMessages]
 * makes every missing or inconsistent count visible instead of collapsing the
 * state into a single "incomplete" badge.
 */
@Immutable
data class NoteArtifactCoverageUi(
    val textBlocks: Int,
    val transcriptSegments: Int,
    val completedTranscriptSegments: Int,
    val photos: Int,
    val completedPhotos: Int,
    val inkPreviews: Int,
    val completedInkPreviews: Int,
    val visionBatches: Int,
    val completedVisionBatches: Int,
    val serverReportsComplete: Boolean,
    val issues: List<NoteArtifactIssueUi> = emptyList(),
) {
    val missingTranscriptSegments: Int
        get() = (transcriptSegments - completedTranscriptSegments).coerceAtLeast(0)

    val missingPhotos: Int
        get() = (photos - completedPhotos).coerceAtLeast(0)

    val missingInkPreviews: Int
        get() = (inkPreviews - completedInkPreviews).coerceAtLeast(0)

    val missingVisionBatches: Int
        get() = (visionBatches - completedVisionBatches).coerceAtLeast(0)

    val hasCountAnomaly: Boolean
        get() = completedTranscriptSegments > transcriptSegments ||
            completedPhotos > photos ||
            completedInkPreviews > inkPreviews ||
            completedVisionBatches > visionBatches

    val complete: Boolean
        get() = serverReportsComplete &&
            !hasCountAnomaly &&
            missingTranscriptSegments == 0 &&
            missingPhotos == 0 &&
            missingInkPreviews == 0 &&
            missingVisionBatches == 0 &&
            issues.isEmpty()

    val gapMessages: List<String>
        get() = buildList {
            addCountGap("转写", transcriptSegments, completedTranscriptSegments)
            addCountGap("照片", photos, completedPhotos)
            addCountGap("墨迹预览", inkPreviews, completedInkPreviews)
            addCountGap("图像识别批次", visionBatches, completedVisionBatches)
            if (!serverReportsComplete && isEmpty()) {
                add("服务端仍将该来源标记为未完整覆盖")
            }
            issues.forEach { issue ->
                val code = issue.code.takeIf(String::isNotBlank)?.let { "$it：" }.orEmpty()
                add("$code${issue.message.ifBlank { "未提供覆盖问题说明" }}")
            }
        }

    private fun MutableList<String>.addCountGap(label: String, total: Int, completed: Int) {
        when {
            completed < total -> add("${label}缺少 ${total - completed}/$total")
            completed > total -> add("${label}完成数 $completed 超过总数 $total（服务端数据异常）")
        }
    }
}

@Immutable
data class NoteArtifactIssueUi(
    val code: String,
    val message: String,
)

@Immutable
data class NoteArtifactEditorUiState(
    val kind: NoteArtifactKind,
    val draft: NoteArtifactDraft? = null,
    val candidates: List<NoteArtifactCandidateUi> = emptyList(),
    @Deprecated("Use confirmedHeadCandidateId only after a server head receipt")
    val selectedCandidateId: String? = null,
    /** Candidate confirmed by the server as the artifact head, never a local guess. */
    val confirmedHeadCandidateId: String? = null,
    /** Candidate with an explicit select request currently awaiting a server receipt. */
    val pendingSelectionCandidateId: String? = null,
    val viewedCandidateId: String? = null,
    val contentView: NoteArtifactContentView = NoteArtifactContentView.LocalDraft,
    val workPhase: NoteArtifactWorkPhase = NoteArtifactWorkPhase.Idle,
    val workDetail: String? = null,
    val problem: String? = null,
) {
    val isDirty: Boolean get() = draft?.dirty == true
    val canStop: Boolean get() = workPhase == NoteArtifactWorkPhase.Running
    val canRegenerate: Boolean get() = workPhase != NoteArtifactWorkPhase.Running
    val canRecover: Boolean get() =
        workPhase == NoteArtifactWorkPhase.Failed || workPhase == NoteArtifactWorkPhase.Unknown
    val canSaveManualRevision: Boolean
        get() = draft != null && draft.dirty.not() && confirmedHeadCandidate != null

    val confirmedHeadCandidate: NoteArtifactCandidateUi?
        get() = candidates.firstOrNull { it.candidateId == confirmedHeadCandidateId }

    val viewedCandidate: NoteArtifactCandidateUi?
        get() = candidates.firstOrNull { it.candidateId == viewedCandidateId }

    val localBaseLabel: String
        get() = draft?.baseServerArtifactVersionId?.takeIf(String::isNotBlank)
            ?: "尚无服务端基线"

    fun canSelectCandidate(candidateId: String): Boolean =
        pendingSelectionCandidateId == null &&
            candidateId != confirmedHeadCandidateId &&
            candidates.any { it.candidateId == candidateId }

    fun isSelectingCandidate(candidateId: String): Boolean =
        pendingSelectionCandidateId == candidateId

    /**
     * An in-flight explicit selection is not a confirmed server choice and must
     * never rewrite the local draft or its base.
     */
    fun withPendingSelection(candidateId: String): NoteArtifactEditorUiState =
        copy(pendingSelectionCandidateId = candidateId)

    fun withSelectionSettled(): NoteArtifactEditorUiState =
        copy(pendingSelectionCandidateId = null)

    @Deprecated("Local selection is not a server-confirmed head; refresh metadata after select")
    fun withSelectedCandidate(candidateId: String): NoteArtifactEditorUiState =
        copy(selectedCandidateId = candidateId)

    /**
     * Viewing a server original switches the read-only pane. The local working
     * copy and its base remain untouched.
     */
    fun withViewedCandidate(candidateId: String): NoteArtifactEditorUiState =
        copy(
            viewedCandidateId = candidateId,
            contentView = NoteArtifactContentView.ServerOriginal,
        )

    /**
     * A refresh replaces server candidates/status only. This helper exists to
     * make that boundary explicit for the future network integration.
     */
    fun withRefreshedCandidates(
        updatedCandidates: List<NoteArtifactCandidateUi>,
        updatedConfirmedHeadCandidateId: String? = confirmedHeadCandidateId,
        updatedPendingSelectionCandidateId: String? = pendingSelectionCandidateId,
        updatedViewedCandidateId: String? = viewedCandidateId,
        updatedWorkPhase: NoteArtifactWorkPhase = workPhase,
        updatedWorkDetail: String? = workDetail,
        updatedProblem: String? = problem,
    ): NoteArtifactEditorUiState = copy(
        candidates = updatedCandidates,
        confirmedHeadCandidateId = updatedConfirmedHeadCandidateId,
        pendingSelectionCandidateId = updatedPendingSelectionCandidateId,
        viewedCandidateId = updatedViewedCandidateId,
        workPhase = updatedWorkPhase,
        workDetail = updatedWorkDetail,
        problem = updatedProblem,
    )
}

@Immutable
data class NoteArtifactCandidateUi(
    /** Opaque server/preview identifier. Never parse this as a local draft ID. */
    val candidateId: String,
    val serverVersionId: String,
    val origin: NoteArtifactCandidateOrigin,
    val text: String,
    val createdAtLabel: String,
    val baseServerArtifactVersionId: String? = null,
    val expectedServerRevision: String? = null,
    val isServerHead: Boolean = false,
    val hasConflict: Boolean = false,
)

enum class NoteArtifactCandidateOrigin {
    ModelOriginal,
    ManualRevision,
    FollowUpCandidate,
}

enum class NoteArtifactContentView {
    LocalDraft,
    ServerOriginal,
}

enum class NoteArtifactWorkPhase {
    Idle,
    Running,
    Ready,
    Failed,
    Unknown,
    Stopped,
}

@Immutable
data class NoteArtifactMessageUi(
    val text: String,
    val level: NoteArtifactMessageLevel = NoteArtifactMessageLevel.Info,
)

enum class NoteArtifactMessageLevel {
    Info,
    Warning,
    Error,
}

data class NoteArtifactDraftEditRequest(
    val kind: NoteArtifactKind,
    val localDraftId: String,
    val expectedLocalRevision: Long,
    val text: String,
)

data class NoteArtifactDraftSaveRequest(
    val kind: NoteArtifactKind,
    val localDraftId: String,
    val expectedLocalRevision: Long,
    val text: String,
)

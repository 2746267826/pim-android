package com.pim.app.notes

/** Fixed source selected from a note or a successful ASR task before entering the derived screen. */
data class NoteArtifactEntry(
    val noteId: String,
    val materialVersionId: String,
    val asrTaskId: String? = null,
    val title: String = "",
)

/**
 * Maps durable local records and server projections into the existing pure
 * Compose surface. This function never mutates a draft, task or candidate.
 */
fun NoteArtifactRepositoryState.toUiState(
    entry: NoteArtifactEntry,
    selectedKind: NoteArtifactKind,
    selectedCandidateIds: Map<NoteArtifactKind, String?> = emptyMap(),
    viewedCandidateIds: Map<NoteArtifactKind, String?> = emptyMap(),
    contentViews: Map<NoteArtifactKind, NoteArtifactContentView> = emptyMap(),
): NoteArtifactUiState {
    val newestTask = tasks.maxByOrNull { it.updatedAtMillis }
    val material = NoteArtifactMaterialUi(
        title = newestTask?.sourceTitle?.takeIf(String::isNotBlank)
            ?: entry.title.ifBlank { "未命名固定材料" },
        summary = "完整记录与独立分析使用同一固定材料版本；本机稿与服务端原文分开保留。",
        serverLabel = newestTask?.serverIdentity
            ?: destinationBinding?.serverIdentity
            ?: "当前未登录",
        ownerLabel = newestTask?.ownerName?.takeIf(String::isNotBlank)
            ?: newestTask?.ownerId?.take(8)
            ?: "当前账号",
        noteId = newestTask?.noteId ?: entry.noteId,
        materialVersionId = newestTask?.materialVersionId ?: entry.materialVersionId,
        fixedAtLabel = when {
            newestTask?.materialCreatedAtMillis != null ->
                formatArtifactMillis(newestTask.materialCreatedAtMillis)
            newestTask != null -> formatArtifactMillis(newestTask.createdAtMillis)
            else -> "尚未固定"
        },
    )
    val coverage = detail?.coverage?.toUi() ?: emptyCoverage()
    return NoteArtifactUiState(
        material = material,
        coverage = coverage,
        selectedKind = selectedKind,
        fullRecord = editor(
            kind = NoteArtifactKind.FullRecord,
            selectedCandidateIds = selectedCandidateIds,
            viewedCandidateIds = viewedCandidateIds,
            contentViews = contentViews,
        ),
        analysis = editor(
            kind = NoteArtifactKind.Analysis,
            selectedCandidateIds = selectedCandidateIds,
            viewedCandidateIds = viewedCandidateIds,
            contentViews = contentViews,
        ),
        loading = syncing,
        loadFailed = loadFailed,
        message = message?.let { NoteArtifactMessageUi(it) },
    )
}

private fun NoteArtifactRepositoryState.editor(
    kind: NoteArtifactKind,
    selectedCandidateIds: Map<NoteArtifactKind, String?>,
    viewedCandidateIds: Map<NoteArtifactKind, String?>,
    contentViews: Map<NoteArtifactKind, NoteArtifactContentView>,
): NoteArtifactEditorUiState {
    val task = tasks.filter { it.kind == kind }.maxByOrNull { it.updatedAtMillis }
    val artifact = artifactRefs[kind]
    val history = versions[kind].orEmpty()
    val draft = drafts.filter { it.kind == kind }.maxByOrNull { it.updatedAtMillis }
    val candidates = history.map { version ->
        NoteArtifactCandidateUi(
            candidateId = version.versionId,
            serverVersionId = version.versionId,
            origin = when {
                version.author == NoteArtifactVersionAuthor.User -> NoteArtifactCandidateOrigin.ManualRevision
                version.parentVersionId != null -> NoteArtifactCandidateOrigin.FollowUpCandidate
                else -> NoteArtifactCandidateOrigin.ModelOriginal
            },
            text = version.contentMarkdown,
            createdAtLabel = version.createdAtLabel,
            baseServerArtifactVersionId = version.parentVersionId,
            expectedServerRevision = artifact?.revision,
            isServerHead = version.isHead,
            hasConflict = version.hasConflict,
        )
    }
    val selected = selectedCandidateIds[kind]
        ?: artifact?.headVersionId
        ?: candidates.firstOrNull { it.isServerHead }?.candidateId
    val viewed = viewedCandidateIds[kind] ?: selected
    val taskDetail = detail?.takeIf { selectedTaskId == task?.taskId }
    return NoteArtifactEditorUiState(
        kind = kind,
        draft = draft,
        candidates = candidates,
        selectedCandidateId = selected,
        viewedCandidateId = viewed,
        contentView = contentViews[kind] ?: NoteArtifactContentView.LocalDraft,
        workPhase = task.workPhase(),
        workDetail = task?.let { record ->
            val phase = record.remote?.phase ?: record.localPhase.name
            val steps = taskDetail?.steps.orEmpty()
            val attempts = steps.sumOf { it.attemptCount }
            "TaskId ${record.taskId.take(8)} · $phase · ${steps.size} 个步骤 / $attempts 次尝试"
        },
        problem = task?.lastProblem,
    )
}

private fun NoteArtifactTaskRecord?.workPhase(): NoteArtifactWorkPhase {
    val record = this ?: return NoteArtifactWorkPhase.Idle
    val remote = record.remote
    if (remote == null) {
        return when (record.localPhase) {
            NoteArtifactIntentPhase.Prepared -> NoteArtifactWorkPhase.Idle
            NoteArtifactIntentPhase.Submitting -> NoteArtifactWorkPhase.Running
            NoteArtifactIntentPhase.VerificationPending,
            NoteArtifactIntentPhase.Accepted,
            NoteArtifactIntentPhase.Unavailable,
            -> NoteArtifactWorkPhase.Unknown
            NoteArtifactIntentPhase.NeedsLogin,
            NoteArtifactIntentPhase.Rejected,
            -> NoteArtifactWorkPhase.Failed
        }
    }
    return when (remote.phase) {
        "Queued", "Running" -> NoteArtifactWorkPhase.Running
        "Succeeded" -> NoteArtifactWorkPhase.Ready
        "Stopped" -> NoteArtifactWorkPhase.Stopped
        "Blocked", "NeedsAttention" -> NoteArtifactWorkPhase.Failed
        else -> NoteArtifactWorkPhase.Unknown
    }
}

private fun NoteArtifactCoverageProjection.toUi() = NoteArtifactCoverageUi(
    textBlocks = textBlocks,
    transcriptSegments = transcriptSegments,
    completedTranscriptSegments = completedTranscriptSegments,
    photos = photos,
    completedPhotos = completedPhotos,
    inkPreviews = inkPreviews,
    completedInkPreviews = completedInkPreviews,
    visionBatches = visionBatches,
    completedVisionBatches = completedVisionBatches,
    serverReportsComplete = complete,
    issues = issues.map { NoteArtifactIssueUi(it.code, it.message) },
)

private fun emptyCoverage() = NoteArtifactCoverageUi(
    textBlocks = 0,
    transcriptSegments = 0,
    completedTranscriptSegments = 0,
    photos = 0,
    completedPhotos = 0,
    inkPreviews = 0,
    completedInkPreviews = 0,
    visionBatches = 0,
    completedVisionBatches = 0,
    serverReportsComplete = false,
)

internal fun formatArtifactMillis(value: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value.toString())

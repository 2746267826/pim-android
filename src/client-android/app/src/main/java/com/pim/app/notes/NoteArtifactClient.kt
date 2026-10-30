package com.pim.app.notes

import com.pim.core.contract.NoteDerivedArtifactDto
import com.pim.core.contract.NoteDerivedArtifactDtoApiResponse
import com.pim.core.contract.NoteDerivedArtifactDtoIReadOnlyListApiResponse
import com.pim.core.contract.NoteDerivedArtifactKind
import com.pim.core.contract.NoteDerivedArtifactRefDto
import com.pim.core.contract.NoteDerivedCoverageDto
import com.pim.core.contract.NoteDerivedDestinationsDtoApiResponse
import com.pim.core.contract.NoteDerivedGenerationConsent
import com.pim.core.contract.NoteDerivedIssueDto
import com.pim.core.contract.NoteDerivedMutationDtoApiResponse
import com.pim.core.contract.NoteDerivedStepDto
import com.pim.core.contract.NoteDerivedStepsDtoApiResponse
import com.pim.core.contract.NoteDerivedTaskDto
import com.pim.core.contract.NoteDerivedTaskDtoApiResponse
import com.pim.core.contract.NoteDerivedVersionAuthor
import com.pim.core.contract.NoteDerivedVersionDto
import com.pim.core.contract.NoteDerivedVersionDtoApiResponse
import com.pim.core.contract.RetryNoteDerivedTaskRequest
import com.pim.core.contract.SaveNoteDerivedManualRevisionRequest
import com.pim.core.contract.SelectNoteDerivedVersionRequest
import com.pim.core.contract.StartNoteDerivedTaskRequest
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.io.IOException

/**
 * One owner/server-bound generated-contract client for derived note artifacts.
 * Every physical attempt is also checked by the bound transport interceptor;
 * this class never obtains or stores a token itself.
 */
class NoteArtifactClient(
    private val api: ApiService,
    private val binding: BoundUploadSession,
) : NoteArtifactTransport {
    override suspend fun destinations(): List<NoteArtifactDestination> {
        val response = api.getNoteDerivedDestinations()
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        val data = response.data ?: throw IOException("服务器未返回派生目标目录")
        require(data.total >= 0 && data.total == data.items.size)
        require(data.items.map { "${it.modelId}:${it.modelRevision}" }.distinct().size == data.items.size)
        return data.items.map { destination ->
            require(destination.modelId.isNotBlank() && destination.modelRevision.isNotBlank())
            require(destination.displayName.isNotBlank() && destination.model.isNotBlank())
            require(destination.endpoint.isTrustedHttpEndpoint())
            require(destination.capabilities.distinct().size == destination.capabilities.size)
            NoteArtifactDestination(
                modelId = destination.modelId,
                modelRevision = destination.modelRevision,
                displayName = destination.displayName.take(200),
                model = destination.model.take(200),
                endpoint = destination.endpoint,
                capabilities = destination.capabilities.distinct(),
                backgroundProcessingAvailable = destination.backgroundProcessingAvailable,
            )
        }
    }

    override suspend fun start(record: NoteArtifactTaskRecord): NoteArtifactRemoteTaskView {
        requireBound(record)
        val request = StartNoteDerivedTaskRequest(
            asrTaskId = record.asrTaskId,
            consent = record.consent.toWire(),
            expectedUserId = record.expectedUserId,
            taskId = record.taskId,
            versionId = record.materialVersionId,
        )
        val remote = taskData(api.startNoteDerivedTask(request))
        require(remote.taskId == record.taskId) { "服务器返回了不同的 TaskId" }
        require(remote.sourceVersionId == record.materialVersionId) { "服务器任务绑定了不同的固定材料版本" }
        require(remote.noteId == record.noteId) { "服务器任务绑定了不同的笔记" }
        require(remote.destination == request.consent) { "服务器任务绑定了不同的目标" }
        require(remote.inputSha256.equals(record.inputSha256, ignoreCase = true) &&
            remote.manifestSha256.equals(record.manifestSha256, ignoreCase = true)) {
            "服务器任务的材料摘要与本地固定版本不一致"
        }
        return remote.asView()
    }

    override suspend fun get(taskId: String): NoteArtifactRemoteTaskView {
        val remote = taskData(api.getNoteDerivedTask(taskId))
        require(remote.taskId == taskId) { "服务器返回了不同的 TaskId" }
        return remote.asView()
    }

    override suspend fun detail(taskId: String): NoteArtifactTaskDetail {
        val remote = taskData(api.getNoteDerivedTask(taskId))
        require(remote.taskId == taskId) { "服务器返回了不同的 TaskId" }
        val steps = mergeSteps(taskId, remote.steps)
        val artifacts = remote.artifacts.map { it.asRefProjection() }
        require(artifacts.map { it.kind }.distinct().size == artifacts.size) {
            "服务器同一任务返回了重复的产物类型"
        }
        val issues = (remote.issues + remote.coverage.issues).map { it.asProjection() }.distinct()
        return NoteArtifactTaskDetail(
            view = remote.asView(),
            coverage = remote.coverage.asProjection(remote.coverage.issues.map { it.asProjection() }),
            issues = issues,
            steps = steps,
            artifacts = artifacts,
        )
    }

    override suspend fun artifacts(noteId: String): List<NoteArtifactArtifactRefProjection> {
        val response = api.getNoteDerivedArtifacts(noteId)
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        val items = response.data.orEmpty()
        require(items.map { it.artifactId }.distinct().size == items.size)
        return items.map { artifact ->
            require(artifact.noteId == noteId) { "服务器返回了其他笔记的派生结果" }
            require(artifact.revision.isNotBlank())
            requireOpaqueId(artifact.headVersionId)
            NoteArtifactArtifactRefProjection(
                artifactId = artifact.artifactId,
                kind = artifact.kind.toLocal(),
                revision = artifact.revision,
                headVersionId = artifact.headVersionId,
            )
        }
    }

    override suspend fun versions(artifactId: String): List<NoteArtifactVersionProjection> {
        val artifact = artifactData(api.getNoteDerivedArtifact(artifactId))
        require(artifact.artifactId == artifactId) { "服务器返回了不同的产物" }
        return artifact.versions
            .sortedByDescending { it.createdAt }
            .take(MAX_VERSION_HISTORY)
            .map { summary ->
                val version = versionData(api.getNoteDerivedVersion(artifactId, summary.versionId))
                require(version.versionId == summary.versionId && version.artifactId == artifactId)
                require(version.kind.toLocal() == artifact.kind.toLocal()) { "版本与产物类型不一致" }
                require(version.contentSha256.equals(summary.contentSha256, ignoreCase = true)) {
                    "版本内容摘要与历史声明不一致"
                }
                version.asProjection()
            }
    }

    override suspend fun stop(taskId: String): NoteArtifactRemoteTaskView {
        val remote = taskData(api.stopNoteDerivedTask(taskId))
        require(remote.taskId == taskId) { "服务器返回了不同的 TaskId" }
        return remote.asView()
    }

    override suspend fun retry(
        record: NoteArtifactTaskRecord,
        retry: NoteArtifactRetryIntent,
    ): NoteArtifactRemoteTaskView {
        requireBound(record)
        require(record.remote?.revision == retry.expectedServerRevision) {
            "恢复意图的核对基线已变化"
        }
        val request = RetryNoteDerivedTaskRequest(
            acknowledgePossibleDuplicateCharge = true,
            consent = record.consent.toWire(),
            expectedRevision = retry.expectedServerRevision,
            expectedUserId = record.expectedUserId,
            retryId = retry.retryId,
            stepKeys = retry.stepKeys,
        )
        val remote = taskData(api.retryNoteDerivedTask(record.taskId, request))
        require(remote.taskId == record.taskId) { "服务器返回了不同的 TaskId" }
        return remote.asView()
    }

    override suspend fun saveManualRevision(
        artifactId: String,
        revisionId: String,
        expectedHeadRevision: String,
        expectedHeadVersionId: String?,
        contentMarkdown: String,
    ): NoteArtifactMutationProjection {
        require(validNoteId(revisionId) && expectedHeadRevision.isNotBlank())
        requireOpaqueId(expectedHeadVersionId)
        val response = api.saveNoteDerivedManualRevision(
            artifactId,
            SaveNoteDerivedManualRevisionRequest(
                contentMarkdown = contentMarkdown,
                expectedHeadRevision = expectedHeadRevision,
                expectedHeadVersionId = expectedHeadVersionId,
                revisionId = revisionId,
            ),
        )
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        val mutation = response.data ?: throw IOException("服务器未返回人工修订回执")
        require(mutation.artifact.artifactId == artifactId) { "人工修订回执指向了其他产物" }
        require(mutation.versionId.isNotBlank() && mutation.artifact.revision.isNotBlank())
        require(mutation.isHead || mutation.hasConflict) {
            "服务器返回的冲突稿既未成为当前版本，也没有标记冲突"
        }
        return NoteArtifactMutationProjection(
            artifactRevision = mutation.artifact.revision,
            versionId = mutation.versionId,
            hasConflict = mutation.hasConflict,
            isHead = mutation.isHead,
        )
    }

    override suspend fun selectVersion(
        artifactId: String,
        expectedHeadRevision: String,
        versionId: String,
    ): String? {
        require(expectedHeadRevision.isNotBlank() && validNoteId(versionId))
        val response = api.selectNoteDerivedVersion(
            artifactId,
            SelectNoteDerivedVersionRequest(
                expectedHeadRevision = expectedHeadRevision,
                versionId = versionId,
            ),
        )
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        val artifact = response.data ?: throw IOException("服务器未返回选择后的产物")
        require(artifact.artifactId == artifactId) { "选择回执指向了其他产物" }
        require(artifact.headVersionId == versionId) { "服务器未把所选版本设为当前版本" }
        return artifact.headVersionId
    }

    private suspend fun mergeSteps(
        taskId: String,
        initial: List<NoteDerivedStepDto>,
    ): List<NoteArtifactStepProjection> {
        val merged = linkedMapOf<String, NoteDerivedStepDto>()
        initial.forEach { step ->
            require(step.key.isNotBlank())
            merged[step.key] = step
        }
        var offset = 0
        var total: Int? = null
        while (true) {
            val page = stepsData(api.getNoteDerivedSteps(taskId, offset, STEP_PAGE_SIZE))
            require(page.offset == offset && page.total >= 0 && page.items.size <= STEP_PAGE_SIZE)
            require(total == null || total == page.total) { "服务器步骤分页总数发生变化" }
            total = page.total
            page.items.forEach { step ->
                require(step.key.isNotBlank())
                merged[step.key] = step
            }
            offset += page.items.size
            if (offset >= page.total) break
            require(page.items.isNotEmpty()) { "服务器步骤分页没有进展" }
        }
        require(offset == checkNotNull(total))
        require(merged.size <= MAX_MERGED_STEPS) { "服务器步骤数量超过客户端可恢复上限" }
        return merged.values.map { it.asProjection() }
    }

    private fun requireBound(record: NoteArtifactTaskRecord) {
        require(record.binding == binding) { "派生任务绑定的账号或服务器已变化" }
        require(record.expectedUserId == binding.ownerId)
    }

    private fun requireOpaqueId(value: String?) {
        require(value == null || (value.isNotBlank() && value.length <= 512))
    }

    private fun NoteArtifactConsent.toWire() = NoteDerivedGenerationConsent(
        confirmed = confirmed,
        endpoint = endpoint,
        modelId = modelId,
        modelRevision = modelRevision,
    )

    private fun NoteDerivedTaskDto.asView() = NoteArtifactRemoteTaskView(
        revision = revision,
        phase = phase,
        inputSha256 = inputSha256,
        manifestSha256 = manifestSha256,
        stopRequested = stopRequested,
        completeRecordArtifactId = artifacts.firstOrNull {
            it.kind == NoteDerivedArtifactKind.COMPLETERECORD
        }?.artifactId,
        analysisArtifactId = artifacts.firstOrNull {
            it.kind == NoteDerivedArtifactKind.INDEPENDENTANALYSIS
        }?.artifactId,
        observedAtMillis = System.currentTimeMillis(),
    )

    private fun NoteDerivedArtifactRefDto.asRefProjection() = NoteArtifactArtifactRefProjection(
        artifactId = artifactId,
        kind = kind.toLocal(),
        revision = null,
        headVersionId = headVersionId,
    )

    private fun NoteDerivedCoverageDto.asProjection(
        issueProjections: List<NoteArtifactIssueProjection>,
    ) = NoteArtifactCoverageProjection(
        textBlocks = textBlocks,
        transcriptSegments = transcriptSegments,
        completedTranscriptSegments = completedTranscriptSegments,
        photos = photos,
        completedPhotos = completedPhotos,
        inkPreviews = inkPreviews,
        completedInkPreviews = completedInkPreviews,
        visionBatches = visionBatches,
        completedVisionBatches = completedVisionBatches,
        complete = complete,
        issues = issueProjections,
    )

    private fun NoteDerivedIssueDto.asProjection() = NoteArtifactIssueProjection(
        code = code,
        message = message,
        sourcePath = sourcePath,
        blockId = blockId,
        stepKey = stepKey,
    )

    private fun NoteDerivedStepDto.asProjection() = NoteArtifactStepProjection(
        key = key,
        kind = kind.name,
        status = status,
        attemptCount = attempts.size,
        lastErrorCode = attempts.lastOrNull()?.errorCode,
        lastErrorMessage = attempts.lastOrNull()?.errorMessage,
    )

    private fun NoteDerivedVersionDto.asProjection() = NoteArtifactVersionProjection(
        versionId = versionId,
        artifactId = artifactId,
        kind = kind.toLocal(),
        author = when (author) {
            NoteDerivedVersionAuthor.MODEL -> NoteArtifactVersionAuthor.Model
            NoteDerivedVersionAuthor.USER -> NoteArtifactVersionAuthor.User
        },
        parentVersionId = parentVersionId,
        observedHeadVersionId = observedHeadVersionId,
        sourceCompleteRecordVersionId = sourceCompleteRecordVersionId,
        sourceVersionId = sourceVersionId,
        generationTaskId = generationTaskId,
        contentMarkdown = contentMarkdown,
        contentSha256 = contentSha256,
        hasConflict = hasConflict,
        createdAtLabel = formatArtifactTime(createdAt),
        isHead = isHead,
    )

    private fun NoteDerivedArtifactKind.toLocal(): NoteArtifactKind = when (this) {
        NoteDerivedArtifactKind.COMPLETERECORD -> NoteArtifactKind.FullRecord
        NoteDerivedArtifactKind.INDEPENDENTANALYSIS -> NoteArtifactKind.Analysis
    }

    private fun String.isTrustedHttpEndpoint(): Boolean {
        val uri = runCatching { java.net.URI(this) }.getOrNull() ?: return false
        return uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    private fun taskData(response: NoteDerivedTaskDtoApiResponse): NoteDerivedTaskDto {
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回派生任务")
    }

    private fun artifactData(response: NoteDerivedArtifactDtoApiResponse): NoteDerivedArtifactDto {
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回派生结果")
    }

    private fun versionData(response: NoteDerivedVersionDtoApiResponse): NoteDerivedVersionDto {
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回派生版本原文")
    }

    private fun stepsData(response: NoteDerivedStepsDtoApiResponse) = run {
        if (response.code != 0) throw NoteArtifactRequestRejectedException(response.message)
        response.data ?: throw IOException("服务器未返回派生步骤")
    }

    companion object {
        private const val STEP_PAGE_SIZE = 20
        private const val MAX_MERGED_STEPS = 1_000
        private const val MAX_VERSION_HISTORY = 30
    }
}

internal fun formatArtifactTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}.getOrDefault(value)

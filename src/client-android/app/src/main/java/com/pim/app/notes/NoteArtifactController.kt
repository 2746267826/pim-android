package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import java.io.IOException
import retrofit2.HttpException

class NoteArtifactBindingChangedException : IOException("派生任务绑定的账号或服务器已变化")

class NoteArtifactTaskMissingException(message: String) : IOException(message)

class NoteArtifactRequestRejectedException(message: String) : IOException(message)

/**
 * Wire boundary for the future generated-contract client. It speaks only in
 * local projections, never generated DTO types or raw JSON field names.
 */
interface NoteArtifactTransport {
    suspend fun destinations(): List<NoteArtifactDestination>

    suspend fun start(record: NoteArtifactTaskRecord): NoteArtifactRemoteTaskView

    suspend fun get(taskId: String): NoteArtifactRemoteTaskView

    suspend fun detail(taskId: String): NoteArtifactTaskDetail

    suspend fun artifacts(noteId: String): List<NoteArtifactArtifactRefProjection>

    suspend fun versions(artifactId: String): List<NoteArtifactVersionProjection>

    suspend fun stop(taskId: String): NoteArtifactRemoteTaskView

    suspend fun retry(
        record: NoteArtifactTaskRecord,
        retry: NoteArtifactRetryIntent,
    ): NoteArtifactRemoteTaskView

    suspend fun saveManualRevision(
        artifactId: String,
        revisionId: String,
        expectedHeadRevision: String,
        expectedHeadVersionId: String?,
        contentMarkdown: String,
    ): NoteArtifactMutationProjection

    suspend fun selectVersion(
        artifactId: String,
        expectedHeadRevision: String,
        versionId: String,
    ): String?
}

/**
 * Local, deterministic task workflow. Every send is preceded by a durable
 * intent write; an unknown receipt is never retried automatically and always
 * keeps the original TaskId / RetryId for an explicit verification.
 */
class NoteArtifactController(
    private val tasks: NoteArtifactTaskStore,
    private val transport: NoteArtifactTransport,
    private val binding: BoundUploadSession,
) {
    fun prepare(
        noteId: String,
        materialVersionId: String,
        sourceTitle: String = "",
        materialCreatedAtMillis: Long? = null,
        asrTaskId: String? = null,
        kind: NoteArtifactKind,
        inputSha256: String,
        manifestSha256: String,
        consent: NoteArtifactConsent,
        destinationDisplayName: String = "",
        destinationModel: String = "",
        destinationCapabilities: List<String> = emptyList(),
        destinationBackgroundAvailable: Boolean? = null,
        ownerName: String = "",
    ): NoteArtifactTaskRecord = tasks.createIntent(
        binding = binding,
        noteId = noteId,
        materialVersionId = materialVersionId,
        sourceTitle = sourceTitle,
        materialCreatedAtMillis = materialCreatedAtMillis,
        asrTaskId = asrTaskId,
        kind = kind,
        inputSha256 = inputSha256,
        manifestSha256 = manifestSha256,
        consent = consent,
        destinationDisplayName = destinationDisplayName,
        destinationModel = destinationModel,
        destinationCapabilities = destinationCapabilities,
        destinationBackgroundAvailable = destinationBackgroundAvailable,
        ownerName = ownerName,
    )

    suspend fun start(id: String, freshlyConfirmed: Boolean): NoteArtifactTaskRecord {
        require(freshlyConfirmed) { "页面重建后必须重新确认固定材料与目标" }
        val record = bound(id)
        require(record.localPhase in PENDING_START_PHASES) { "只有尚未确认创建的任务可以发送" }
        tasks.update(id) {
            it.copy(localPhase = NoteArtifactIntentPhase.Submitting, lastProblem = null)
        }
        return sendStart(id)
    }

    /** Queries the server with the already-durable TaskId; never creates a new task. */
    suspend fun verify(id: String): NoteArtifactTaskRecord {
        val record = bound(id)
        try {
            val view = transport.get(record.taskId)
            return tasks.recordRemote(id, view)
        } catch (error: HttpException) {
            if (error.code() == 404) {
                tasks.update(id) {
                    it.copy(
                        localPhase = if (it.localPhase in PENDING_START_PHASES)
                            NoteArtifactIntentPhase.VerificationPending else NoteArtifactIntentPhase.Unavailable,
                        lastProblem = "服务器未返回该 TaskId。不会自动另建任务；请核对后显式使用同一标识。",
                    )
                }
                throw NoteArtifactTaskMissingException("服务器没有返回该 TaskId")
            }
            if (error.code() in setOf(401, 403)) {
                tasks.update(id) {
                    it.copy(
                        localPhase = NoteArtifactIntentPhase.NeedsLogin,
                        lastProblem = "登录或账号权限已变化；原任务与本机稿保留。",
                    )
                }
            }
            throw error
        }
    }

    suspend fun replayStart(id: String, freshlyConfirmed: Boolean): NoteArtifactTaskRecord {
        require(freshlyConfirmed) { "页面重建后必须重新确认固定材料与目标" }
        val record = bound(id)
        require(record.localPhase in PENDING_START_PHASES) { "只有回执未确认的任务可以按原标识核对" }
        tasks.update(id) {
            it.copy(localPhase = NoteArtifactIntentPhase.Submitting, lastProblem = null)
        }
        return sendStart(id)
    }

    suspend fun stop(id: String): NoteArtifactTaskRecord {
        val record = bound(id)
        record.remote ?: throw NoteArtifactTaskMissingException("尚无已确认任务，不能停止")
        require(record.stopPhase == null) { "停止请求已在发送或待核对；请先刷新确认，不重复发送" }
        tasks.update(id) {
            it.copy(stopPhase = NoteArtifactIntentPhase.Submitting, lastProblem = null)
        }
        try {
            val view = transport.stop(id)
            val saved = tasks.recordRemote(id, view)
            return if (view.stopRequested) saved else tasks.update(id) {
                it.copy(
                    stopPhase = NoteArtifactIntentPhase.VerificationPending,
                    lastProblem = "停止请求已发出，但服务器尚未确认停止；不会自动重复发送，请刷新核对。",
                )
            }
        } catch (error: Throwable) {
            val phase = failurePhase(error)
            tasks.update(id) {
                it.copy(
                    stopPhase = if (phase == NoteArtifactIntentPhase.VerificationPending) phase else null,
                    localPhase = if (phase == NoteArtifactIntentPhase.NeedsLogin)
                        NoteArtifactIntentPhase.NeedsLogin else it.localPhase,
                    lastProblem = when (phase) {
                        NoteArtifactIntentPhase.NeedsLogin ->
                            "登录已失效或当前账号不能停止该任务；任务与本机稿保留。"
                        NoteArtifactIntentPhase.Rejected ->
                            "服务器拒绝了停止请求；没有改用新稿或另建任务。"
                        else ->
                            "停止回执未确认。已发出的服务商调用不保证撤销费用；请刷新核对，不自动重发。"
                    },
                )
            }
            throw error
        }
    }

    suspend fun retry(
        id: String,
        stepKeys: List<String>,
        duplicateChargeConfirmed: Boolean,
    ): NoteArtifactTaskRecord {
        require(duplicateChargeConfirmed) { "恢复前必须确认可能重复计费" }
        val record = bound(id)
        require(record.pendingRetry?.requiresExplicitReplay() != true) {
            "已有待核对的恢复请求；必须使用原恢复标识明确核对，不能新选步骤覆盖"
        }
        val remote = record.remote ?: throw NoteArtifactTaskMissingException("尚无已确认任务")
        require(stepKeys.isNotEmpty() && stepKeys.size <= 500 && stepKeys.distinct().size == stepKeys.size)
        require(stepKeys.all { it.isNotBlank() && it.length <= 200 })
        val existing = record.pendingRetry?.takeIf { it.phase != NoteArtifactIntentPhase.Rejected }
        val intent = existing?.takeIf {
            it.expectedServerRevision == remote.revision && it.stepKeys == stepKeys
        } ?: NoteArtifactRetryIntent(
            retryId = newNoteId(),
            expectedServerRevision = remote.revision,
            stepKeys = stepKeys,
            createdAtMillis = System.currentTimeMillis(),
        )
        tasks.update(id) {
            it.copy(
                pendingRetry = intent.copy(phase = NoteArtifactIntentPhase.Submitting),
                lastProblem = null,
            )
        }
        return sendRetry(id, intent)
    }

    suspend fun replayRetry(id: String, freshlyConfirmed: Boolean): NoteArtifactTaskRecord {
        require(freshlyConfirmed) { "恢复回执核对前必须重新确认可能重复计费" }
        val record = bound(id)
        val retry = record.pendingRetry ?: throw NoteArtifactTaskMissingException("没有待核对的恢复请求")
        require(retry.phase in PENDING_START_PHASES) { "该恢复请求已结束，请先刷新核对" }
        tasks.update(id) {
            it.copy(
                pendingRetry = retry.copy(phase = NoteArtifactIntentPhase.Submitting),
                lastProblem = null,
            )
        }
        return sendRetry(id, retry)
    }

    private suspend fun sendStart(id: String): NoteArtifactTaskRecord {
        try {
            val view = transport.start(tasks.read(id))
            return tasks.recordRemote(id, view)
        } catch (error: Throwable) {
            markStartFailure(id, error)
            throw error
        }
    }

    private suspend fun sendRetry(id: String, retry: NoteArtifactRetryIntent): NoteArtifactTaskRecord {
        try {
            val view = transport.retry(tasks.read(id), retry)
            val saved = tasks.recordRemote(id, view)
            return if (saved.pendingRetry == null) saved else tasks.update(id) {
                it.copy(pendingRetry = null, lastProblem = null)
            }
        } catch (error: Throwable) {
            val phase = failurePhase(error)
            tasks.update(id) {
                it.copy(
                    pendingRetry = retry.copy(phase = phase),
                    localPhase = if (phase == NoteArtifactIntentPhase.NeedsLogin)
                        NoteArtifactIntentPhase.NeedsLogin else it.localPhase,
                    lastProblem = if (phase == NoteArtifactIntentPhase.Rejected)
                        (error.message?.takeIf(String::isNotBlank) ?: "服务器拒绝了恢复请求") +
                            "；已有结果保留。"
                    else "恢复请求结果未确认。请先核对任务进度，不自动重发。",
                )
            }
            throw error
        }
    }

    private fun markStartFailure(id: String, error: Throwable) {
        val phase = failurePhase(error)
        tasks.update(id) {
            it.copy(
                localPhase = phase,
                lastProblem = when (phase) {
                    NoteArtifactIntentPhase.NeedsLogin ->
                        "登录已失效或当前账号不能访问该派生任务；固定材料与本机稿保留。"
                    NoteArtifactIntentPhase.Rejected ->
                        (error.message?.takeIf(String::isNotBlank) ?: "服务器拒绝了该派生任务") +
                            "；没有改用新稿或另建任务。"
                    else ->
                        "请求可能已发出，但结果未确认。请按同一 TaskId 查询，不自动重发。"
                },
            )
        }
    }

    private fun failurePhase(error: Throwable): NoteArtifactIntentPhase = when {
        error is NoteArtifactRequestRejectedException -> NoteArtifactIntentPhase.Rejected
        error is HttpException && error.code() in setOf(401, 403) -> NoteArtifactIntentPhase.NeedsLogin
        error is HttpException && error.code() !in setOf(408, 429) && error.code() < 500 ->
            NoteArtifactIntentPhase.Rejected
        else -> NoteArtifactIntentPhase.VerificationPending
    }

    private fun bound(id: String): NoteArtifactTaskRecord {
        val record = tasks.read(id)
        if (record.binding != binding) throw NoteArtifactBindingChangedException()
        return record
    }

    private companion object {
        val PENDING_START_PHASES = setOf(
            NoteArtifactIntentPhase.Prepared,
            NoteArtifactIntentPhase.Submitting,
            NoteArtifactIntentPhase.VerificationPending,
        )
    }
}

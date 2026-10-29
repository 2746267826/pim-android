package com.pim.app.notes

import com.pim.core.contract.AiAsrDestinationConsent
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrSourceDto
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.network.BoundUploadSession
import kotlinx.serialization.Serializable

/** Local submission state. Server task phase remains a separate wire value from the generated contract. */
@Serializable
enum class NoteAsrIntentPhase {
    Prepared,
    Submitting,
    VerificationPending,
    Accepted,
    NeedsLogin,
    Unavailable,
    Rejected,
}

@Serializable
data class NoteAsrRecordingSource(
    val id: String,
    val createdAtMillis: Long,
    val recordingSegments: Int,
    val samples: Long,
)

@Serializable
data class NoteAsrRetryIntent(
    val retryId: String,
    val expectedRevision: String,
    val segmentIndexes: List<Int>,
    val phase: NoteAsrIntentPhase = NoteAsrIntentPhase.Prepared,
    val createdAtMillis: Long,
)

internal fun NoteAsrRetryIntent.requiresExplicitReplay(): Boolean =
    phase == NoteAsrIntentPhase.Submitting || phase == NoteAsrIntentPhase.VerificationPending

/**
 * Private durable task state. It never contains an access token, refresh token, or Authorization header.
 * Generated ASR DTOs are used directly so local persistence cannot silently redefine the wire contract.
 */
@Serializable
data class NoteAsrTaskRecord(
    val taskId: String,
    val versionId: String,
    val noteId: String,
    val sourceTitle: String,
    val versionRevision: Long? = null,
    val versionCreatedAt: String? = null,
    val versionIsCurrent: Boolean? = null,
    val versionHasConflict: Boolean? = null,
    val sourceRecordings: List<NoteAsrRecordingSource> = emptyList(),
    val totalSamples: Long,
    val sampleRate: Int = NOTE_ASR_SAMPLE_RATE,
    val serverIdentity: String,
    val ownerId: String,
    val ownerName: String,
    val expectedUserId: String,
    val language: String = "auto",
    val destination: AiAsrDestinationConsent,
    val destinationDisplayName: String = "",
    val destinationModel: String = "",
    val destinationLanguages: List<String> = emptyList(),
    val destinationBackgroundAvailable: Boolean? = null,
    val localPhase: NoteAsrIntentPhase,
    val task: NoteAsrTaskDto? = null,
    val segments: List<NoteAsrSegmentDto> = emptyList(),
    val segmentsComplete: Boolean = false,
    val pendingRetry: NoteAsrRetryIntent? = null,
    val lastProblem: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val binding: BoundUploadSession get() = BoundUploadSession(serverIdentity, ownerId)
    val effectiveTotalSamples: Long get() = task?.totalSamples ?: totalSamples
    val effectiveSampleRate: Int get() = task?.sampleRate ?: sampleRate
    override fun toString() =
        "NoteAsrTaskRecord(taskId=$taskId, versionId=$versionId, phase=$localPhase, wirePhase=${task?.phase})"
}

data class NoteAsrRepositoryState(
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val tasks: List<NoteAsrTaskRecord> = emptyList(),
    val sources: List<NoteAsrSourceDto> = emptyList(),
    val sourcesTotal: Int = 0,
    val sourceProblem: String? = null,
    val sourceBinding: BoundUploadSession? = null,
    val destinations: List<NoteAsrDestinationDto> = emptyList(),
    val destinationProblem: String? = null,
    val destinationBinding: BoundUploadSession? = null,
    val segmentsLoadingTaskId: String? = null,
    val message: String? = null,
    val loadFailed: Boolean = false,
    val newTaskAvailable: Boolean = false,
    val newTaskProblem: String = SAFE_DESTINATION_PENDING,
)

internal fun NoteAsrDestinationDto.asAsrConsent() = AiAsrDestinationConsent(
    confirmed = true,
    endpoint = endpoint,
    modelId = modelId,
    modelRevision = modelRevision,
    presetId = presetId,
    presetVersion = presetVersion,
)

internal data class NoteAsrDestinationConsentIdentity(
    val modelId: String,
    val modelRevision: String,
    val displayName: String,
    val model: String,
    val endpoint: String,
    val presetId: String,
    val presetVersion: String,
    val languages: List<String>,
    val backgroundProcessingAvailable: Boolean,
)

internal fun NoteAsrDestinationDto.consentIdentity() = NoteAsrDestinationConsentIdentity(
    modelId = modelId,
    modelRevision = modelRevision,
    displayName = displayName,
    model = model,
    endpoint = endpoint,
    presetId = presetId,
    presetVersion = presetVersion,
    languages = languages,
    backgroundProcessingAvailable = backgroundProcessingAvailable,
)

internal fun NoteAsrDestinationDto.displayLabel(): String =
    displayName.ifBlank { model.ifBlank { modelId.take(8) } }

const val NOTE_ASR_SAMPLE_RATE = 16_000
const val SAFE_DESTINATION_PENDING =
    "请登录并读取当前账号的安全 ASR 目标目录；未选择并明确同意前不会创建收费任务。"

fun NoteAsrTaskRecord.isBoundTo(target: NoteUploadTarget?): Boolean = target?.binding == binding

data class NoteAsrTaskListPresentation(
    val title: String,
    val subtitle: String,
    val showProgress: Boolean,
)

/**
 * Cross-account/server views expose only a generic local-retention notice. The original
 * task remains on disk for its bound owner, but cached titles and materials are not rendered.
 */
fun NoteAsrTaskRecord.listPresentation(currentTarget: NoteUploadTarget?): NoteAsrTaskListPresentation =
    if (!isBoundTo(currentTarget)) {
        NoteAsrTaskListPresentation(
            title = "原账号转写任务",
            subtitle = "仅保留本机记录；当前登录不可访问",
            showProgress = false,
        )
    } else {
        NoteAsrTaskListPresentation(
            title = sourceTitle.ifBlank { "固定版本 ${versionId.take(8)}" },
            subtitle = "TaskId ${taskId.take(8)} · ${ownerName.ifBlank { ownerId.take(8) }}",
            showProgress = task != null,
        )
    }

/** Returns the cached source title only for the exact owner/server binding. */
fun NoteAsrTaskRecord.detailSourceTitle(currentTarget: NoteUploadTarget?): String? =
    if (isBoundTo(currentTarget)) sourceTitle.ifBlank { "固定版本 ${versionId.take(8)}" } else null

enum class NoteAsrStatus {
    VerificationPending,
    BindingUnavailable,
    Prepared,
    Queued,
    Running,
    NeedsAttention,
    Stopped,
    Succeeded,
    Unknown,
}

data class NoteAsrStatusView(val status: NoteAsrStatus, val label: String, val detail: String)

fun NoteAsrTaskRecord.statusView(currentTarget: NoteUploadTarget?): NoteAsrStatusView {
    if (!isBoundTo(currentTarget)) return NoteAsrStatusView(
        NoteAsrStatus.BindingUnavailable,
        "原账号任务，当前不可访问",
        "任务与原材料仍保留在本机。请切回创建它的账号和服务器后再恢复查看；当前登录不会读取、显示或发送旧任务。",
    )
    if (localPhase == NoteAsrIntentPhase.NeedsLogin) return NoteAsrStatusView(
        NoteAsrStatus.BindingUnavailable,
        "当前账号无法访问",
        lastProblem ?: "请重新登录或核对原账号权限；旧任务和已缓存成功片段保留。",
    )
    if (localPhase == NoteAsrIntentPhase.Unavailable) return NoteAsrStatusView(
        NoteAsrStatus.Unknown,
        "服务器未确认该任务",
        lastProblem ?: "服务器没有返回该 TaskId；不会自动另建任务。请保留原标识并显式核对。",
    )
    pendingRetry?.takeIf { it.phase == NoteAsrIntentPhase.NeedsLogin }?.let {
        return NoteAsrStatusView(
            NoteAsrStatus.BindingUnavailable,
            "恢复请求需要原账号",
            lastProblem ?: "登录或权限已变化；已成功片段保留。",
        )
    }
    pendingRetry?.takeIf { it.phase == NoteAsrIntentPhase.Rejected }?.let {
        return NoteAsrStatusView(
            NoteAsrStatus.NeedsAttention,
            "恢复请求未接受",
            lastProblem ?: "服务器未接受恢复请求；已成功片段保留，可重新选择失败片。",
        )
    }
    pendingRetry?.takeIf { it.requiresExplicitReplay() }?.let {
        return NoteAsrStatusView(
            NoteAsrStatus.VerificationPending,
            "恢复回执待核对",
            if (it.phase == NoteAsrIntentPhase.VerificationPending)
                "恢复请求结果未确认；不会自动重发。请先核对同一任务的进度与尝试。"
            else "正在发送恢复请求；请勿重复操作。",
        )
    }
    if (task == null) return when (localPhase) {
        NoteAsrIntentPhase.Prepared -> NoteAsrStatusView(NoteAsrStatus.Prepared, "已保存，尚未发送", "固定版本和全部同意条件已落盘；发送前仍需当前页面重新确认。")
        NoteAsrIntentPhase.Submitting -> NoteAsrStatusView(NoteAsrStatus.VerificationPending, "正在核对任务", "请求可能已发出；不会创建另一个任务。")
        NoteAsrIntentPhase.VerificationPending -> NoteAsrStatusView(
            NoteAsrStatus.VerificationPending,
            "回执未知，待核对",
            "没有收到可确认的服务器回执。请按同一 TaskId 查询；不会自动重发未知请求。",
        )
        NoteAsrIntentPhase.NeedsLogin -> NoteAsrStatusView(NoteAsrStatus.BindingUnavailable, "需要原账号登录", lastProblem ?: "请登录任务原绑定的账号和服务器。")
        NoteAsrIntentPhase.Unavailable -> NoteAsrStatusView(NoteAsrStatus.Unknown, "当前不可发起", lastProblem ?: SAFE_DESTINATION_PENDING)
        NoteAsrIntentPhase.Rejected -> NoteAsrStatusView(NoteAsrStatus.NeedsAttention, "未创建任务", lastProblem ?: "服务器未接受该任务；固定版本和本机录音保留。")
        NoteAsrIntentPhase.Accepted -> NoteAsrStatusView(NoteAsrStatus.VerificationPending, "等待任务回执", "服务器曾接受，但本机尚无任务快照；请刷新核对。")
    }
    val remote = checkNotNull(task)
    val status = when (remote.phase) {
        "Queued" -> NoteAsrStatus.Queued
        "Running" -> NoteAsrStatus.Running
        "Stopped" -> NoteAsrStatus.Stopped
        "NeedsAttention" -> NoteAsrStatus.NeedsAttention
        "Succeeded" -> NoteAsrStatus.Succeeded
        else -> NoteAsrStatus.Unknown
    }
    val coverage = "${remote.completedSegments}/${remote.totalSegments} 片成功 · " +
        "${asrDuration(remote.completedSamples, remote.sampleRate)}/${asrDuration(remote.totalSamples, remote.sampleRate)} 样本覆盖"
    val detail = when (status) {
        NoteAsrStatus.Queued -> "$coverage；已排队，尚无模型结果。"
        NoteAsrStatus.Running -> "$coverage；后台正在逐片处理。"
        NoteAsrStatus.Stopped -> "$coverage；停止不撤销已发送调用，成功片文字继续保留。"
        NoteAsrStatus.NeedsAttention -> "$coverage；${remote.failedSegments} 片失败、未确认或被阻止，不能视为整篇完成。"
        NoteAsrStatus.Succeeded -> when {
            !segmentsComplete -> "$coverage；服务器标记成功，但逐段文字尚未全部加载，不能确认全篇完整。"
            segments.any { it.status == "Succeeded" && it.transcript.isNullOrBlank() } ->
                "$coverage；有 ${segments.count { it.status == "Succeeded" && it.transcript.isNullOrBlank() }} 片空文字，请试听，不视为完整结果。"
            segments.any { it.status != "Succeeded" } -> "$coverage；逐段状态与总状态不一致，请核对缺口。"
            !canClaimWholeComplete() -> "$coverage；覆盖或逐段状态仍有缺口，不能视为整篇完成。"
            else -> "$coverage；全部片段均有非空文字。"
        }
        NoteAsrStatus.Unknown -> "$coverage；服务器返回未识别阶段“${remote.phase}”，不推断为成功。"
        else -> coverage
    }
    return NoteAsrStatusView(status, phaseLabel(status, remote.phase), detail)
}

private fun phaseLabel(status: NoteAsrStatus, wire: String): String = when (status) {
    NoteAsrStatus.Queued -> "已排队"
    NoteAsrStatus.Running -> "逐段处理中"
    NoteAsrStatus.Stopped -> "已停止，保留成功片"
    NoteAsrStatus.NeedsAttention -> "存在失败或未确认片段"
    NoteAsrStatus.Succeeded -> if (wire == "Succeeded") "服务器标记成功" else wire
    NoteAsrStatus.Unknown -> "状态未知：$wire"
    NoteAsrStatus.VerificationPending -> "待核对"
    NoteAsrStatus.BindingUnavailable -> "原账号不可用"
    NoteAsrStatus.Prepared -> "尚未发送"
}

fun NoteAsrTaskRecord.canClaimWholeComplete(): Boolean {
    val remote = task ?: return false
    return remote.phase == "Succeeded" && segmentsComplete &&
        remote.completedSegments == remote.totalSegments &&
        remote.completedSamples == remote.totalSamples &&
        segments.size == remote.totalSegments &&
        segments.all { it.status == "Succeeded" && !it.transcript.isNullOrBlank() }
}

fun NoteAsrTaskRecord.isRetryEligible(segment: NoteAsrSegmentDto): Boolean =
    task != null && segment.status in RETRYABLE_SEGMENT_STATUSES && segment.attempts.size < 10 &&
    !segment.status.equals("Running", ignoreCase = true) && !segment.status.equals("Succeeded", ignoreCase = true)

data class NoteAsrRecordingGroup(
    val recordingId: String,
    val label: String,
    val items: List<NoteAsrSegmentDto>,
)

fun NoteAsrTaskRecord.recordingGroups(): List<NoteAsrRecordingGroup> =
    segments.groupBy { it.recordingId }
        .map { (recordingId, items) ->
            val ordered = items.sortedBy { it.index }
            val sourceIndex = sourceRecordings.indexOfFirst { it.id == recordingId }
            NoteAsrRecordingGroup(
                recordingId = recordingId,
                label = if (sourceIndex >= 0) "录音 ${sourceIndex + 1}" else "录音 ${recordingId.take(8)}",
                items = ordered,
            )
        }
        .sortedBy { group -> group.items.minOfOrNull { it.timelineOffsetSamples } ?: Long.MAX_VALUE }

fun asrDuration(samples: Long, sampleRate: Int = NOTE_ASR_SAMPLE_RATE): String {
    require(sampleRate > 0)
    val seconds = (samples.coerceAtLeast(0) / sampleRate)
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** User-visible labels only. Protocol and log values remain unchanged. */
internal fun noteAsrWireStatusLabel(value: String): String = when (value.trim().lowercase(java.util.Locale.ROOT)) {
    "succeeded" -> "成功"
    "queued" -> "等待"
    "running" -> "执行中"
    "prepared" -> "待发送"
    "submitting" -> "发送中"
    "verificationpending" -> "待核对"
    "unknown" -> "待核对"
    "stopped" -> "已停止"
    "needsattention" -> "需处理"
    "failed" -> "失败"
    "blocked" -> "已阻止"
    "timedout" -> "超时"
    "failedvalidation" -> "校验失败"
    "cancelled", "canceled" -> "已取消"
    "incomplete" -> "未完成"
    else -> "未知状态(${value.trim().ifBlank { "空值" }.take(80)})"
}

private val RETRYABLE_SEGMENT_STATUSES = setOf(
    "Failed", "Blocked", "TimedOut", "FailedValidation", "Cancelled", "Incomplete", "Unknown",
)

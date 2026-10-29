package com.pim.app.notes

import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.contract.NoteAsrDestinationsDtoApiResponse
import com.pim.core.contract.NoteAsrSegmentsDtoApiResponse
import com.pim.core.contract.NoteAsrSourcesDtoApiResponse
import com.pim.core.contract.NoteAsrSourceDto
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.contract.NoteAsrTaskDtoApiResponse
import com.pim.core.contract.NoteAsrTaskDtoIReadOnlyListApiResponse
import com.pim.core.contract.RetryNoteAsrRequest
import com.pim.core.contract.StartNoteAsrRequest
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import retrofit2.HttpException

class NoteAsrBindingChangedException : IOException("转写任务绑定的账号或服务器已变化")

class NoteAsrTaskMissingException(message: String) : IOException(message)

class NoteAsrRequestRejectedException(message: String) : IOException(message)

/**
 * One owner/server-bound ASR client. Every request is also checked by the transport binding interceptor;
 * this class never obtains or stores a token itself.
 */
class NoteAsrClient(
    private val tasks: NoteAsrTaskStore,
    private val materials: NoteMaterialStore,
    private val api: ApiService,
    private val binding: BoundUploadSession,
    private val ownerName: String,
) {
    suspend fun sources(): Pair<List<NoteAsrSourceDto>, Int> {
        val result = mutableListOf<NoteAsrSourceDto>()
        var offset = 0
        var total: Int? = null
        while (true) {
            val page = data(api.getNoteAsrSources(offset, 50))
            require(page.offset == offset && page.total >= 0 && page.items.size <= 50)
            require(total == null || total == page.total) { "服务器源分页总数发生变化" }
            total = page.total
            result += page.items
            offset += page.items.size
            if (offset >= page.total) break
            require(page.items.isNotEmpty()) { "服务器源分页没有进展" }
        }
        val resolvedTotal = checkNotNull(total)
        require(result.size == resolvedTotal && result.map { it.versionId }.distinct().size == result.size)
        return result.sortedByDescending { it.createdAt } to resolvedTotal
    }

    suspend fun destinations(): List<NoteAsrDestinationDto> {
        val response = api.getNoteAsrDestinations()
        if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
        val data = response.data ?: throw IOException("服务器未返回 ASR 目标目录")
        require(data.total >= 0 && data.total == data.items.size)
        require(data.items.map { "${it.modelId}:${it.modelRevision}:${it.presetId}:${it.presetVersion}" }
            .distinct().size == data.items.size)
        data.items.forEach { destination ->
            require(destination.displayName.isNotBlank() && destination.model.isNotBlank() &&
                (destination.endpoint.startsWith("https://") || destination.endpoint.startsWith("http://")))
            require(destination.languages.distinct().size == destination.languages.size)
        }
        return data.items
    }

    suspend fun list(): List<NoteAsrTaskDto> {
        val response = api.getNoteAsrTasks()
        if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
        return response.data.orEmpty()
    }

    suspend fun import(task: NoteAsrTaskDto): NoteAsrTaskRecord {
        val material = localMaterial(task.versionId)
        return tasks.importRemote(task, binding, ownerName, material)
    }

    suspend fun get(id: String): NoteAsrTaskDto {
        val before = tasks.read(id)
        require(before.binding == binding)
        try {
            return data(api.getNoteAsrTask(id)).also { remote ->
                require(remote.taskId == id)
                tasks.importRemote(remote, binding, ownerName, localMaterial(remote.versionId))
            }
        } catch (error: HttpException) {
            if (error.code() == 404) {
                tasks.update(id) { record ->
                    record.copy(
                        localPhase = if (record.localPhase in PENDING_START_PHASES)
                            NoteAsrIntentPhase.VerificationPending else NoteAsrIntentPhase.Unavailable,
                        lastProblem = "服务器未返回该 TaskId。不会自动另建任务；请核对后用同一标识明确重放。",
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
                throw NoteAsrTaskMissingException("服务器没有返回该 TaskId")
            }
            if (error.code() in setOf(401, 403)) {
                tasks.update(id) { record ->
                    record.copy(
                        localPhase = NoteAsrIntentPhase.NeedsLogin,
                        lastProblem = "登录或账号权限已变化；旧任务与已缓存成功片段保留。",
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
            }
            throw error
        }
    }

    suspend fun start(record: NoteAsrTaskRecord): NoteAsrTaskRecord {
        require(record.binding == binding && tasks.read(record.taskId).binding == binding)
        val request = StartNoteAsrRequest(
            consent = record.destination,
            expectedUserId = record.expectedUserId,
            language = record.language,
            taskId = record.taskId,
            versionId = record.versionId,
        )
        return sendStart(record.taskId, request)
    }

    suspend fun replayStart(id: String, freshlyConfirmed: Boolean): NoteAsrTaskRecord {
        require(freshlyConfirmed) { "页面重建后必须重新确认固定目标" }
        val record = tasks.read(id)
        require(record.binding == binding)
        require(record.localPhase in PENDING_START_PHASES) { "只有尚未确认创建的任务可以按原标识核对" }
        val request = StartNoteAsrRequest(
            consent = record.destination,
            expectedUserId = record.expectedUserId,
            language = record.language,
            taskId = record.taskId,
            versionId = record.versionId,
        )
        return sendStart(id, request)
    }

    suspend fun segments(id: String, pageSize: Int = 20): NoteAsrTaskRecord {
        require(pageSize in 1..20)
        val record = tasks.read(id)
        require(record.binding == binding)
        val task = record.task ?: throw NoteAsrTaskMissingException("尚无已确认任务，不能读取逐段结果")
        require(task.totalSegments in 1..5_000)
        var offset = 0
        while (offset < task.totalSegments) {
            val response = api.getNoteAsrSegments(id, offset, pageSize)
            val page = data(response)
            require(page.offset == offset && page.total == task.totalSegments && page.items.size <= pageSize)
            require(page.items.all { it.index in offset until minOf(offset + page.items.size, task.totalSegments) })
            tasks.mergeSegments(id, page.items, task.totalSegments)
            offset += page.items.size
            require(page.items.isNotEmpty()) { "服务器分片分页没有进展" }
        }
        return tasks.read(id)
    }

    suspend fun stop(id: String): NoteAsrTaskRecord {
        val record = tasks.read(id)
        require(record.binding == binding && record.task != null)
        val remote = data(api.stopNoteAsrTask(id))
        require(remote.taskId == id)
        return tasks.importRemote(remote, binding, ownerName, localMaterial(remote.versionId))
    }

    suspend fun retry(
        id: String,
        segmentIndexes: List<Int>,
        duplicateChargeConfirmed: Boolean,
    ): NoteAsrTaskRecord {
        require(duplicateChargeConfirmed) { "恢复前必须确认可能重复计费" }
        val record = tasks.read(id)
        require(record.binding == binding)
        require(record.pendingRetry?.requiresExplicitReplay() != true) {
            "已有待核对的恢复请求；必须使用原 retryId 明确核对，不能新选片段覆盖"
        }
        val task = record.task ?: throw NoteAsrTaskMissingException("尚无已确认任务")
        require(segmentIndexes.isNotEmpty() && segmentIndexes.size <= 5_000 && segmentIndexes.distinct().size == segmentIndexes.size)
        val known = record.segments.associateBy { it.index }
        require(segmentIndexes.all { index ->
            val segment = known[index]
            segment != null && record.isRetryEligible(segment)
        }) { "只能选择明确失败或未知的片段；成功、排队或运行中的片段不可重选" }
        val existing = record.pendingRetry?.takeIf { it.phase != NoteAsrIntentPhase.Rejected }
        val intent = existing?.takeIf {
            it.expectedRevision == task.revision && it.segmentIndexes == segmentIndexes
        } ?: NoteAsrRetryIntent(
            retryId = newNoteId(),
            expectedRevision = task.revision,
            segmentIndexes = segmentIndexes,
            createdAtMillis = System.currentTimeMillis(),
        )
        tasks.update(id) {
            it.copy(
                pendingRetry = intent.copy(phase = NoteAsrIntentPhase.Submitting),
                lastProblem = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        return sendRetry(id, intent)
    }

    suspend fun replayRetry(id: String, freshlyConfirmed: Boolean): NoteAsrTaskRecord {
        require(freshlyConfirmed) { "恢复回执核对前必须重新确认可能重复计费" }
        val record = tasks.read(id)
        require(record.binding == binding)
        val retry = record.pendingRetry ?: throw NoteAsrTaskMissingException("没有待核对的恢复请求")
        require(retry.phase in PENDING_START_PHASES)
        tasks.update(id) {
            it.copy(
                pendingRetry = retry.copy(phase = NoteAsrIntentPhase.Submitting),
                lastProblem = null,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        return sendRetry(id, retry)
    }

    private suspend fun sendStart(id: String, request: StartNoteAsrRequest): NoteAsrTaskRecord {
        tasks.update(id) {
            it.copy(localPhase = NoteAsrIntentPhase.Submitting, lastProblem = null,
                updatedAtMillis = System.currentTimeMillis())
        }
        try {
            val response = api.startNoteAsrTask(request)
            if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
            val remote = response.data ?: throw IOException("服务器未返回已保存的任务")
            require(remote.taskId == request.taskId && remote.versionId == request.versionId &&
                remote.destination == request.consent)
            return tasks.importRemote(remote, binding, ownerName, localMaterial(remote.versionId))
        } catch (error: Throwable) {
            markStartFailure(id, error)
            throw error
        }
    }

    private suspend fun sendRetry(id: String, retry: NoteAsrRetryIntent): NoteAsrTaskRecord {
        val record = tasks.read(id)
        record.task ?: throw NoteAsrTaskMissingException("尚无已确认任务")
        val request = RetryNoteAsrRequest(
            acknowledgePossibleDuplicateCharge = true,
            consent = record.destination,
            expectedRevision = retry.expectedRevision,
            expectedUserId = record.expectedUserId,
            retryId = retry.retryId,
            segmentIndexes = retry.segmentIndexes,
        )
        try {
            val response = api.retryNoteAsrTask(id, request)
            if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
            val remote = response.data ?: throw IOException("服务器未返回恢复后的任务")
            require(remote.taskId == id && remote.destination == record.destination)
            val imported = tasks.importRemote(remote, binding, ownerName, localMaterial(remote.versionId))
            return if (imported.pendingRetry == null) imported else tasks.update(id) {
                it.copy(pendingRetry = null, lastProblem = null, updatedAtMillis = System.currentTimeMillis())
            }
        } catch (error: Throwable) {
            tasks.update(id) {
                it.copy(
                    pendingRetry = retry.copy(phase = retryFailurePhase(error)),
                    localPhase = if (error is HttpException && error.code() in setOf(401, 403))
                        NoteAsrIntentPhase.NeedsLogin else it.localPhase,
                    lastProblem = retryProblem(error),
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
            throw error
        }
    }

    private suspend fun markStartFailure(id: String, error: Throwable) {
        val phase = when {
            error is NoteAsrRequestRejectedException -> NoteAsrIntentPhase.Rejected
            error is HttpException && error.code() in setOf(401, 403) -> NoteAsrIntentPhase.NeedsLogin
            error is HttpException && error.code() !in setOf(408, 429) && error.code() < 500 ->
                NoteAsrIntentPhase.Rejected
            else -> NoteAsrIntentPhase.VerificationPending
        }
        tasks.update(id) {
            it.copy(
                localPhase = phase,
                lastProblem = startProblem(error, phase),
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun retryFailurePhase(error: Throwable): NoteAsrIntentPhase = when {
        error is NoteAsrRequestRejectedException -> NoteAsrIntentPhase.Rejected
        error is HttpException && error.code() in setOf(401, 403) -> NoteAsrIntentPhase.NeedsLogin
        error is HttpException && error.code() !in setOf(408, 429) && error.code() < 500 ->
            NoteAsrIntentPhase.Rejected
        else -> NoteAsrIntentPhase.VerificationPending
    }

    private fun startProblem(error: Throwable, phase: NoteAsrIntentPhase): String = when {
        phase == NoteAsrIntentPhase.NeedsLogin ->
            "登录已失效或当前账号不能访问该 ASR 任务；固定版本和任务快照保留。"
        phase == NoteAsrIntentPhase.Rejected ->
            (error.message?.takeIf(String::isNotBlank) ?: "服务器拒绝了该转写任务") + "；没有改用新稿或另建任务。"
        else -> "请求已可能发出，但结果未确认。请按同一 TaskId 查询，不自动重发。"
    }

    private fun retryProblem(error: Throwable): String = when {
        error is NoteAsrRequestRejectedException ->
            (error.message?.takeIf(String::isNotBlank) ?: "服务器拒绝了恢复请求") + "；已成功片段保留。"
        error is HttpException && error.code() in setOf(401, 403) ->
            "登录已失效或当前账号不能恢复该任务；原任务与成功片段保留。"
        else -> "恢复请求结果未确认。请先核对任务进度，不自动重发。"
    }

    private fun localMaterial(versionId: String): NoteMaterialInput? =
        runCatching { materials.read(versionId) }.getOrNull()

    private fun data(response: NoteAsrTaskDtoApiResponse): NoteAsrTaskDto {
        if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回任务")
    }

    private fun data(response: NoteAsrTaskDtoIReadOnlyListApiResponse): List<NoteAsrTaskDto> {
        if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
        return response.data.orEmpty()
    }

    private fun data(response: NoteAsrSegmentsDtoApiResponse): com.pim.core.contract.NoteAsrSegmentsDto {
        if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回逐段结果")
    }

    private fun data(response: NoteAsrSourcesDtoApiResponse): com.pim.core.contract.NoteAsrSourcesDto {
        if (response.code != 0) throw NoteAsrRequestRejectedException(response.message)
        return response.data ?: throw IOException("服务器未返回固定材料源")
    }

    private companion object {
        val PENDING_START_PHASES = setOf(
            NoteAsrIntentPhase.Prepared,
            NoteAsrIntentPhase.Submitting,
            NoteAsrIntentPhase.VerificationPending,
        )
    }
}

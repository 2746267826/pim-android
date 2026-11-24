package com.pim.app.notes

import com.pim.core.auth.TokenManager
import com.pim.core.contract.NoteProcessingChainDto
import com.pim.core.contract.NoteProcessingChainState
import com.pim.core.contract.NoteProcessingIssueDto
import com.pim.core.contract.NoteProcessingState
import com.pim.core.contract.NoteProcessingStatusDto
import com.pim.core.contract.NoteProcessingTaskKind
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

data class NoteProcessingTarget(
    val binding: BoundUploadSession,
    val ownerName: String,
    val generation: Long,
)

data class NoteProcessingKey(
    val binding: BoundUploadSession,
    val noteId: String,
    val versionId: String,
    val generation: Long,
)

data class NoteProcessingChainView(
    val kind: NoteProcessingTaskKind,
    val state: NoteProcessingChainState,
    val phase: String?,
    val completedItems: Int,
    val failedItems: Int,
    val totalItems: Int,
    val hasResult: Boolean,
    val taskCreated: Boolean,
    val taskId: String?,
    val waitingReason: String?,
    val errors: List<String>,
)

data class NoteProcessingIssueView(
    val code: String,
    val message: String,
    val task: NoteProcessingTaskKind?,
)

data class NoteProcessingStatusView(
    val noteId: String,
    val versionId: String,
    val processingId: String,
    val state: NoteProcessingState,
    val scope: String,
    val pendingReason: String?,
    val autoProcessingEnabled: Boolean,
    val autoStarted: Boolean,
    val manualStartAvailable: Boolean,
    val incrementalScopeSupported: Boolean,
    val asr: NoteProcessingChainView,
    val derived: NoteProcessingChainView,
    val issues: List<NoteProcessingIssueView>,
    val createdAt: String,
    val updatedAt: String,
)

data class NoteProcessingEntry(
    val status: NoteProcessingStatusView? = null,
    val syncing: Boolean = false,
    val problem: String? = null,
    val observedAtMillis: Long? = null,
)

data class NoteProcessingRepositoryState(
    val entries: Map<NoteProcessingKey, NoteProcessingEntry> = emptyMap(),
) {
    fun entry(
        binding: BoundUploadSession,
        noteId: String,
        versionId: String,
        generation: Long,
    ): NoteProcessingEntry? = entries[NoteProcessingKey(binding, noteId, versionId, generation)]
}

internal fun interface NoteProcessingTransport {
    suspend fun read(noteId: String, versionId: String): NoteProcessingStatusDto
}

internal class NoteProcessingServerException(message: String) : IOException(message)

internal fun NoteProcessingStatusDto.toNoteProcessingStatusView(
    expectedNoteId: String,
    expectedVersionId: String,
): NoteProcessingStatusView {
    require(noteId == expectedNoteId) {
        "Server processing noteId $noteId does not match the pinned noteId $expectedNoteId"
    }
    require(versionId == expectedVersionId) {
        "Server processing versionId $versionId does not match the pinned versionId $expectedVersionId"
    }
    require(asr.kind == NoteProcessingTaskKind.ASR) {
        "Server processing ASR chain has unexpected kind ${asr.kind}"
    }
    require(derived.kind == NoteProcessingTaskKind.DERIVED) {
        "Server processing derived chain has unexpected kind ${derived.kind}"
    }
    return NoteProcessingStatusView(
        noteId = noteId,
        versionId = versionId,
        processingId = processingId,
        state = state,
        scope = scope,
        pendingReason = pendingReason,
        autoProcessingEnabled = autoProcessingEnabled,
        autoStarted = autoStarted,
        manualStartAvailable = manualStartAvailable,
        incrementalScopeSupported = incrementalScopeSupported,
        asr = asr.toView(),
        derived = derived.toView(),
        issues = issues.map(NoteProcessingIssueDto::toView),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun NoteProcessingChainDto.toView() = NoteProcessingChainView(
    kind = kind,
    state = state,
    phase = phase,
    completedItems = completedItems,
    failedItems = failedItems,
    totalItems = totalItems,
    hasResult = hasResult,
    taskCreated = taskCreated,
    taskId = taskId,
    waitingReason = waitingReason,
    errors = errors,
)

private fun NoteProcessingIssueDto.toView() = NoteProcessingIssueView(code, message, task)

internal fun noteProcessingStateLabel(state: NoteProcessingState): String = when (state) {
    NoteProcessingState.WAITINGFORMATERIAL -> "等待材料"
    NoteProcessingState.PENDINGPROCESSING -> "等待处理"
    NoteProcessingState.PROCESSING -> "处理中"
    NoteProcessingState.PARTIALLYCOMPLETED -> "部分完成"
    NoteProcessingState.COMPLETED -> "已完成"
    NoteProcessingState.NEEDSATTENTION -> "需要核对"
}

internal fun noteProcessingChainStateLabel(state: NoteProcessingChainState): String = when (state) {
    NoteProcessingChainState.NOTAPPLICABLE -> "不适用"
    NoteProcessingChainState.WAITINGFORMATERIAL -> "等待材料"
    NoteProcessingChainState.WAITINGFORASR -> "等待转写完成"
    NoteProcessingChainState.PENDINGSTART -> "等待开始"
    NoteProcessingChainState.QUEUED -> "已排队"
    NoteProcessingChainState.RUNNING -> "处理中"
    NoteProcessingChainState.SUCCEEDED -> "已完成"
    NoteProcessingChainState.NEEDSATTENTION -> "需要核对"
    NoteProcessingChainState.STOPPED -> "已停止"
}

internal fun noteProcessingMaterialLine(receipt: String): String = "材料上传回执：$receipt"

internal fun noteProcessingChainLine(label: String, chain: NoteProcessingChainView): String {
    val counts = if (chain.totalItems > 0) {
        " · ${chain.completedItems}/${chain.totalItems} 项"
    } else {
        ""
    }
    val failed = if (chain.failedItems > 0) " · 失败 ${chain.failedItems} 项" else ""
    val detail = when {
        chain.errors.isNotEmpty() -> " · ${chain.errors.first()}"
        !chain.waitingReason.isNullOrBlank() -> " · ${chain.waitingReason}"
        !chain.phase.isNullOrBlank() -> " · 阶段 ${chain.phase}"
        chain.hasResult -> " · 已有结果"
        else -> ""
    }
    return "$label：${noteProcessingChainStateLabel(chain.state)}$counts$failed$detail"
}

@Singleton
class NoteProcessingRepository internal constructor(
    private val targetProvider: () -> NoteProcessingTarget?,
    private val transportFactory: (NoteProcessingTarget) -> NoteProcessingTransport,
    private val nowMillis: () -> Long,
) {
    @Inject
    constructor(
        clients: ApiClientProvider,
        sessions: TokenManager,
        settings: ServerSettingsStore,
    ) : this(
        targetProvider = { currentNoteProcessingTarget(sessions, settings) },
        transportFactory = { target ->
            val service = clients.noteUploadApiService(target.binding)
            NoteProcessingTransport { noteId, versionId ->
                val response = service.getNoteProcessingStatus(noteId, versionId)
                if (response.code != 0) {
                    throw NoteProcessingServerException(
                        response.message.ifBlank { "服务器返回 code=${response.code}" },
                    )
                }
                response.data ?: throw NoteProcessingServerException(
                    response.message.ifBlank { "服务器未返回处理状态" },
                )
            }
        },
        nowMillis = System::currentTimeMillis,
    )

    private val sync = Mutex()
    private val mutableState = MutableStateFlow(NoteProcessingRepositoryState())
    val state = mutableState.asStateFlow()

    fun entry(
        binding: BoundUploadSession,
        noteId: String,
        versionId: String,
        generation: Long,
    ): NoteProcessingEntry? = state.value.entry(binding, noteId, versionId, generation)

    suspend fun refresh(
        noteId: String,
        versionId: String,
        expectedBinding: BoundUploadSession,
        expectedGeneration: Long,
    ): NoteProcessingEntry = withContext(Dispatchers.IO) {
        val key = NoteProcessingKey(expectedBinding, noteId, versionId, expectedGeneration)
        sync.withLock {
            val target = targetProvider()
            if (target == null || !target.matches(expectedBinding, expectedGeneration)) {
                return@withLock publish(
                    key,
                    NoteProcessingEntry(
                        syncing = false,
                        problem = "账号或服务器已变化，未读取服务器处理状态。",
                    ),
                )
            }
            publish(key, (state.value.entries[key] ?: NoteProcessingEntry()).copy(
                syncing = true,
                problem = null,
            ))
            try {
                val status = transportFactory(target)
                    .read(noteId, versionId)
                    .toNoteProcessingStatusView(noteId, versionId)
                val current = targetProvider()
                if (!current.matches(expectedBinding, expectedGeneration)) {
                    publish(
                        key,
                        NoteProcessingEntry(
                            syncing = false,
                            problem = "账号或服务器已变化，已丢弃旧的服务器处理状态。",
                        ),
                    )
                } else {
                    publish(
                        key,
                        NoteProcessingEntry(
                            status = status,
                            syncing = false,
                            observedAtMillis = nowMillis(),
                        ),
                    )
                }
            } catch (error: CancellationException) {
                publish(key, (state.value.entries[key] ?: NoteProcessingEntry()).copy(syncing = false))
                throw error
            } catch (error: Exception) {
                publish(
                    key,
                    (state.value.entries[key] ?: NoteProcessingEntry()).copy(
                        syncing = false,
                        problem = describeProcessingError(error),
                    ),
                )
            }
        }
    }

    private fun publish(key: NoteProcessingKey, entry: NoteProcessingEntry): NoteProcessingEntry {
        val scoped = state.value.entries.filterKeys {
            it.binding == key.binding && it.generation == key.generation
        } + (key to entry)
        mutableState.value = NoteProcessingRepositoryState(scoped)
        return entry
    }

    private fun describeProcessingError(error: Exception): String = when (error) {
        is UploadSessionChangedException -> "账号或服务器已变化，未读取服务器处理状态。"
        is HttpException -> when (error.code()) {
            404 -> "服务器未返回该固定版本的处理状态；请先确认材料上传回执。"
            else -> "服务器处理状态暂时无法读取（HTTP ${error.code()}）；上次状态保留。"
        }
        is NoteProcessingServerException -> "服务器拒绝读取处理状态：${error.message}"
        is IllegalArgumentException -> "服务器返回的处理状态与当前笔记或固定版本不匹配，已忽略。"
        is IOException -> "未能读取服务器处理状态；上次状态保留。"
        else -> "未能读取服务器处理状态；上次状态保留。"
    }
}

private fun currentNoteProcessingTarget(
    sessions: TokenManager,
    settings: ServerSettingsStore,
): NoteProcessingTarget? = runCatching {
    val origin = PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin
    var target: NoteProcessingTarget? = null
    settings.commitSessionIfCurrentServer(origin) {
        val before = sessions.sessionIdentityGeneration()
        val session = sessions.snapshot()
        val after = sessions.sessionIdentityGeneration()
        val ownerId = session.ownerId
        if (before != after || session.tokens == null || session.serverIdentity != origin || ownerId == null) {
            false
        } else {
            target = NoteProcessingTarget(
                binding = BoundUploadSession(origin, ownerId),
                ownerName = session.ownerName.orEmpty(),
                generation = after,
            )
            true
        }
    }
    target
}.getOrNull()

private fun NoteProcessingTarget?.matches(
    binding: BoundUploadSession,
    generation: Long,
): Boolean = this != null && this.binding == binding && this.generation == generation

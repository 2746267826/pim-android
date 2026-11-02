package com.pim.app.notes

import com.pim.core.contract.NoteTaskSuggestionStatusDto
import com.pim.core.contract.StartNoteTaskSuggestionExtractionRequest
import com.pim.core.contract.UpdateNoteTaskSuggestionRequest
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

fun interface NoteTaskSuggestionTransportFactory {
    fun create(target: NoteTaskSuggestionTarget): NoteTaskSuggestionTransport
}

class NoteTaskSuggestionRepository(
    private val store: NoteTaskSuggestionStore,
    private val targetProvider: () -> NoteTaskSuggestionTarget?,
    private val factory: NoteTaskSuggestionTransportFactory,
) {
    private val mutex = Mutex()
    private val stateMutex = Mutex()
    private val lifecycleLock = Any()
    private var screenGeneration = 0L
    private var activeSource: NoteTaskSuggestionSource? = null
    private val mutableState = MutableStateFlow(NoteTaskSuggestionsState())
    val state = mutableState.asStateFlow()

    fun selectSource(source: NoteTaskSuggestionSource) {
        synchronized(lifecycleLock) {
            screenGeneration++
            activeSource = source
            mutableState.value = NoteTaskSuggestionsState(
                loading = true,
                source = source,
                screenGeneration = screenGeneration,
            )
        }
    }

    suspend fun open(source: NoteTaskSuggestionSource) {
        if (!isActiveSource(source)) selectSource(source)
        refresh(source)
    }

    private fun beginRefresh(source: NoteTaskSuggestionSource): Long? = synchronized(lifecycleLock) {
        if (activeSource != source) return@synchronized null
        (++screenGeneration).also { generation ->
            mutableState.value = NoteTaskSuggestionsState(
                loading = true,
                source = source,
                screenGeneration = generation,
            )
        }
    }

    suspend fun refresh(source: NoteTaskSuggestionSource) {
        val generation = beginRefresh(source) ?: return
        withContext(Dispatchers.IO) {
            mutex.withLock { refreshLocked(source, generation) }
        }
    }

    private suspend fun refreshLocked(source: NoteTaskSuggestionSource, generation: Long) {
        if (!isActive(source, generation)) return
        stateMutex.withLock {
            if (!isActive(source, generation)) return@withLock
            val target = runCatching { targetProvider() }.getOrNull()
            if (!publishIfActive(source, generation) {
                    mutableState.update { current ->
                        current.copy(binding = target?.binding, identityGeneration = target?.identityGeneration)
                    }
                }
            ) return@withLock
            if (target == null) {
                publishIfActive(source, generation) {
                    mutableState.value = NoteTaskSuggestionsState(
                        loaded = true,
                        source = source,
                        screenGeneration = generation,
                        message = "请登录后读取任务建议；旧账号意图仍保留在本机，不会切换账号访问。",
                    )
                }
                return@withLock
            }
            var local = try {
                store.read(source, target.binding)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                publishIfActive(source, generation) {
                    mutableState.value = NoteTaskSuggestionsState(
                        loaded = true,
                        source = source,
                        binding = target.binding,
                        identityGeneration = target.identityGeneration,
                        screenGeneration = generation,
                        loadFailed = true,
                        message = "本机任务建议草稿无法通过完整性或来源校验；原文件保留，暂停覆盖。",
                    )
                }
                return@withLock
            }
            val transport = factory.create(target)
            var verifiedContent: NoteTaskSuggestionSourceContent? = null
            var sourceProblem: String? = null
            try {
                verifiedContent = transport.verifySource(source)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { sourceProblem = describe(error, "核验固定派生版本") }
            if (!isActive(source, generation)) return@withLock
            if (!sameTarget(target)) {
                publishIfActive(source, generation) {
                    mutableState.value = NoteTaskSuggestionsState(
                        loaded = true,
                        source = source,
                        screenGeneration = generation,
                        message = "账号、服务器或登录会话已变化；已丢弃迟到回执，原绑定草稿保留。",
                    )
                }
                return@withLock
            }
            var destinations = state.value.destinations.takeIf { state.value.destinationsBinding == target.binding }
                .orEmpty()
            var destinationProblem: String? = null
            if (verifiedContent != null) {
                try { destinations = transport.destinations() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { destinationProblem = describe(error, "读取文本模型目标") }
            }
            var items = emptyList<NoteTaskSuggestionItem>()
            var extraction = if (state.value.binding == target.binding &&
                state.value.identityGeneration == target.identityGeneration
            ) state.value.extraction else null
            var problem = sourceProblem ?: destinationProblem
            if (verifiedContent != null) {
                try {
                    val remote = transport.list(source).filter { it.versionId == source.versionId }
                    local = store.reconcileDrafts(source, target.binding, remote)
                    items = remote.map { suggestion ->
                        NoteTaskSuggestionItem(
                            remote = suggestion,
                            draft = local.drafts.singleOrNull { it.suggestionId == suggestion.id },
                        )
                    }.sortedBy { it.remote.ordinal }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { problem = problem ?: describe(error, "读取任务建议") }
                if (local.extractionId != null) {
                    try { extraction = transport.extraction(source, local.extractionId) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { problem = problem ?: describe(error, "核对原提取状态") }
                }
            }
            if (!isActive(source, generation)) return@withLock
            if (!sameTarget(target)) {
                publishIfActive(source, generation) {
                    mutableState.value = NoteTaskSuggestionsState(
                        loaded = true,
                        source = source,
                        screenGeneration = generation,
                        message = "账号或服务器在请求期间变化；已丢弃迟到的界面回执，原绑定数据保留。",
                    )
                }
                return@withLock
            }
            publishIfActive(source, generation) {
                mutableState.value = NoteTaskSuggestionsState(
                    loaded = true,
                    source = source,
                    binding = target.binding,
                    identityGeneration = target.identityGeneration,
                    screenGeneration = generation,
                    destinations = destinations,
                    destinationsBinding = if (destinationProblem == null && verifiedContent != null) target.binding else null,
                    frozenDestination = local.frozenExtraction?.consent?.let { consent ->
                        destinations.firstOrNull {
                            it.modelId == consent.modelId && it.modelRevision == consent.modelRevision &&
                                it.endpoint == consent.endpoint
                        } ?: NoteTaskSuggestionDestination(
                            modelId = consent.modelId,
                            modelRevision = consent.modelRevision,
                            displayName = "已冻结目标",
                            model = "原提取目标",
                            endpoint = consent.endpoint,
                        )
                    },
                    items = items,
                    extraction = extraction,
                    frozenExtraction = local.frozenExtraction != null,
                    sourceVerified = verifiedContent != null,
                    sourceCharacterCount = verifiedContent?.markdown?.length ?: 0,
                    sourceContent = verifiedContent?.markdown,
                    loadFailed = problem != null,
                    message = problem,
                )
            }
        }
    }

    private suspend fun refreshCurrentLocked(source: NoteTaskSuggestionSource) {
        val generation = beginRefresh(source) ?: return
        refreshLocked(source, generation)
    }

    fun invalidateIfTargetChanged() {
        synchronized(lifecycleLock) {
            val source = activeSource ?: return
            val current = mutableState.value
            if (current.source != source || current.loading) return
            val target = targetProvider()
            if (target != null && current.binding == target.binding &&
                current.identityGeneration == target.identityGeneration
            ) return
            screenGeneration++
            mutableState.value = NoteTaskSuggestionsState(
                source = source,
                screenGeneration = screenGeneration,
                message = "账号、服务器或登录会话已变化；旧建议已清除，请显式刷新核验当前绑定。",
            )
        }
    }

    suspend fun extract(
        scope: NoteTaskSuggestionActionScope,
        destination: NoteTaskSuggestionDestination,
        chargeAcknowledged: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (source, target) = requireActionContext(scope)
                val verified = factory.create(target).verifySource(source).source
                require(verified == source) { "固定正文或来源摘要已变化；未发送提取请求" }
                require(state.value.sourceCharacterCount <= MAX_SOURCE_CHARACTERS) {
                    "固定笔记正文超过单次提取上限 ${MAX_SOURCE_CHARACTERS} 字；不会静默截断"
                }
                ensureActionContext(source, target)
                val existing = store.read(source, target.binding).frozenExtraction
                val request = if (existing != null) {
                    check(existing.expectedUserId == target.binding.ownerId && existing.noteId == source.noteId &&
                        existing.artifactId == source.artifactId && existing.versionId == source.versionId &&
                        existing.expectedContentSha256.equals(source.contentSha256, true)) {
                        "冻结请求与当前账号或固定来源不一致；已停止重试"
                    }
                    require(existing.consent.modelId == destination.modelId &&
                        existing.consent.modelRevision == destination.modelRevision &&
                        existing.consent.endpoint == destination.endpoint) {
                        "已有提取回执未知；必须先按原模型目标重试，不可改用新目标"
                    }
                    existing
                } else {
                    require(chargeAcknowledged) { "请明确确认固定正文发送与可能产生的费用" }
                    check(state.value.destinationsBinding == target.binding &&
                        state.value.destinations.any { it == destination }) { "目标目录已变化，请刷新并重新确认" }
                    check(state.value.extraction?.status !in setOf(
                        com.pim.core.contract.NoteTaskSuggestionExtractionStatusDto.QUEUED,
                        com.pim.core.contract.NoteTaskSuggestionExtractionStatusDto.RUNNING,
                    )) { "已有提取仍在处理中；请读取原记录，不会新建第二次提取" }
                    check(sameTarget(target)) { "账号、服务器或登录会话已变化" }
                    StartNoteTaskSuggestionExtractionRequest(
                        acknowledgePossibleCharge = true,
                        artifactId = source.artifactId,
                        consent = destination.toWire(),
                        expectedContentSha256 = source.contentSha256,
                        expectedUserId = target.binding.ownerId,
                        noteId = source.noteId,
                        requestId = UUID.randomUUID().toString(),
                        versionId = source.versionId,
                    ).also { store.freezeExtraction(source, target.binding, it) }
                }
                try {
                    ensureActionContext(source, target)
                    val created = factory.create(target).startExtraction(request)
                    store.recordExtraction(source, target.binding, request.requestId, created.id)
                    if (isCurrentScope(source, target)) refreshCurrentLocked(source)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    publishMessage(source, target,
                        "${describe(error, "提交任务提取")}；请求标识和模型目标已冻结，重试会复用原请求。")
                }
            }
        }

    suspend fun stageDraft(
        scope: NoteTaskSuggestionActionScope,
        suggestionId: String,
        title: String,
        description: String,
        priority: Int,
        due: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, target) = requireActionContext(scope)
            val item = requireDraftItem(suggestionId, source)
            check(item.remote.status == NoteTaskSuggestionStatusDto.DRAFT && !item.confirmationPending) {
                "只有未确认建议可编辑；待确认请求需先核对原修订"
            }
            val baseline = item.draft ?: NoteTaskSuggestionDraft(
                suggestionId = suggestionId,
                expectedRevision = item.remote.revision,
                title = item.remote.title,
                description = item.remote.description.orEmpty(),
                priority = item.remote.priority,
                due = suggestionDueInput(item.remote.due),
                dirty = false,
                updatedAtMillis = System.currentTimeMillis(),
            )
            check(baseline.expectedRevision == item.remote.revision) {
                "服务端修订已变化；本机稿保留，刷新后手动处理冲突"
            }
            val staged = baseline.copy(
                title = title,
                description = description,
                priority = priority,
                due = due,
                dirty = title != item.remote.title || description != item.remote.description.orEmpty() ||
                    priority != item.remote.priority || due != suggestionDueInput(item.remote.due),
                updatedAtMillis = System.currentTimeMillis(),
            )
            val publishedDraft = if (staged.dirty) {
                store.putDraft(source, target.binding, staged).drafts.single { it.suggestionId == suggestionId }
            } else {
                if (item.draft != null) store.removeDraft(source, target.binding, suggestionId)
                null
            }
            if (isCurrentScope(source, target)) {
                publishDraft(suggestionId, publishedDraft)
            }
        }
    }

    suspend fun saveDraft(
        scope: NoteTaskSuggestionActionScope,
        suggestionId: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, target) = requireActionContext(scope)
            val item = requireDraftItem(suggestionId, source)
            val draft = item.draft ?: error("没有待保存的本机修订")
            check(item.remote.status == NoteTaskSuggestionStatusDto.DRAFT && draft.dirty &&
                !item.confirmationPending && !item.hasConflict) { "草稿状态或修订基线已变化" }
            try {
                ensureActionContext(source, target)
                val updated = factory.create(target).update(
                    source,
                    suggestionId,
                    UpdateNoteTaskSuggestionRequest(
                        calendarId = item.remote.calendarId,
                        description = draft.description.ifBlank { null },
                        due = suggestionDueWire(draft.due),
                        expectedRevision = draft.expectedRevision,
                        priority = draft.priority,
                        taskBookId = item.remote.taskBookId,
                        title = draft.title.trim(),
                    ),
                )
                ensureActionContext(source, target)
                store.recordSavedDraft(source, target.binding, draft, draft.copy(
                    expectedRevision = updated.revision,
                    title = updated.title,
                    description = updated.description.orEmpty(),
                    priority = updated.priority,
                    due = suggestionDueInput(updated.due),
                    dirty = false,
                    confirmRequestId = null,
                    confirmRevision = null,
                ))
                if (isCurrentScope(source, target)) refreshCurrentLocked(source)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                publishMessage(source, target, "${describe(error, "保存建议修订")}；本机草稿仍保留。")
            }
        }
    }

    suspend fun dismiss(
        scope: NoteTaskSuggestionActionScope,
        suggestionId: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, target) = requireActionContext(scope)
            val item = requireDraftItem(suggestionId, source)
            check(item.remote.status == NoteTaskSuggestionStatusDto.DRAFT && !item.confirmationPending) {
                "该建议已处理或存在未确认的创建请求"
            }
            check(item.draft?.dirty != true) { "请先保存或明确放弃本机未同步修订，再放弃服务端建议" }
            try {
                ensureActionContext(source, target)
                factory.create(target).dismiss(source, item.remote)
                ensureActionContext(source, target)
                if (isCurrentScope(source, target)) refreshCurrentLocked(source)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                publishMessage(source, target, "${describe(error, "放弃任务建议")}；服务器状态未标记成功。")
            }
        }
    }

    suspend fun confirm(
        scope: NoteTaskSuggestionActionScope,
        suggestionId: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, target) = requireActionContext(scope)
            val item = requireDraftItem(suggestionId, source)
            check(item.remote.status == NoteTaskSuggestionStatusDto.DRAFT && item.draft?.dirty != true) {
                "请先保存或放弃本机编辑，再确认创建任务"
            }
            val current = item.draft
            if (current?.confirmationPending == true) {
                check(current.confirmRevision == item.remote.revision) {
                    "未确认的创建请求属于旧修订；刷新核对前已锁定，不会使用新修订重复创建"
                }
            }
            val frozen = current?.takeIf { it.confirmationPending } ?: NoteTaskSuggestionDraft(
                suggestionId = suggestionId,
                expectedRevision = item.remote.revision,
                title = item.remote.title,
                description = item.remote.description.orEmpty(),
                priority = item.remote.priority,
                due = suggestionDueInput(item.remote.due),
                dirty = false,
                confirmRequestId = UUID.randomUUID().toString(),
                confirmRevision = item.remote.revision,
                updatedAtMillis = System.currentTimeMillis(),
            )
            val prepared = store.putDraft(source, target.binding, frozen)
                .drafts.single { it.suggestionId == suggestionId }
            if (isCurrentScope(source, target)) publishDraft(suggestionId, prepared)
            try {
                ensureActionContext(source, target)
                factory.create(target).confirm(source, item.remote, checkNotNull(prepared.confirmRequestId))
                ensureActionContext(source, target)
                if (isCurrentScope(source, target)) refreshCurrentLocked(source)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                publishMessage(source, target, "${describe(error, "确认创建任务")}；冻结的确认标识会在重试时复用。")
            }
        }
    }

    suspend fun discardDraft(
        scope: NoteTaskSuggestionActionScope,
        suggestionId: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, target) = requireActionContext(scope)
            val item = requireDraftItem(suggestionId, source)
            check(item.draft != null && !item.confirmationPending) { "没有可放弃的本机修订" }
            store.removeDraft(source, target.binding, suggestionId)
            if (isCurrentScope(source, target)) refreshCurrentLocked(source)
        }
    }

    suspend fun rebaseDraftAfterConflict(
        scope: NoteTaskSuggestionActionScope,
        suggestionId: String,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, target) = requireActionContext(scope)
            val item = requireDraftItem(suggestionId, source)
            val draft = item.draft ?: error("没有待核对的本机修订")
            check(item.hasConflict && item.remote.status == NoteTaskSuggestionStatusDto.DRAFT &&
                !item.confirmationPending && !draft.confirmationPending) {
                "当前建议不支持冲突基线更新"
            }
            val rebased = store.rebaseDraft(source, target.binding, draft, item.remote.revision)
            if (isCurrentScope(source, target)) publishDraft(suggestionId, rebased)
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            screenGeneration++
            activeSource = null
            mutableState.value = NoteTaskSuggestionsState()
        }
    }

    private fun publishDraft(id: String, draft: NoteTaskSuggestionDraft?) {
        synchronized(lifecycleLock) {
            if (activeSource != null && activeSource == state.value.source) mutableState.update { current -> current.copy(items = current.items.map { item ->
                if (item.remote.id == id) item.copy(draft = draft) else item
            }) }
        }
    }

    private fun requireDraftItem(id: String, source: NoteTaskSuggestionSource): NoteTaskSuggestionItem =
        state.value.items.singleOrNull { item ->
            val remote = item.remote
            remote.id == id && remote.noteId == source.noteId && remote.artifactId == source.artifactId &&
                remote.versionId == source.versionId &&
                remote.sourceContentSha256.equals(source.contentSha256, true) &&
                remote.sourceInputSha256.equals(source.inputSha256, true) &&
                remote.sourceManifestSha256.equals(source.manifestSha256, true) &&
                remote.sourceGenerationTaskId == source.generationTaskId
        } ?: error("当前账号、笔记、派生版本或来源摘要没有此建议")

    private fun requireActionContext(
        scope: NoteTaskSuggestionActionScope,
    ): Pair<NoteTaskSuggestionSource, NoteTaskSuggestionTarget> {
        invalidateIfTargetChanged()
        val current = state.value
        check(current.actionScope == scope) { "操作请求属于旧固定来源或旧登录会话；请刷新后重新确认" }
        val source = current.source ?: error("请先打开固定笔记版本")
        val target = targetProvider() ?: throw IOException("请先登录")
        check(current.loaded && !current.loading && !current.loadFailed && current.sourceVerified &&
            current.source == source && current.binding == target.binding &&
            current.identityGeneration == target.identityGeneration &&
            current.screenGeneration == scope.screenGeneration && isActiveScreen(source, scope.screenGeneration) &&
            sameTarget(target)) {
            "账号、服务器或登录会话已变化，请刷新后重试"
        }
        return source to target
    }

    private fun sameTarget(target: NoteTaskSuggestionTarget) =
        targetProvider()?.let { it == target } == true

    private fun isActiveSource(source: NoteTaskSuggestionSource?) = synchronized(lifecycleLock) {
        source != null && activeSource == source
    }

    private fun isActiveScreen(source: NoteTaskSuggestionSource, generation: Long) =
        synchronized(lifecycleLock) { activeSource == source && screenGeneration == generation }

    private fun isCurrentScope(source: NoteTaskSuggestionSource, target: NoteTaskSuggestionTarget): Boolean {
        val current = state.value
        return isActiveSource(source) && current.source == source && current.binding == target.binding &&
            current.identityGeneration == target.identityGeneration &&
            current.screenGeneration?.let { isActiveScreen(source, it) } == true && sameTarget(target)
    }

    private fun ensureActionContext(source: NoteTaskSuggestionSource, target: NoteTaskSuggestionTarget) {
        invalidateIfTargetChanged()
        val current = state.value
        check(current.loaded && !current.loading && !current.loadFailed && current.sourceVerified &&
            current.source == source && current.binding == target.binding &&
            current.identityGeneration == target.identityGeneration &&
            current.screenGeneration?.let { isActiveScreen(source, it) } == true && sameTarget(target)) {
            "账号、服务器、登录会话或固定来源已变化；原草稿保留"
        }
    }

    private fun publishMessage(
        source: NoteTaskSuggestionSource,
        target: NoteTaskSuggestionTarget,
        message: String,
    ) {
        synchronized(lifecycleLock) {
            if (activeSource == source && state.value.source == source && !state.value.loading &&
                state.value.binding == target.binding &&
                state.value.identityGeneration == target.identityGeneration &&
                state.value.screenGeneration?.let { isActiveScreen(source, it) } == true && sameTarget(target)
            ) mutableState.update { it.copy(message = message) }
        }
    }

    private fun isActive(source: NoteTaskSuggestionSource, generation: Long) = synchronized(lifecycleLock) {
        activeSource == source && screenGeneration == generation
    }

    private inline fun publishIfActive(
        source: NoteTaskSuggestionSource,
        generation: Long,
        publish: () -> Unit,
    ): Boolean = synchronized(lifecycleLock) {
        if (activeSource != source || screenGeneration != generation) false
        else {
            publish()
            true
        }
    }

    private fun describe(error: Exception, action: String): String = when {
        error is HttpException && error.code() == 401 -> "$action 需要重新登录"
        error is HttpException && error.code() == 403 -> "$action 没有当前账号权限"
        error is HttpException && error.code() == 404 -> "$action 未找到固定版本或建议"
        error is HttpException && error.code() == 409 -> "$action 的服务端修订已变化；本机稿保留，请刷新核对"
        error is NoteTaskSuggestionRequestRejectedException -> error.message ?: "$action 被服务端拒绝"
        error is IOException -> "$action 网络或回执未确认"
        else -> "$action 未确认"
    }

    companion object {
        const val MAX_SOURCE_CHARACTERS = 180_000
    }
}

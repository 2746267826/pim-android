package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

data class NoteArtifactTarget(
    val binding: BoundUploadSession,
    val ownerName: String,
)

fun interface NoteArtifactTransportFactory {
    fun create(target: NoteArtifactTarget): NoteArtifactTransport
}

data class NoteArtifactRepositoryState(
    val loaded: Boolean = false,
    val syncing: Boolean = false,
    val noteId: String? = null,
    val tasks: List<NoteArtifactTaskRecord> = emptyList(),
    val drafts: List<NoteArtifactDraft> = emptyList(),
    val destinations: List<NoteArtifactDestination> = emptyList(),
    val destinationProblem: String? = null,
    val destinationBinding: BoundUploadSession? = null,
    val artifactBinding: BoundUploadSession? = null,
    val artifactRefs: Map<NoteArtifactKind, NoteArtifactArtifactRefProjection> = emptyMap(),
    val versions: Map<NoteArtifactKind, List<NoteArtifactVersionProjection>> = emptyMap(),
    val selectedTaskId: String? = null,
    val detail: NoteArtifactTaskDetail? = null,
    val message: String? = null,
    val loadFailed: Boolean = false,
)

/**
 * Owner/server-bound orchestration for durable derived-task intents, local
 * drafts, explicit server revisions and candidate history. Transport calls are
 * supplied by the generated-contract client; this class holds no wire types.
 */
class NoteArtifactRepository(
    private val tasks: NoteArtifactTaskStore,
    private val drafts: NoteArtifactDraftStore,
    private val materials: NoteMaterialStore,
    private val targetProvider: () -> NoteArtifactTarget?,
    private val factory: NoteArtifactTransportFactory,
) {
    private val initialization = Mutex()
    private val sync = Mutex()
    private val mutableState = MutableStateFlow(NoteArtifactRepositoryState())
    val state = mutableState.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (state.value.loaded) return@withLock
            publish()
        }
    }

    /**
     * Reads only the current owner/server catalogue, tasks and drafts. Material
     * or account changes never re-target the screen to another owner's data.
     */
    suspend fun refresh(
        noteId: String,
        materialVersionId: String? = null,
        selectedTaskId: String? = null,
    ) = withContext(Dispatchers.IO) {
        initialize()
        sync.withLock {
            val target = targetProvider()
            if (target == null) {
                mutableState.update {
                    it.copy(
                        loaded = true,
                        syncing = false,
                        noteId = noteId,
                        tasks = emptyList(),
                        drafts = emptyList(),
                        destinations = emptyList(),
                        destinationProblem = "请先登录；旧派生结果仍保留本机，但不会用当前或空账号访问。",
                        destinationBinding = null,
                        artifactBinding = null,
                        artifactRefs = emptyMap(),
                        versions = emptyMap(),
                        detail = null,
                        message = "请先登录后核对固定材料与派生目标。",
                    )
                }
                return@withLock
            }
            mutableState.update { it.copy(loaded = true, syncing = true, noteId = noteId, message = null) }
            val transport = factory.create(target)
            var destinationProblem: String? = null
            val destinations = try {
                transport.destinations()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                destinationProblem = describe(error, "读取派生目标目录")
                emptyList()
            }
            val taskState = runCatching { tasks.load() }
                .getOrElse { NoteArtifactTasksState(loaded = true, loadFailed = true) }
            val currentTasks = taskState.tasks
                .filter { it.binding == target.binding && it.noteId == noteId }
                .sortedByDescending { it.updatedAtMillis }
            val chosenTaskId = selectedTaskId?.takeIf { id -> currentTasks.any { it.taskId == id } }
                ?: currentTasks.firstOrNull()?.taskId
            val chosen = currentTasks.firstOrNull { it.taskId == chosenTaskId }
            val fixedVersionId = chosen?.materialVersionId ?: materialVersionId

            val canReuseMetadata = state.value.artifactBinding == target.binding &&
                state.value.noteId == noteId &&
                state.value.artifactRefs.values.all { !it.revision.isNullOrBlank() }
            var refs = if (canReuseMetadata) state.value.artifactRefs else emptyMap()
            var versions = if (canReuseMetadata) state.value.versions else emptyMap()
            var artifactProblem: String? = null
            var metadataLoaded = false
            try {
                val metadata = loadArtifactMetadata(transport, noteId)
                refs = metadata.refs
                versions = metadata.versions
                metadataLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                artifactProblem = describeArtifactMetadataProblem(error)
            }
            var detail: NoteArtifactTaskDetail? = null
            var detailProblem: String? = null
            if (chosen != null) {
                try {
                    detail = transport.detail(chosen.taskId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    detailProblem = describe(error, "读取任务步骤与覆盖")
                }
            }
            val partialRefs = detail?.artifacts.orEmpty()
            val needsMetadataRefresh = partialRefs.any { partial ->
                val complete = refs[partial.kind]
                complete == null ||
                    complete.artifactId != partial.artifactId ||
                    complete.revision.isNullOrBlank() ||
                    (partial.headVersionId != null && partial.headVersionId != complete.headVersionId)
            }
            if (needsMetadataRefresh && metadataLoaded) {
                try {
                    val metadata = loadArtifactMetadata(transport, noteId)
                    refs = metadata.refs
                    versions = metadata.versions
                    artifactProblem = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    artifactProblem = describeArtifactMetadataProblem(error)
                }
            }
            if (partialRefs.any { partial ->
                    val complete = refs[partial.kind]
                    complete == null ||
                        complete.artifactId != partial.artifactId ||
                        complete.revision.isNullOrBlank()
                }
            ) {
                artifactProblem =
                    "任务详情只包含派生结果的部分引用；未用其覆盖完整服务端元数据，本机稿保留。请刷新后重试。"
            }

            val loadedDrafts = if (fixedVersionId != null) {
                runCatching { ensureDrafts(target.binding, noteId, fixedVersionId, refs, versions) }
                    .getOrElse {
                        artifactProblem = it.message ?: "本机派生稿无法准备"
                        runCatching { drafts.drafts(target.binding).filter { draft -> draft.noteId == noteId } }
                            .getOrDefault(emptyList())
                    }
            } else {
                runCatching { drafts.drafts(target.binding).filter { draft -> draft.noteId == noteId } }
                    .getOrDefault(emptyList())
            }
            mutableState.update {
                it.copy(
                    loaded = true,
                    syncing = false,
                    noteId = noteId,
                    tasks = currentTasks,
                    drafts = loadedDrafts,
                    destinations = destinations,
                    destinationProblem = destinationProblem,
                    destinationBinding = if (destinationProblem == null) target.binding else null,
                    artifactBinding = if (refs.isNotEmpty() &&
                        refs.values.all { !it.revision.isNullOrBlank() }
                    ) {
                        target.binding
                    } else {
                        null
                    },
                    artifactRefs = refs,
                    versions = versions,
                    selectedTaskId = chosenTaskId,
                    detail = detail,
                    message = destinationProblem ?: artifactProblem ?: detailProblem,
                    loadFailed = taskState.loadFailed,
                )
            }
        }
    }

    /**
     * Prepares the durable TaskId before sending, then sends exactly that task.
     * The current destination catalogue and a fresh explicit confirmation are
     * both required; local material snapshots are re-read, never inferred.
     */
    suspend fun start(
        noteId: String,
        materialVersionId: String,
        asrTaskId: String?,
        destination: NoteArtifactDestination,
        freshlyConfirmed: Boolean,
    ): String = withContext(Dispatchers.IO) {
        initialize()
        require(freshlyConfirmed) { "必须明确同意本次模型目标与可能产生的费用" }
        sync.withLock {
            val target = targetProvider() ?: error("请先登录")
            check(state.value.destinationBinding == target.binding) {
                "账号或服务器已变化，请重新读取派生目标目录"
            }
            check(state.value.destinations.any { it == destination }) {
                "派生目标已变化，请刷新并重新确认"
            }
            val snapshot = materials.uploadSnapshot(materialVersionId)
            require(snapshot.noteId == noteId) { "固定材料属于其他笔记" }
            val controller = controller(target)
            val created = controller.prepare(
                noteId = noteId,
                materialVersionId = materialVersionId,
                sourceTitle = runCatching { materials.read(materialVersionId).document.title }
                    .getOrDefault(""),
                materialCreatedAtMillis = runCatching {
                    materials.entries().firstOrNull { it.id == materialVersionId }?.createdAtMillis
                }.getOrNull(),
                asrTaskId = asrTaskId,
                inputSha256 = materialDigest(snapshot.inputJson),
                manifestSha256 = materialDigest(snapshot.manifestJson),
                consent = destination.asConsent(),
                destinationDisplayName = destination.displayName,
                destinationModel = destination.model,
                destinationCapabilities = destination.capabilities,
                destinationBackgroundAvailable = destination.backgroundProcessingAvailable,
                ownerName = target.ownerName,
            )
            try {
                controller.start(created.taskId, freshlyConfirmed = true)
                publish("任务已保存；固定材料、完整记录与独立分析来源不会随新稿变化。")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(describe(error, "创建派生任务"))
            }
            created.taskId
        }
    }

    suspend fun verifyTask(id: String) = withContext(Dispatchers.IO) {
        initialize()
        try {
            controllerFor(id).verify(id)
            publish("已按同一 TaskId 核对派生任务状态。")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "核对派生任务"))
        }
    }

    suspend fun replayStart(id: String, freshlyConfirmed: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        try {
            controllerFor(id).replayStart(id, freshlyConfirmed)
            publish("已使用原 TaskId 核对创建结果，没有生成第二个任务。")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "按原 TaskId 核对创建"))
        }
    }

    suspend fun stop(id: String) = withContext(Dispatchers.IO) {
        initialize()
        try {
            controllerFor(id).stop(id)
            publish("停止已保存；已发出的模型调用不保证撤销费用。")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "停止派生任务"))
        }
    }

    suspend fun retry(
        id: String,
        stepKeys: List<String>,
        duplicateChargeConfirmed: Boolean,
    ) = withContext(Dispatchers.IO) {
        initialize()
        try {
            controllerFor(id).retry(id, stepKeys, duplicateChargeConfirmed)
            publish("已保存所选步骤的恢复请求；已完成步骤不会重跑。")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "恢复失败步骤"))
        }
    }

    suspend fun replayRetry(id: String, freshlyConfirmed: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        try {
            controllerFor(id).replayRetry(id, freshlyConfirmed)
            publish("已使用原恢复标识核对，没有另建恢复意图。")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "按原恢复标识核对"))
        }
    }

    /**
     * Regeneration always creates a new TaskId and only appends a model
     * candidate. It never rewrites the manual draft or the current head.
     */
    suspend fun regenerate(kind: NoteArtifactKind) = withContext(Dispatchers.IO) {
        initialize()
        val noteId = state.value.noteId ?: return@withContext
        val latest = state.value.tasks.filter { it.supports(kind) }.maxByOrNull { it.updatedAtMillis }
        if (latest == null) {
            publish("尚未创建该结果的派生任务，请先明确选择目标并开始生成。")
            return@withContext
        }
        if (latest.localPhase in setOf(
                NoteArtifactIntentPhase.Submitting,
                NoteArtifactIntentPhase.VerificationPending,
            )
        ) {
            publish("上一次请求的回执仍未确认；请先按同一 TaskId 核对，不另建任务。")
            return@withContext
        }
        val destination = state.value.destinations.firstOrNull { it.asConsent() == latest.consent }
        if (destination == null) {
            publish("原目标已不在当前账号目录中；请刷新并重新明确同意目标。")
            return@withContext
        }
        val taskId = start(
            noteId = noteId,
            materialVersionId = latest.materialVersionId,
            asrTaskId = latest.asrTaskId,
            destination = destination,
            freshlyConfirmed = true,
        )
        refresh(noteId, latest.materialVersionId, taskId)
        publish("已提交新的候选生成；人工稿与服务端当前版本保持不变。")
    }

    suspend fun stageText(
        localDraftId: String,
        expectedLocalRevision: Long,
        text: String,
    ) = withContext(Dispatchers.IO) {
        val target = targetProvider() ?: return@withContext
        when (val result = drafts.stageText(target.binding, localDraftId, expectedLocalRevision, text)) {
            is NoteArtifactDraftWriteResult.Written -> publish()
            is NoteArtifactDraftWriteResult.Conflict -> {
                publish(
                    "本机输入与另一份修改冲突；当前文字保持不动，本次输入已完整保留。" +
                        "原文与冲突稿都没有被覆盖。",
                )
            }
        }
    }

    suspend fun saveLocalDraft(
        localDraftId: String,
        expectedLocalRevision: Long,
        text: String,
    ) = withContext(Dispatchers.IO) {
        val target = targetProvider() ?: return@withContext
        when (val result = drafts.save(target.binding, localDraftId, expectedLocalRevision, text)) {
            is NoteArtifactDraftWriteResult.Written -> publish("本机稿已保存；尚未同步服务端。")
            is NoteArtifactDraftWriteResult.Conflict -> {
                publish("本机稿版本已变化；本次全文与当前稿都保留，请核对后再保存。")
            }
        }
    }

    suspend fun acceptRemoteDraft(localDraftId: String, expectedLocalRevision: Long) =
        withContext(Dispatchers.IO) {
            val target = targetProvider() ?: return@withContext
            when (val result = drafts.acceptRemote(target.binding, localDraftId, expectedLocalRevision)) {
                is NoteArtifactRemoteAcceptResult.Accepted ->
                    publish("已显式采用服务端候选；本机编辑基线已更新。")
                is NoteArtifactRemoteAcceptResult.BlockedByDirty ->
                    publish("本机稿有未同步修改；服务端候选保留，未覆盖本机文字。")
                is NoteArtifactRemoteAcceptResult.StaleRevision ->
                    publish("本机稿版本已变化；候选保留，本次未应用。")
                is NoteArtifactRemoteAcceptResult.NoPendingSnapshot ->
                    publish("没有待采用的服务端候选。")
            }
        }

    /**
     * Saves the full local text as an explicit server manual revision. A
     * non-head conflict receipt preserves both server versions and the local
     * draft; only a confirmed head advances the local editing baseline.
     */
    suspend fun saveManualRevisionToServer(
        kind: NoteArtifactKind,
        artifactId: String,
        localDraftId: String,
        expectedLocalRevision: Long,
        expectedHeadRevision: String,
        expectedHeadVersionId: String?,
        contentMarkdown: String,
    ) = withContext(Dispatchers.IO) {
        val target = targetProvider() ?: return@withContext
        val transport = factory.create(target)
        try {
            val receipt = transport.saveManualRevision(
                artifactId = artifactId,
                revisionId = newNoteId(),
                expectedHeadRevision = expectedHeadRevision,
                expectedHeadVersionId = expectedHeadVersionId,
                contentMarkdown = contentMarkdown,
            )
            if (receipt.isHead) {
                when (val accepted = drafts.markServerAccepted(
                    target.binding,
                    localDraftId,
                    expectedLocalRevision,
                    receipt.versionId,
                    receipt.artifactRevision,
                )) {
                    is NoteArtifactServerAcceptResult.Accepted ->
                        publish("人工全文已保存为当前版本，本机编辑基线已同步。")
                    is NoteArtifactServerAcceptResult.StaleRevision ->
                        publish("人工全文已保存，但本机稿在保存期间又变化；新输入仍保留，未覆盖。")
                }
            } else {
                publish(
                    "服务器已保留本机全文与原版本，但本机稿未成为当前版本；双方内容均保留，" +
                        "本机编辑基线未改变。",
                )
            }
            val noteId = state.value.noteId
            if (noteId != null) refresh(noteId, selectedTaskId = state.value.selectedTaskId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "保存人工全文"))
        }
    }

    suspend fun selectCandidate(
        artifactId: String,
        expectedHeadRevision: String,
        versionId: String,
    ) = withContext(Dispatchers.IO) {
        val target = targetProvider() ?: return@withContext
        try {
            factory.create(target).selectVersion(artifactId, expectedHeadRevision, versionId)
            publish("已显式选用该版本；本机稿文字与编辑基线没有被改写。")
            state.value.noteId?.let { noteId ->
                refresh(noteId, selectedTaskId = state.value.selectedTaskId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publish(describe(error, "选择服务端版本"))
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    fun canStartWith(binding: BoundUploadSession?) =
        binding != null && state.value.destinationBinding == binding && state.value.destinations.isNotEmpty()

    private suspend fun loadArtifactMetadata(
        transport: NoteArtifactTransport,
        noteId: String,
    ): ArtifactMetadata {
        val refs = transport.artifacts(noteId).associateBy { it.kind }
        val versions = refs.mapValues { (_, ref) -> transport.versions(ref.artifactId) }
        return ArtifactMetadata(refs = refs, versions = versions)
    }

    private fun ensureDrafts(
        binding: BoundUploadSession,
        noteId: String,
        materialVersionId: String,
        refs: Map<NoteArtifactKind, NoteArtifactArtifactRefProjection>,
        versions: Map<NoteArtifactKind, List<NoteArtifactVersionProjection>>,
    ): List<NoteArtifactDraft> {
        NoteArtifactKind.entries.forEach { kind ->
            val artifact = refs[kind]
            val history = versions[kind].orEmpty()
            val head = history.firstOrNull { it.isHead } ?: artifact?.headVersionId?.let { id ->
                history.firstOrNull { it.versionId == id }
            }
            drafts.getOrCreate(
                binding = binding,
                noteId = noteId,
                materialVersionId = materialVersionId,
                kind = kind,
                initialText = head?.contentMarkdown.orEmpty(),
                baseServerArtifactVersionId = head?.versionId ?: artifact?.headVersionId,
                expectedServerRevision = artifact?.revision,
            )
        }
        return drafts.drafts(binding).filter { it.noteId == noteId }
    }

    private fun controllerFor(id: String): NoteArtifactController {
        val record = tasks.read(id)
        val target = targetProvider() ?: throw UploadSessionChangedException()
        if (record.binding != target.binding) throw NoteArtifactBindingChangedException()
        return controller(target)
    }

    private fun controller(target: NoteArtifactTarget) =
        NoteArtifactController(tasks, factory.create(target), target.binding)

    private fun publish(message: String? = null) {
        val target = targetProvider()
        val taskState = runCatching { tasks.load() }
            .getOrElse { NoteArtifactTasksState(loaded = true, loadFailed = true) }
        val visibleTasks = if (target == null) emptyList() else taskState.tasks
            .filter { it.binding == target.binding && it.noteId == state.value.noteId }
        val visibleDrafts = if (target == null) emptyList() else runCatching {
            drafts.drafts(target.binding).filter { it.noteId == state.value.noteId }
        }.getOrDefault(emptyList())
        mutableState.update {
            it.copy(
                loaded = true,
                syncing = false,
                tasks = visibleTasks,
                drafts = visibleDrafts,
                loadFailed = taskState.loadFailed,
                message = message,
            )
        }
    }

    private fun describe(error: Exception, action: String): String = when {
        error is UploadSessionChangedException || error is NoteArtifactBindingChangedException ->
            "账号或服务器已变化；$action 未继续。旧任务、历史与本机稿保留。"
        error is HttpException && error.code() == 401 ->
            "$action 需要重新登录；没有使用新账号访问旧任务。"
        error is HttpException && error.code() == 403 ->
            "$action 被拒绝：当前账号尚未开放派生入口或没有权限。"
        error is HttpException && error.code() == 404 ->
            "$action 未找到任务；保留原 TaskId，不会自动另建。"
        error is HttpException && error.code() == 409 ->
            "$action 遇到固定输入或版本冲突；本机与服务端记录均保留，请刷新核对。"
        error is HttpException && error.code() == 413 ->
            "$action 触及上限；没有扩大范围或删除记录。"
        error is HttpException && error.code() == 429 ->
            "$action 被要求稍后重试；未自动重发。"
        error is NoteArtifactTaskMissingException -> error.message ?: "$action 未确认。"
        error is NoteArtifactRequestRejectedException -> error.message ?: "$action 被服务器拒绝。"
        error is IOException -> "$action 的网络或回执未确认；不会把超时或缺失当作成功。"
        else -> "$action 未确认；固定材料与已有结果保留，请刷新后核对。"
    }

    private fun describeArtifactMetadataProblem(error: Exception) =
        describe(error, "刷新派生结果元数据") +
            "；不会用任务详情里的部分引用覆盖完整元数据，已有引用与本机稿保留。"

    private data class ArtifactMetadata(
        val refs: Map<NoteArtifactKind, NoteArtifactArtifactRefProjection>,
        val versions: Map<NoteArtifactKind, List<NoteArtifactVersionProjection>>,
    )

    companion object {
        fun at(
            noBackupDirectory: java.io.File,
            materials: NoteMaterialStore,
            targetProvider: () -> NoteArtifactTarget?,
            factory: NoteArtifactTransportFactory,
        ) = NoteArtifactRepository(
            tasks = NoteArtifactTaskStore.at(noBackupDirectory),
            drafts = NoteArtifactDraftStore.at(noBackupDirectory),
            materials = materials,
            targetProvider = targetProvider,
            factory = factory,
        )
    }
}

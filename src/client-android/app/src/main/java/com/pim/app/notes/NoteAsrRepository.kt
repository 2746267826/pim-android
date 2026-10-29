package com.pim.app.notes

import android.content.Context
import com.pim.core.auth.TokenManager
import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

@Singleton
class NoteAsrRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val clients: ApiClientProvider,
    private val sessions: TokenManager,
    private val settings: ServerSettingsStore,
) {
    private val store = NoteAsrTaskStore.at(context.noBackupFilesDir)
    private val materials = NoteMaterialStore.at(context.noBackupFilesDir)
    private val initialization = Mutex()
    private val sync = Mutex()
    private val mutableState = MutableStateFlow(NoteAsrRepositoryState())
    val state = mutableState.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (state.value.loaded) return@withLock
            val loaded = try { store.load() } catch (_: Exception) {
                NoteAsrTasksState(loaded = true, loadFailed = true)
            }
            mutableState.value = state.value.copy(
                loaded = true,
                tasks = loaded.tasks,
                loadFailed = loaded.loadFailed,
            )
        }
    }

    fun target(): NoteUploadTarget? = runCatching {
        val origin = PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin
        var target: NoteUploadTarget? = null
        settings.commitSessionIfCurrentServer(origin) {
            val session = sessions.snapshot()
            val ownerId = session.ownerId
            if (session.tokens == null || session.serverIdentity != origin || ownerId == null) false
            else {
                target = NoteUploadTarget(BoundUploadSession(origin, ownerId), session.ownerName.orEmpty())
                true
            }
        }
        target
    }.getOrNull()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        initialize()
        sync.withLock {
            val current = target()
            if (current == null) {
                mutableState.update { it.copy(
                    syncing = false,
                    sources = emptyList(),
                    sourceProblem = "请先登录；旧任务仍保留本机，但不会用当前或空账号访问。",
                    sourceBinding = null,
                    destinations = emptyList(),
                    destinationProblem = "请先登录；不会使用旧账号的目标目录。",
                    destinationBinding = null,
                    newTaskAvailable = false,
                    newTaskProblem = "请先登录后读取当前账号的 ASR 目标目录。",
                    message = "请先登录后核对固定版本和转写任务。",
                ) }
                return@withLock
            }
            mutableState.update { it.copy(syncing = true, message = null) }
            val client = client(current)
            var destinationError: String? = null
            var destinations = state.value.destinations
            try {
                destinations = client.destinations()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                destinationError = describe(error, "读取 ASR 目标目录")
            }
            var sourceError: String? = null
            var sourceTotal = state.value.sourcesTotal
            val sources = try {
                client.sources().also { sourceTotal = it.second }.first
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                sourceError = describe(error, "读取固定版本源")
                state.value.sources
            }
            var taskError: String? = null
            var remoteTasks: List<com.pim.core.contract.NoteAsrTaskDto>? = null
            try {
                remoteTasks = client.list()
                remoteTasks.forEach { task ->
                    try { client.import(task) } catch (_: Exception) {
                        taskError = "部分服务器任务无法与本地固定身份核对，已保留本机记录。"
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                taskError = describe(error, "读取转写任务")
            }
            remoteTasks?.let { remote ->
                val ids = remote.map { it.taskId }.toSet()
                store.tasks()
                    .filter { it.binding == current.binding && it.localPhase in RECONCILABLE_PHASES && it.task == null && it.taskId !in ids }
                    .forEach { record ->
                        store.update(record.taskId) {
                            it.copy(
                                localPhase = NoteAsrIntentPhase.VerificationPending,
                                lastProblem = "服务器列表未返回该 TaskId；不会自动另建任务。",
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                        }
                    }
            }
            val loaded = store.load()
            val problem = destinationError ?: sourceError ?: taskError
            val destinationReady = destinationError == null && sourceError == null && destinations.isNotEmpty()
            mutableState.update {
                it.copy(
                    syncing = false,
                    tasks = loaded.tasks,
                    sources = sources,
                    sourcesTotal = sourceTotal,
                    sourceProblem = sourceError,
                    sourceBinding = if (sourceError == null) current.binding else null,
                    destinations = destinations,
                    destinationProblem = destinationError,
                    destinationBinding = if (destinationError == null) current.binding else null,
                    newTaskAvailable = destinationReady,
                    newTaskProblem = when {
                        destinationError != null -> destinationError
                        sourceError != null -> sourceError
                        destinations.isEmpty() -> "当前账号没有启用且凭据可用的 ASR 目标；不能创建收费任务。"
                        else -> "请选择并明确同意一个 ASR 目标。"
                    },
                    loadFailed = loaded.loadFailed,
                    message = problem,
                )
            }
        }
    }

    suspend fun refreshTask(id: String) = withContext(Dispatchers.IO) {
        initialize()
        try {
            val record = store.read(id)
            val current = targetFor(record)
            client(current).get(id)
            publish(message = "已按同一 TaskId 核对任务状态。")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            publish(message = describe(error, "核对转写任务"))
        }
    }

    suspend fun loadSegments(id: String) = withContext(Dispatchers.IO) {
        initialize()
        mutableState.update { it.copy(segmentsLoadingTaskId = id, message = null) }
        try {
            val record = store.read(id)
            val current = targetFor(record)
            val loaded = client(current).segments(id)
            publish(message = if (loaded.segmentsComplete)
                "已加载全部 ${loaded.segments.size} 个内部片段。"
            else "只加载了部分内部片段，缺口仍保留。")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            publish(message = describe(error, "读取逐段结果"))
        } finally {
            mutableState.update { it.copy(segmentsLoadingTaskId = null) }
        }
    }

    suspend fun stop(id: String) = withContext(Dispatchers.IO) {
        initialize()
        try {
            val record = store.read(id)
            val current = targetFor(record)
            client(current).stop(id)
            publish(message = "停止已保存；已成功片段保留，已发出的服务商调用不保证撤销费用。")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            publish(message = describe(error, "停止转写任务"))
        }
    }

    suspend fun retry(id: String, segmentIndexes: List<Int>, duplicateChargeConfirmed: Boolean) =
        withContext(Dispatchers.IO) {
            initialize()
            try {
                val record = store.read(id)
                val current = targetFor(record)
                client(current).retry(id, segmentIndexes, duplicateChargeConfirmed)
                publish(message = "已保存所选片段的恢复请求；原成功片段不会重跑。")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                publish(message = describe(error, "恢复失败片"))
            }
        }

    suspend fun replayStart(id: String, freshlyConfirmed: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        try {
            val record = store.read(id)
            val current = targetFor(record)
            client(current).replayStart(id, freshlyConfirmed)
            publish(message = "已使用原 TaskId 核对创建结果，没有生成第二个任务。")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            publish(message = describe(error, "按原 TaskId 核对创建"))
        }
    }

    suspend fun replayRetry(id: String, freshlyConfirmed: Boolean) = withContext(Dispatchers.IO) {
        initialize()
        try {
            val record = store.read(id)
            val current = targetFor(record)
            client(current).replayRetry(id, freshlyConfirmed)
            publish(message = "已使用原恢复标识核对，没有另建恢复意图。")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            publish(message = describe(error, "按原恢复标识核对"))
        }
    }

    /**
     * The target comes only from the generated owner-scoped destination catalogue. The current page's
     * explicit consent and the catalogue's current owner/server binding are both required.
     */
    suspend fun start(
        versionId: String,
        destination: NoteAsrDestinationDto,
        freshlyConfirmed: Boolean,
        language: String = "auto",
    ): String = withContext(Dispatchers.IO) {
        initialize()
        check(state.value.newTaskAvailable) { state.value.newTaskProblem }
        require(freshlyConfirmed) { "上传同意不能替代 ASR 发送同意" }
        sync.withLock {
            val current = target() ?: error("请先登录")
            check(state.value.destinationBinding == current.binding) { "账号或服务器已变化，请重新读取 ASR 目标目录" }
            check(state.value.sourceBinding == current.binding) { "账号或服务器已变化，请重新读取固定版本源" }
            check(state.value.destinations.any { it == destination }) { "ASR 目标已变化，请刷新并重新确认" }
            require(language in destination.languages) { "当前目标不支持所选语言" }
            val source = state.value.sources.firstOrNull { it.versionId == versionId }
                ?: error("该固定版本不在服务器 ASR 源列表中")
            val material = runCatching { materials.read(versionId) }.getOrNull()
            val record = store.createIntent(
                source = source,
                material = material,
                binding = current.binding,
                ownerName = current.name,
                destination = destination.asAsrConsent(),
                language = language,
                destinationInfo = destination,
            )
            publish()
            try {
                client(current).start(record)
                publish(message = "任务已保存；固定版本和 ASR 目标不会随当前新稿变化。")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                publish(message = describe(error, "创建转写任务"))
            }
            record.taskId
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun targetFor(record: NoteAsrTaskRecord): NoteUploadTarget {
        val current = target() ?: throw UploadSessionChangedException()
        if (current.binding != record.binding) throw UploadSessionChangedException()
        return current
    }

    private fun client(target: NoteUploadTarget) = NoteAsrClient(
        store, materials, clients.noteUploadApiService(target.binding), target.binding, target.name,
    )

    private fun publish(message: String? = null) {
        val loaded = try { store.load() } catch (_: Exception) { NoteAsrTasksState(loaded = true, loadFailed = true) }
        mutableState.update {
            it.copy(
                loaded = true,
                tasks = loaded.tasks,
                loadFailed = loaded.loadFailed,
                message = message,
            )
        }
    }

    private fun describe(error: Exception, action: String): String = when {
        error is UploadSessionChangedException || error is NoteAsrBindingChangedException ->
            "账号或服务器已变化；$action 未继续。旧任务和成功片段保留。"
        error is HttpException && error.code() == 401 ->
            "$action 需要重新登录；没有使用新账号访问旧任务。"
        error is HttpException && error.code() == 403 ->
            "$action 被拒绝：服务端普通账号 ASR 入口或当前账号权限尚未开放。"
        error is HttpException && error.code() == 404 ->
            "$action 未找到任务；保留原 TaskId，不会自动另建。"
        error is HttpException && error.code() == 409 ->
            "$action 遇到固定输入或进度冲突；本机与服务端记录均保留，请刷新核对。"
        error is HttpException && error.code() == 413 ->
            "$action 触及任务或材料上限；没有扩大范围或删除记录。"
        error is HttpException && error.code() == 429 ->
            "$action 被要求稍后重试；未自动重发。"
        error is NoteAsrTaskMissingException -> error.message ?: "$action 未确认。"
        error is NoteAsrRequestRejectedException -> error.message ?: "$action 被服务器拒绝。"
        error is IOException -> "$action 的网络或回执未确认；不会把超时或缺失当作成功。"
        else -> "$action 未确认；固定版本与已有结果保留，请刷新后核对。"
    }

    companion object {
        private val RECONCILABLE_PHASES = setOf(
            NoteAsrIntentPhase.Submitting,
            NoteAsrIntentPhase.VerificationPending,
            NoteAsrIntentPhase.Accepted,
        )
    }
}

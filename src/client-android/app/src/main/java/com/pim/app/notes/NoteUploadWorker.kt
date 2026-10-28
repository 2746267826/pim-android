package com.pim.app.notes

import android.content.Context
import androidx.work.*
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class NoteUploadWorker @AssistedInject constructor(@Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val clients: ApiClientProvider, private val execution: NoteUploadExecution) : CoroutineWorker(context, parameters) {
    @AssistedFactory interface Factory { fun create(context: Context, parameters: WorkerParameters): NoteUploadWorker }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(JOB_ID)?.takeIf(::validNoteId) ?: return@withContext Result.failure()
        val store = NoteUploadStore.at(applicationContext.noBackupFilesDir)
        val job = try { store.read(id) } catch (_: Exception) { return@withContext Result.failure() }
        if (job.phase !in setOf(NoteUploadPhase.Queued, NoteUploadPhase.Uploading, NoteUploadPhase.WaitingForNetwork)) return@withContext Result.success()
        if (job.retryNotBeforeMillis > System.currentTimeMillis()) return@withContext Result.retry()
        val coroutine = currentCoroutineContext()
        fun failed(phase: NoteUploadPhase, message: String, retry: Boolean, notBefore: Long = 0): Result {
            coroutine.ensureActive()
            var canRetry = false
            try {
                store.update(id) {
                    val attempts = if (retry) (it.consecutiveFailures + 1).coerceAtMost(9) else it.consecutiveFailures
                    canRetry = retry && attempts <= 8
                    if (it.phase == NoteUploadPhase.Paused) it else it.copy(
                        phase = if (retry && !canRetry) NoteUploadPhase.Failed else phase, problem = message,
                        retryNotBeforeMillis = notBefore, consecutiveFailures = attempts)
                }
            } catch (_: Exception) { return Result.retry() }
            return if (canRetry) Result.retry() else Result.failure()
        }

        try {
            val started = android.os.SystemClock.elapsedRealtime()
            val progress = execution.run(id) { NoteUploadEngine(store, NoteMaterialStore.at(applicationContext.noBackupFilesDir),
                clients.noteUploadApiService(BoundUploadSession(job.serverIdentity, job.ownerId)),
                shouldYield = { android.os.SystemClock.elapsedRealtime() - started >= 120_000 }) { coroutine.ensureActive() }.run(id)
            }
            coroutine.ensureActive()
            if (progress.phase == NoteUploadPhase.Uploading)
                WorkManager.getInstance(applicationContext).enqueueUniqueWork("note-upload-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, noteUploadRequest(id, job.serverIdentity)).result.get()
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: UploadPausedException) { Result.success() }
        catch (_: UploadSessionChangedException) { failed(NoteUploadPhase.NeedsLogin, "账号或服务器已变化，请登录原账号后明确继续", false) }
        catch (error: HttpException) {
            when (error.code()) {
                401, 403 -> failed(NoteUploadPhase.NeedsLogin, "登录已失效或账号无权上传，请重新核对原账号", false)
                413 -> failed(NoteUploadPhase.Failed, "服务器配额或材料大小达到限制，原材料保留，请处理后重试", false)
                409 -> failed(NoteUploadPhase.Failed, "服务器保留了同标识的不同输入，请核对版本，未覆盖任何材料", false)
                429 -> failed(NoteUploadPhase.WaitingForNetwork, "服务器要求等待，稍后核对同一版本继续", true,
                    uploadRetryNotBefore(error.response()?.headers()?.get("Retry-After"), System.currentTimeMillis()))
                408, in 500..599 -> failed(NoteUploadPhase.WaitingForNetwork, "服务器暂时未确认保存，将核对同一版本后继续", true)
                else -> failed(NoteUploadPhase.Failed, "服务器未接受此材料，请核对格式和服务版本后重试", false)
            }
        } catch (_: IOException) { failed(NoteUploadPhase.WaitingForNetwork, "网络或结果未确认，将核对同一版本后继续；不将超时算作成功", true) }
        catch (_: Exception) { failed(NoteUploadPhase.Failed, "固定材料或服务器回执不一致，未改用当前新稿，请检查后重试", false) }


    }

    companion object { const val JOB_ID = "note_upload_job_id"; const val TAG = "native-note-upload" }
}

internal fun noteUploadRequest(id: String, serverIdentity: String): OneTimeWorkRequest = OneTimeWorkRequestBuilder<NoteUploadWorker>()
    .setInputData(workDataOf(NoteUploadWorker.JOB_ID to id))
    .setConstraints(Constraints.Builder().setRequiredNetworkType(noteUploadNetworkType(serverIdentity)).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
    .addTag(NoteUploadWorker.TAG).build()

internal fun noteUploadNetworkType(serverIdentity: String): NetworkType {
    val host = com.pim.core.settings.PimServerEndpoints.apiBaseUrlForTrustedOrigin(serverIdentity).host
    // A server running on this device (or an adb reverse endpoint) is reachable without a public network.
    return if (host == "127.0.0.1" || host == "::1") NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
}

@javax.inject.Singleton
class NoteUploadExecution @javax.inject.Inject constructor() {
    private val locks = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()
    suspend fun <T> run(id: String, action: suspend () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() } }
        lock.lock()
        try { return action() } finally { lock.unlock() }
    }
}

internal fun uploadRetryNotBefore(value: String?, now: Long): Long {
    val seconds = value?.toLongOrNull()?.takeIf { it >= 0 }
    if (seconds != null) return if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
    return runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrDefault(now)
}

package com.pim.app.notes

import android.content.Context
import androidx.work.*
import com.pim.app.recording.RecordingSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class NoteMaterialRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val store = NoteMaterialStore.at(context.noBackupFilesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = Mutex()
    private val mutableState = MutableStateFlow(NoteMaterialsState())
    val state = mutableState.asStateFlow()
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (state.value.loaded) return@withLock
            try {
                refresh()
                state.value.entries.filter { it.phase == NoteMaterialPhase.Preparing }.forEach { enqueue(it.id) }
                scope.launch {
                    workManager.getWorkInfosByTagFlow(NoteMaterialWorker.TAG).collect { refreshSafely() }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(loaded = false, loadFailed = true) }
        }
    }

    suspend fun freeze(note: NoteDocument, sessions: List<RecordingSession>): String = withContext(Dispatchers.IO) {
        val input = store.create(note, sessions)
        refresh()
        if (state.value.entries.single { it.id == input.id }.phase == NoteMaterialPhase.Preparing) enqueue(input.id)
        input.id
    }

    fun retry(id: String) {
        scope.launch {
            try {
                store.retry(id); refresh()
                enqueue(id, ExistingWorkPolicy.APPEND_OR_REPLACE)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { refreshSafely() }
        }
    }

    suspend fun export(id: String, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        try { open().use { store.export(id, it) { job.ensureActive() } } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // A destination failure does not make the pinned source corrupt.
            refreshSafely()
            throw error
        }
    }

    private fun enqueue(id: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<NoteMaterialWorker>()
            .setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to id)).addTag(NoteMaterialWorker.TAG).build()
        workManager.enqueueUniqueWork("note-material-$id", policy, request).result.get()
    }

    private fun refresh() { mutableState.value = NoteMaterialsState(true, store.entries()) }
    private fun refreshSafely() {
        try { refresh() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutableState.value = state.value.copy(loadFailed = true) }
    }
}

class NoteMaterialWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(VERSION_ID)?.takeIf(::validNoteId) ?: return@withContext Result.failure()
        val store = NoteMaterialStore.at(applicationContext.noBackupFilesDir)
        val job = currentCoroutineContext()
        try {
            store.prepare(id) { job.ensureActive() }
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            try { store.fail(id, if (error is IOException) NoteMaterialProblem.Storage else NoteMaterialProblem.Incomplete) }
            catch (_: Exception) { return@withContext Result.retry() }
            Result.failure()
        }
    }

    companion object {
        const val VERSION_ID = "local_material_version_id"
        const val TAG = "note-material-preparation"
    }
}

package com.pim.app.notes

import android.content.Context
import androidx.work.*
import com.pim.core.auth.TokenManager
import com.pim.core.network.BoundUploadSession
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import com.pim.core.settings.ServerSessionCommitResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NoteUploadTarget(val binding: BoundUploadSession, val name: String)

@Singleton
class NoteUploadRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val sessions: TokenManager, private val settings: ServerSettingsStore) {
    private val store = NoteUploadStore.at(context.noBackupFilesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = Mutex()
    private val mutableState = MutableStateFlow(NoteUploadsState())
    val state = mutableState.asStateFlow()
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (state.value.loaded) return@withLock
            refresh()
            state.value.jobs.filter { it.phase in resumable }.forEach { enqueue(it.id) }
            scope.launch { workManager.getWorkInfosByTagFlow(NoteUploadWorker.TAG).collect { refresh() } }
        }
    }

    fun target(): NoteUploadTarget? = runCatching {
        val origin = PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin
        var target: NoteUploadTarget? = null
        settings.commitSessionIfCurrentServer(origin) {
            val session = sessions.snapshot()
            val ownerId = session.ownerId
            if (session.tokens == null || session.serverIdentity != origin || ownerId == null) false
            else { target = NoteUploadTarget(BoundUploadSession(origin, ownerId), session.ownerName.orEmpty()); true }
        }
        target
    }.getOrNull()

    suspend fun submit(versionId: String, confirmed: NoteUploadTarget): String = withContext(Dispatchers.IO) {
        val snapshot = NoteMaterialStore.at(context.noBackupFilesDir).uploadSnapshot(versionId)
        var job: NoteUploadJob? = null
        val committed = settings.commitSessionIfCurrentServer(confirmed.binding.serverIdentity) {
            val current = sessions.snapshot()
            if (current.tokens == null || current.ownerId != confirmed.binding.ownerId || current.serverIdentity != confirmed.binding.serverIdentity) false
            else { job = store.create(snapshot, confirmed.binding.serverIdentity, confirmed.binding.ownerId, confirmed.name); true }
        }
        check(committed == ServerSessionCommitResult.Committed) { "Upload account or server changed" }
        val pinned = checkNotNull(job)
        refresh()
        if (pinned.phase in resumable) enqueue(pinned.id)
        pinned.id
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        val old = store.read(id)
        check(target()?.binding == BoundUploadSession(old.serverIdentity, old.ownerId)) { "Please sign in to the original account and server" }
        if (old.phase !in setOf(NoteUploadPhase.Uploaded, NoteUploadPhase.Conflict)) {
            store.update(id) { it.copy(phase = NoteUploadPhase.Queued, problem = null) }
            refresh(); enqueue(id, ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        store.update(id) {
            if (it.phase in setOf(NoteUploadPhase.Uploaded, NoteUploadPhase.Conflict)) it
            else it.copy(phase = NoteUploadPhase.Paused, problem = "后续上传已暂停；已发出的分块会核对结果")
        }
        refresh()
    }

    private fun enqueue(id: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        workManager.enqueueUniqueWork("note-upload-$id", policy, noteUploadRequest(id, store.read(id).serverIdentity)).result.get()
    }

    private fun refresh() {
        try { mutableState.value = store.load() }
        catch (_: Exception) { mutableState.value = state.value.copy(loadFailed = true) }
    }

    companion object { private val resumable = setOf(NoteUploadPhase.Queued, NoteUploadPhase.Uploading, NoteUploadPhase.WaitingForNetwork) }
}

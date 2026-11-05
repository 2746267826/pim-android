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
data class NoteAutoSubmitRegistration(val authorized: Boolean, val scheduled: Boolean)

@Singleton
class NoteUploadRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val sessions: TokenManager, private val settings: ServerSettingsStore) {
    private val store = NoteUploadStore.at(context.noBackupFilesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = Mutex()
    private val autoSubmitLock = Mutex()
    private val mutableState = MutableStateFlow(NoteUploadsState())
    val state = mutableState.asStateFlow()
    private val autoSubmitStore = NoteAutoSubmitStore(context)
    private val mutableAutoSubmitAuthorization = MutableStateFlow(autoSubmitStore.authorization())
    val autoSubmitAuthorization = mutableAutoSubmitAuthorization.asStateFlow()
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun authorizeAutoSubmit(target: NoteUploadTarget): Boolean = withContext(Dispatchers.IO) {
        autoSubmitLock.withLock {
            val oldIds = autoSubmitStore.pendingVersionIds()
            val previous = autoSubmitStore.authorization()
            val authorization = NoteAutoSubmitAuthorization(target.binding.serverIdentity, target.binding.ownerId)
            val committed = settings.commitSessionIfCurrentServer(target.binding.serverIdentity) {
                val current = sessions.snapshot()
                current.tokens != null && current.ownerId == target.binding.ownerId && current.serverIdentity == target.binding.serverIdentity &&
                    autoSubmitStore.authorize(target)
            }
            if (committed != ServerSessionCommitResult.Committed) return@withLock false
            mutableAutoSubmitAuthorization.value = authorization
            if (previous == authorization) oldIds.forEach { id -> scheduleSafely(id) }
            else cancelAutoSubmitWorks(oldIds)
            true
        }
    }

    suspend fun authorizeAutoSubmitForVersion(versionId: String, target: NoteUploadTarget): NoteAutoSubmitRegistration =
        withContext(Dispatchers.IO) {
            autoSubmitLock.withLock {
                val oldIds = autoSubmitStore.pendingVersionIds()
                val previous = autoSubmitStore.authorization()
                val authorization = NoteAutoSubmitAuthorization(target.binding.serverIdentity, target.binding.ownerId)
                val committed = settings.commitSessionIfCurrentServer(target.binding.serverIdentity) {
                    val current = sessions.snapshot()
                    current.tokens != null && current.ownerId == target.binding.ownerId && current.serverIdentity == target.binding.serverIdentity &&
                        autoSubmitStore.authorizeForVersion(target, versionId)
                }
                if (committed != ServerSessionCommitResult.Committed) return@withLock NoteAutoSubmitRegistration(false, false)
                mutableAutoSubmitAuthorization.value = authorization
                if (previous != authorization) cancelAutoSubmitWorks(oldIds - versionId)
                val ids = if (previous == authorization) oldIds + versionId else setOf(versionId)
                val scheduled = ids.map(::scheduleSafely).all { it }
                NoteAutoSubmitRegistration(true, scheduled)
            }
        }

    suspend fun submitIfAuthorized(versionId: String): NoteAutoSubmitRegistration = withContext(Dispatchers.IO) {
        autoSubmitLock.withLock {
            val current = target() ?: return@withLock NoteAutoSubmitRegistration(false, false)
            val authorization = NoteAutoSubmitAuthorization(current.binding.serverIdentity, current.binding.ownerId)
            if (autoSubmitStore.authorization() != authorization) return@withLock NoteAutoSubmitRegistration(false, false)
            val committed = settings.commitSessionIfCurrentServer(current.binding.serverIdentity) {
                val snapshot = sessions.snapshot()
                snapshot.tokens != null && snapshot.ownerId == current.binding.ownerId && snapshot.serverIdentity == current.binding.serverIdentity &&
                    autoSubmitStore.addIntentIfAuthorized(current, versionId)
            }
            if (committed != ServerSessionCommitResult.Committed) return@withLock NoteAutoSubmitRegistration(false, false)
            NoteAutoSubmitRegistration(true, scheduleSafely(versionId))
        }
    }

    suspend fun revokeAutoSubmitDurably(): Boolean = withContext(Dispatchers.IO) {
        autoSubmitLock.withLock {
            val (committed, ids) = autoSubmitStore.revoke()
            if (!committed) return@withLock false
            mutableAutoSubmitAuthorization.value = null
            cancelAutoSubmitWorks(ids)
            true
        }
    }

    suspend fun resumeAutoSubmits() = withContext(Dispatchers.IO) {
        autoSubmitLock.withLock {
            val authorization = autoSubmitStore.authorization() ?: return@withLock
            val binding = target()?.binding ?: return@withLock
            if (authorization != NoteAutoSubmitAuthorization(binding.serverIdentity, binding.ownerId)) return@withLock
            autoSubmitStore.pendingVersionIds().forEach { id ->
                val intent = autoSubmitStore.intent(id) ?: return@forEach
                if (intent.binding == binding) scheduleSafely(id)
            }
        }
    }

    internal suspend fun processAutoSubmit(versionId: String): AutoSubmitWorkerOutcome = withContext(Dispatchers.IO) {
        autoSubmitLock.withLock {
            val intent = autoSubmitStore.intent(versionId) ?: return@withLock AutoSubmitWorkerOutcome.Revoked
            if (autoSubmitStore.authorization() != NoteAutoSubmitAuthorization(intent.serverIdentity, intent.ownerId)) {
                autoSubmitStore.removeIntent(versionId)
                return@withLock AutoSubmitWorkerOutcome.Revoked
            }
            val entry = NoteMaterialStore.at(context.noBackupFilesDir).entries().firstOrNull { it.id == versionId }
                ?: return@withLock AutoSubmitWorkerOutcome.Retry
            when (entry.phase) {
                NoteMaterialPhase.Preparing -> return@withLock AutoSubmitWorkerOutcome.Retry
                NoteMaterialPhase.Failed -> return@withLock AutoSubmitWorkerOutcome.Deferred
                NoteMaterialPhase.Ready -> Unit
            }
            if (target()?.binding != intent.binding) return@withLock AutoSubmitWorkerOutcome.Deferred
            try {
                submit(versionId, NoteUploadTarget(intent.binding, intent.ownerName))
                check(autoSubmitStore.removeIntent(versionId))
                AutoSubmitWorkerOutcome.Submitted
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { if (target()?.binding != intent.binding) AutoSubmitWorkerOutcome.Deferred else throw error }
        }
    }

    private fun scheduleAutoSubmitLocked(versionId: String): Boolean {
        val intent = autoSubmitStore.intent(versionId) ?: return false
        val authorization = autoSubmitStore.authorization() ?: return false
        if (authorization != NoteAutoSubmitAuthorization(intent.serverIdentity, intent.ownerId)) return false
        val entry = NoteMaterialStore.at(context.noBackupFilesDir).entries().firstOrNull { it.id == versionId } ?: return false
        return enqueueNoteAutoSubmit(workManager, versionId, entry.phase)
    }

    private fun cancelAutoSubmitWorks(ids: Set<String>) {
        ids.forEach { id -> runCatching { cancelNoteAutoSubmit(workManager, id) } }
    }

    private fun scheduleSafely(versionId: String): Boolean = try { scheduleAutoSubmitLocked(versionId) }
    catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (_: Exception) { false }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (state.value.loaded) return@withLock
            refresh()
            state.value.jobs.filter { it.phase in resumable }.forEach { enqueue(it.id) }
            resumeAutoSubmits()
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

package com.pim.app.notes

import com.pim.core.network.BoundUploadSession
import java.io.File
import kotlinx.serialization.encodeToString

/**
 * Durable, owner/server-bound derived-task intents. The TaskId is generated
 * locally before the first send so an unknown receipt can always be queried
 * with the same identifier instead of creating a second task.
 */
class NoteArtifactTaskStore(private val root: File) {
    fun load(): NoteArtifactTasksState = synchronized(lock) {
        if (!root.exists()) return@synchronized NoteArtifactTasksState(loaded = true)
        var damaged = false
        val tasks = checkNotNull(root.listFiles())
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map { it.name.removeSuffix(".bak").removeSuffix(".json") }
            .distinct()
            .mapNotNull { id ->
                try {
                    read(id)
                } catch (_: Exception) {
                    damaged = true
                    null
                }
            }
            .sortedByDescending { it.createdAtMillis }
        NoteArtifactTasksState(loaded = true, tasks = tasks, loadFailed = damaged)
    }

    fun tasks(): List<NoteArtifactTaskRecord> = load().tasks

    fun createIntent(
        binding: BoundUploadSession,
        noteId: String,
        materialVersionId: String,
        sourceTitle: String = "",
        materialCreatedAtMillis: Long? = null,
        asrTaskId: String? = null,
        kinds: Set<NoteArtifactKind>,
        inputSha256: String,
        manifestSha256: String,
        consent: NoteArtifactConsent,
        destinationDisplayName: String = "",
        destinationModel: String = "",
        destinationCapabilities: List<String> = emptyList(),
        destinationBackgroundAvailable: Boolean? = null,
        ownerName: String = "",
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactTaskRecord = synchronized(lock) {
        require(validArtifactTaskId(noteId) && validArtifactTaskId(materialVersionId))
        require(asrTaskId == null || validNoteId(asrTaskId))
        val requestedKinds = kinds.toSet()
        require(requestedKinds.isNotEmpty()) { "派生任务至少需要一种结果" }
        val primaryKind = NoteArtifactKind.entries.first { it in requestedKinds }
        require(sourceTitle.length <= 500)
        require(materialCreatedAtMillis == null || materialCreatedAtMillis >= 0)
        validateDigests(inputSha256, manifestSha256)
        validateConsent(consent)
        requireNonNegative(nowMillis)
        val existing = tasks()
        val sameIntent = existing.firstOrNull {
            it.binding == binding && it.noteId == noteId && it.materialVersionId == materialVersionId &&
                it.sourceTitle == sourceTitle && it.materialCreatedAtMillis == materialCreatedAtMillis &&
                it.asrTaskId == asrTaskId && it.kinds == requestedKinds &&
                it.inputSha256 == inputSha256.lowercase() &&
                it.manifestSha256 == manifestSha256.lowercase() && it.consent == consent &&
                it.localPhase in PENDING_INTENT_PHASES
        }
        if (sameIntent != null) return@synchronized sameIntent
        require(existing.size < MAX_TASKS) { "本机保留的派生任务已达 $MAX_TASKS 个" }
        val record = NoteArtifactTaskRecord(
            taskId = newNoteId(),
            noteId = noteId,
            materialVersionId = materialVersionId,
            sourceTitle = sourceTitle.take(500),
            materialCreatedAtMillis = materialCreatedAtMillis,
            asrTaskId = asrTaskId,
            kind = primaryKind,
            kinds = requestedKinds,
            inputSha256 = inputSha256.lowercase(),
            manifestSha256 = manifestSha256.lowercase(),
            serverIdentity = binding.serverIdentity,
            ownerId = binding.ownerId,
            ownerName = ownerName.take(200),
            expectedUserId = binding.ownerId,
            consent = consent,
            destinationDisplayName = destinationDisplayName.take(200),
            destinationModel = destinationModel.take(200),
            destinationCapabilities = destinationCapabilities.distinct(),
            destinationBackgroundAvailable = destinationBackgroundAvailable,
            localPhase = NoteArtifactIntentPhase.Prepared,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
        write(record)
        record
    }

    fun read(id: String): NoteArtifactTaskRecord = synchronized(lock) {
        require(validNoteId(id))
        val record = NoteJson.decodeFromString<NoteArtifactTaskRecord>(readMaterialFile(path(id)))
        validate(record)
        require(record.taskId == id)
        record
    }

    fun update(
        id: String,
        nowMillis: Long = System.currentTimeMillis(),
        change: (NoteArtifactTaskRecord) -> NoteArtifactTaskRecord,
    ): NoteArtifactTaskRecord = synchronized(lock) {
        requireNonNegative(nowMillis)
        val before = read(id)
        val changed = change(before)
        requireSameIdentity(before, changed)
        val after = changed.copy(updatedAtMillis = maxOf(changed.updatedAtMillis, nowMillis))
        validate(after)
        write(after)
        after
    }

    /**
     * Records a confirmed server view. A successful retry clears the pending
     * intent when the server revision advanced; a still-unknown stop receipt
     * stays visible until the server reports that stopping was accepted.
     */
    fun recordRemote(
        id: String,
        view: NoteArtifactRemoteTaskView,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteArtifactTaskRecord = update(id, nowMillis) { record ->
        validateRemote(view)
        require(view.inputSha256 == null || view.inputSha256.lowercase() == record.inputSha256)
        require(view.manifestSha256 == null || view.manifestSha256.lowercase() == record.manifestSha256)
        record.copy(
            localPhase = NoteArtifactIntentPhase.Accepted,
            remote = view,
            pendingRetry = record.pendingRetry?.takeIf {
                it.expectedServerRevision == view.revision
            },
            stopPhase = if (view.stopRequested) null else record.stopPhase,
            lastProblem = null,
        )
    }

    private fun write(record: NoteArtifactTaskRecord) {
        validate(record)
        val json = NoteJson.encodeToString(record)
        writeVerifiedMaterialJson(path(record.taskId), json)
    }

    private fun validate(record: NoteArtifactTaskRecord) {
        require(validNoteId(record.taskId) && validArtifactTaskId(record.noteId) &&
            validArtifactTaskId(record.materialVersionId))
        require(record.asrTaskId == null || validNoteId(record.asrTaskId))
        require(record.sourceTitle.length <= 500)
        require(record.materialCreatedAtMillis == null || record.materialCreatedAtMillis >= 0)
        require(record.kinds.isNotEmpty() && record.kind in record.kinds)
        require(record.expectedUserId == record.ownerId)
        record.binding
        require(record.ownerName.length <= 200)
        require(record.destinationDisplayName.length <= 200 && record.destinationModel.length <= 200)
        require(record.destinationCapabilities.size <= 32 &&
            record.destinationCapabilities.distinct().size == record.destinationCapabilities.size &&
            record.destinationCapabilities.all { it.isNotBlank() && it.length <= 200 })
        validateDigests(record.inputSha256, record.manifestSha256)
        validateConsent(record.consent)
        record.remote?.let(::validateRemote)
        record.pendingRetry?.let { retry ->
            require(validNoteId(retry.retryId))
            requireOpaqueId(retry.expectedServerRevision, "恢复基线")
            require(retry.stepKeys.isNotEmpty() && retry.stepKeys.size <= 500 &&
                retry.stepKeys.distinct().size == retry.stepKeys.size &&
                retry.stepKeys.all { it.isNotBlank() && it.length <= 200 })
            require(retry.createdAtMillis >= 0)
        }
        require(record.stopPhase == null || record.stopPhase in STOP_PHASES)
        require(record.lastProblem == null || record.lastProblem.length <= 2_000)
        require(record.createdAtMillis >= 0 && record.updatedAtMillis >= record.createdAtMillis)
    }

    private fun validateRemote(view: NoteArtifactRemoteTaskView) {
        requireOpaqueId(view.revision, "服务端修订")
        require(view.phase.isNotBlank() && view.phase.length <= 120)
        require(view.inputSha256 == null || validMaterialDigest(view.inputSha256.lowercase()))
        require(view.manifestSha256 == null || validMaterialDigest(view.manifestSha256.lowercase()))
        requireOpaqueIdAllowNull(view.completeRecordArtifactId, "完整记录标识")
        requireOpaqueIdAllowNull(view.analysisArtifactId, "独立分析标识")
        require(view.observedAtMillis >= 0)
    }

    private fun requireSameIdentity(before: NoteArtifactTaskRecord, after: NoteArtifactTaskRecord) {
        require(after.taskId == before.taskId && after.noteId == before.noteId &&
            after.materialVersionId == before.materialVersionId && after.asrTaskId == before.asrTaskId &&
            after.sourceTitle == before.sourceTitle &&
            after.materialCreatedAtMillis == before.materialCreatedAtMillis &&
            after.kind == before.kind && after.kinds == before.kinds &&
            after.inputSha256 == before.inputSha256 && after.manifestSha256 == before.manifestSha256 &&
            after.serverIdentity == before.serverIdentity && after.ownerId == before.ownerId &&
            after.expectedUserId == before.expectedUserId && after.consent == before.consent &&
            after.createdAtMillis == before.createdAtMillis)
    }

    private fun validateConsent(consent: NoteArtifactConsent) {
        require(consent.confirmed)
        require(validNoteId(consent.modelId) && validNoteId(consent.modelRevision))
        require(consent.modelId.length <= 200 && consent.modelRevision.length <= 200)
        val endpoint = runCatching { java.net.URI(consent.endpoint) }.getOrNull()
        require(endpoint != null && endpoint.scheme in setOf("https", "http") &&
            !endpoint.host.isNullOrBlank() && endpoint.userInfo == null &&
            consent.endpoint.length <= 2_000)
    }

    private fun validateDigests(inputSha256: String, manifestSha256: String) {
        require(validMaterialDigest(inputSha256.lowercase()) &&
            validMaterialDigest(manifestSha256.lowercase()))
    }

    private fun requireOpaqueId(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 512) { "$label 标识无效" }
    }

    private fun requireOpaqueIdAllowNull(value: String?, label: String) {
        if (value != null) requireOpaqueId(value, label)
    }

    private fun validArtifactTaskId(value: String) =
        validNoteId(value) && value != "00000000-0000-0000-0000-000000000000"

    private fun requireNonNegative(value: Long): Long {
        require(value >= 0)
        return value
    }

    private fun path(id: String): File {
        require(validNoteId(id))
        return File(root, "$id.json")
    }

    companion object {
        const val MAX_TASKS = 200
        private val lock = Any()
        private val PENDING_INTENT_PHASES = setOf(
            NoteArtifactIntentPhase.Prepared,
            NoteArtifactIntentPhase.Submitting,
            NoteArtifactIntentPhase.VerificationPending,
        )
        private val STOP_PHASES = setOf(
            NoteArtifactIntentPhase.Submitting,
            NoteArtifactIntentPhase.VerificationPending,
        )

        fun at(noBackupDirectory: File) = NoteArtifactTaskStore(File(noBackupDirectory, "note-artifact-tasks"))
    }
}

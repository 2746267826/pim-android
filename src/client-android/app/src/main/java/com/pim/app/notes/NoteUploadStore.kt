package com.pim.app.notes

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

data class NoteUploadSnapshot(val versionId: String, val noteId: String, val revision: Long,
    val inputJson: String, val manifestJson: String, val files: List<NoteMaterialFile>) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

@Serializable
enum class NoteUploadPhase { Queued, Uploading, WaitingForNetwork, Paused, NeedsLogin, Failed, Uploaded, Conflict }

/** Private queue metadata. Tokens never enter this record, WorkData, or material archives. */
@Serializable
data class NoteUploadJob(
    val id: String, val versionId: String, val noteId: String, val revision: Long,
    val serverIdentity: String, val ownerId: String, val ownerName: String,
    val inputSha256: String, val manifestSha256: String, val baseVersionId: String?,
    val createdAtMillis: Long, val phase: NoteUploadPhase = NoteUploadPhase.Queued,
    val uploadedBytes: Long = 0, val totalBytes: Long,
    val currentVersionId: String? = null, val problem: String? = null,
    val storageConsent: Boolean = true, val retryNotBeforeMillis: Long = 0, val consecutiveFailures: Int = 0
) {
    override fun toString() = "NoteUploadJob(id=$id, phase=$phase, bytes=$uploadedBytes/$totalBytes)"
}

data class NoteUploadsState(val loaded: Boolean = false, val jobs: List<NoteUploadJob> = emptyList(), val loadFailed: Boolean = false)

class NoteUploadStore(private val root: File) {
    fun load(): NoteUploadsState = synchronized(lock) {
        if (!root.exists()) return@synchronized NoteUploadsState(loaded = true)
        var damaged = false
        val jobs = checkNotNull(root.listFiles()).filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct().mapNotNull { id ->
                try { read(id) } catch (_: Exception) { damaged = true; null }
            }.sortedByDescending { it.createdAtMillis }
        NoteUploadsState(true, jobs, damaged)
    }

    fun jobs(): List<NoteUploadJob> = load().jobs

    fun create(snapshot: NoteUploadSnapshot, server: String, owner: String, name: String): NoteUploadJob = synchronized(lock) {
        com.pim.core.network.BoundUploadSession(server, owner)
        val existing = jobs()
        val inputHash = materialDigest(snapshot.inputJson); val manifestHash = materialDigest(snapshot.manifestJson)
        existing.firstOrNull { it.versionId == snapshot.versionId && it.serverIdentity == server && it.ownerId == owner }?.let {
            require(it.inputSha256 == inputHash && it.manifestSha256 == manifestHash) { "Upload intent already has different material" }
            return@synchronized it
        }
        require(existing.size < 256) { "Too many retained upload jobs" }
        val previous = existing.firstOrNull { it.noteId == snapshot.noteId && it.serverIdentity == server && it.ownerId == owner && it.phase == NoteUploadPhase.Uploaded }
        val job = NoteUploadJob(newNoteId(), snapshot.versionId, snapshot.noteId, snapshot.revision, server, owner, name.take(200),
            inputHash, manifestHash, previous?.versionId, System.currentTimeMillis(), totalBytes = snapshot.sizeBytes)
        write(job); job
    }

    fun read(id: String): NoteUploadJob = synchronized(lock) {
        val job = NoteJson.decodeFromString<NoteUploadJob>(readMaterialFile(path(id)))
        require(job.id == id && validNoteId(job.versionId) && validNoteId(job.noteId) && job.storageConsent)
        com.pim.core.network.BoundUploadSession(job.serverIdentity, job.ownerId)
        require(validMaterialDigest(job.inputSha256) && validMaterialDigest(job.manifestSha256))
        require(job.totalBytes >= 0 && job.uploadedBytes in 0..job.totalBytes)
        require(job.retryNotBeforeMillis >= 0 && job.consecutiveFailures in 0..9)
        if (job.phase in setOf(NoteUploadPhase.Uploaded, NoteUploadPhase.Conflict)) require(job.uploadedBytes == job.totalBytes)
        job
    }

    fun update(id: String, change: (NoteUploadJob) -> NoteUploadJob): NoteUploadJob = synchronized(lock) {
        val before = read(id); val after = change(before)
        require(after.id == before.id && after.versionId == before.versionId && after.ownerId == before.ownerId &&
            after.serverIdentity == before.serverIdentity && after.inputSha256 == before.inputSha256 &&
            after.manifestSha256 == before.manifestSha256 && after.baseVersionId == before.baseVersionId &&
            after.noteId == before.noteId && after.revision == before.revision && after.totalBytes == before.totalBytes &&
            after.ownerName == before.ownerName && after.storageConsent && after.createdAtMillis == before.createdAtMillis &&
            after.uploadedBytes in 0..after.totalBytes)
        write(after); after
    }

    private fun write(job: NoteUploadJob) {
        val json = NoteJson.encodeToString(job)
        writeMaterialFile(path(job.id)) { it.write(json.toByteArray(Charsets.UTF_8)) }
        check(readMaterialFile(path(job.id)) == json) { "Upload checkpoint was not confirmed on disk" }
    }
    private fun path(id: String): File { require(validNoteId(id)); return File(root, "$id.json") }
    companion object {
        private val lock = Any()
        fun at(noBackupDirectory: File) = NoteUploadStore(File(noBackupDirectory, "note-uploads"))
    }
}

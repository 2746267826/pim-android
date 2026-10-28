package com.pim.app.notes

import com.pim.core.contract.*
import com.pim.core.network.ApiService
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** One immutable upload intent; every transport call is additionally checked by bound authentication. */
class NoteUploadEngine(private val jobs: NoteUploadStore, private val materials: NoteMaterialStore,
    private val api: ApiService, private val maxParts: Int = 32, private val shouldYield: () -> Boolean = { false },
    private val checkActive: () -> Unit = {}) {
    suspend fun run(id: String): NoteUploadJob {
        val job = jobs.read(id)
        if (job.phase in setOf(NoteUploadPhase.Paused, NoteUploadPhase.Uploaded, NoteUploadPhase.Conflict)) return job
        fun check() {
            checkActive()
            if (jobs.read(id).phase == NoteUploadPhase.Paused) throw UploadPausedException()
        }
        fun save(phase: NoteUploadPhase, bytes: Long, current: String? = null): NoteUploadJob = jobs.update(id) {
            it.copy(phase = if (it.phase == NoteUploadPhase.Paused && phase == NoteUploadPhase.Uploading) it.phase else phase,
                uploadedBytes = bytes, currentVersionId = current, problem = null,
                consecutiveFailures = if (bytes > it.uploadedBytes || phase in setOf(NoteUploadPhase.Uploaded, NoteUploadPhase.Conflict)) 0 else it.consecutiveFailures)
        }
        check()
        val snapshot = materials.uploadSnapshot(job.versionId)
        require(maxParts > 0 && snapshot.noteId == job.noteId && snapshot.revision == job.revision && snapshot.sizeBytes == job.totalBytes &&
            materialDigest(snapshot.inputJson) == job.inputSha256 &&
            materialDigest(snapshot.manifestJson) == job.manifestSha256) { "Fixed source changed" }
        val begun = api.beginNoteMaterialUpload(BeginNoteMaterialUploadRequest(job.baseVersionId, snapshot.inputJson,
            snapshot.manifestJson, job.noteId, true, job.versionId)).let { requireData(it.code, it.data) }
        validate(begun, job, snapshot)
        require(begun.phase in setOf("Uploading", "Uploaded", "Conflict")) { "Unknown server phase" }
        if (begun.phase != "Uploading") return save(if (begun.hasConflict) NoteUploadPhase.Conflict else NoteUploadPhase.Uploaded,
            job.totalBytes, begun.currentVersionId)
        val size = begun.limits.partSizeBytes
        require(size in 1..1_048_576) { "Unsupported server part limit" }
        var uploaded = begun.files.sumOf { file -> file.uploadedParts.sumOf { part -> minOf(size.toLong(), file.sizeBytes - part.toLong() * size) } }
        save(NoteUploadPhase.Uploading, uploaded)
        var copiedParts = 0
        for (remote in begun.files) {
            for (part in 0 until remote.partCount) {
                check(); if (part in remote.uploadedParts) continue
                val bytes = materials.uploadPart(job.versionId, remote.index, part, size, ::check)
                check()
                val response = api.uploadNoteMaterialPart(job.versionId, remote.index, part,
                    bytes.inputStream().use { materialDigest(it) }, bytes.toRequestBody("application/octet-stream".toMediaType()))
                if (response.code != 0) throw IOException("Server did not confirm the stored part")
                uploaded += bytes.size; save(NoteUploadPhase.Uploading, uploaded)
                copiedParts++
                if (copiedParts >= maxParts || shouldYield()) return jobs.read(id)
            }
        }
        check()
        val completed = api.completeNoteMaterialUpload(job.versionId).let { requireData(it.code, it.data) }
        validate(completed, job, snapshot)
        require(completed.phase in setOf("Uploaded", "Conflict")) { "Server has not confirmed a complete version" }
        return save(if (completed.hasConflict) NoteUploadPhase.Conflict else NoteUploadPhase.Uploaded, job.totalBytes, completed.currentVersionId)
    }

    private fun validate(remote: NoteMaterialUploadDto, job: NoteUploadJob, source: NoteUploadSnapshot) {
        require(remote.versionId == job.versionId && remote.noteId == job.noteId && remote.baseVersionId == job.baseVersionId)
        val size = remote.limits.partSizeBytes
        require(size in 1..1_048_576 && remote.files.size == source.files.size)
        remote.files.forEachIndexed { index, file ->
            val pinned = source.files[index]
            require(file.index == index && file.path == pinned.path && file.kind == pinned.kind.name &&
                file.sha256 == pinned.sha256 && file.sizeBytes == pinned.sizeBytes)
            val expectedParts = ((file.sizeBytes + size - 1) / size).toInt()
            require(file.partCount == expectedParts && file.uploadedParts.distinct().size == file.uploadedParts.size &&
                file.uploadedParts.all { it in 0 until expectedParts })
        }
    }

    private fun <T> requireData(code: Int, data: T?): T {
        if (code != 0 || data == null) throw IOException("Server result is not confirmed")
        return data
    }
}

class UploadPausedException : IOException("后续上传已暂停，已保存材料保留")

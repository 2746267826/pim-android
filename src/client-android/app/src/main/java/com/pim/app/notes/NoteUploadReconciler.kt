package com.pim.app.notes

import com.pim.core.contract.NoteMaterialUploadDto
import com.pim.core.contract.NoteMaterialVersionDto
import com.pim.core.contract.PromoteNoteMaterialVersionRequest
import com.pim.core.network.ApiService
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Reconciles durable upload metadata with the server's material/version
 * operations. It never publishes, deletes, or rewrites fixed local material.
 */
class NoteUploadReconciler(
    private val jobs: NoteUploadStore,
    private val api: ApiService,
) {
    suspend fun refresh(jobId: String): NoteUploadJob = NoteUploadJobLocks.withLock(jobId) {
        refreshLocked(jobId)
    }

    private suspend fun refreshLocked(jobId: String, failOnRemoteSummary: Boolean = false): NoteUploadJob {
        val job = jobs.read(jobId)
        val response = api.getNoteMaterialVersion(job.versionId)
        val version = requireData(response.code, response.data, response.message)
        validateIdentity(job, version)
        val localSummary = job.localVersion ?: noteMaterialVersionSummary(version.inputJson, version.manifestJson, version.upload)
        val current = version.upload.currentVersionId
        var remote: NoteMaterialVersionSummary? = null
        var problem: String? = null
        if (version.upload.hasConflict && current != null && current != job.versionId) {
            remote = try {
                readRemoteSummary(job, current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (failOnRemoteSummary) throw IOException("服务器当前版本摘要尚未确认", error)
                problem = "服务器当前版本摘要暂时无法读取；本机回执与双方原文件仍保留"
                job.conflictRemoteVersion?.takeIf { it.versionId == current }
            }
        }
        return jobs.update(job.id) {
            it.withServerReceipt(version.upload, remoteVersion = remote, problem = problem)
                .copy(localVersion = it.localVersion ?: localSummary)
        }
    }

    suspend fun promote(jobId: String, selectedVersionId: String): NoteUploadJob = NoteUploadJobLocks.withLock(jobId) {
        promoteLocked(jobId, selectedVersionId)
    }

    private suspend fun promoteLocked(jobId: String, selectedVersionId: String): NoteUploadJob {
        var job = jobs.read(jobId)
        require(job.phase == NoteUploadPhase.Conflict || job.serverUpload?.hasConflict == true) {
            "Only a confirmed conflict can be resolved by explicit promotion"
        }
        if (needsConflictSummaryRefresh(job)) job = refreshLocked(jobId, failOnRemoteSummary = true)
        val receipt = requireNotNull(job.serverUpload) { "Refresh the server receipt before choosing a version" }
        require(selectedVersionId == job.versionId ||
            selectedVersionId == receipt.currentVersionId ||
            selectedVersionId == job.conflictRemoteVersion?.versionId) {
            "The selected version is not one of the two conflict summaries"
        }
        val remoteSummary = if (selectedVersionId == job.versionId) {
            requireNotNull(job.localVersion?.takeIf { it.versionId == job.versionId && it.noteId == job.noteId }) {
                "Both conflict summaries must be confirmed before keeping the local version"
            }
            require(receipt.hasConflict && receipt.currentVersionId != null) {
                "The server no longer reports an open conflict"
            }
            requireNotNull(job.conflictRemoteVersion?.takeIf { it.versionId == receipt.currentVersionId }) {
                "Both conflict summaries must be confirmed before keeping the local version"
            }
        } else {
            null
        }
        val response = api.promoteNoteMaterialVersion(
            selectedVersionId,
            PromoteNoteMaterialVersionRequest(receipt.currentVersionId),
        )
        val promoted = requireData(response.code, response.data, response.message)
        require(promoted.versionId == selectedVersionId && promoted.noteId == job.noteId &&
            promoted.isCurrent && promoted.currentVersionId == selectedVersionId) {
            "The server did not confirm the explicitly selected version"
        }
        if (selectedVersionId == job.versionId) {
            return jobs.update(job.id) {
                it.withServerReceipt(
                    promoted,
                    remoteVersion = null,
                    promotedVersionId = selectedVersionId,
                    problem = null,
                )
            }
        }
        val remote = remoteSummary ?: job.conflictRemoteVersion?.takeIf { it.versionId == selectedVersionId }
            ?: readRemoteSummary(job, selectedVersionId)
        val chosen = remote.copy(
            serverPhase = promoted.phase,
            isCurrent = promoted.isCurrent,
            hasConflict = promoted.hasConflict,
        )
        return jobs.update(job.id) {
            it.copy(
                phase = NoteUploadPhase.Uploaded,
                currentVersionId = promoted.currentVersionId,
                conflictRemoteVersion = chosen,
                resolutionVersionId = selectedVersionId,
                problem = null,
            )
        }
    }

    private fun needsConflictSummaryRefresh(job: NoteUploadJob): Boolean {
        if (job.localVersion?.versionId != job.versionId) return true
        val receipt = job.serverUpload ?: return true
        if (!receipt.hasConflict) return false
        val current = receipt.currentVersionId ?: return true
        return current != job.versionId && job.conflictRemoteVersion?.versionId != current
    }

    private suspend fun readRemoteSummary(job: NoteUploadJob, versionId: String): NoteMaterialVersionSummary {
        require(versionId != job.versionId)
        val response = api.getNoteMaterialVersion(versionId)
        val version = requireData(response.code, response.data, response.message)
        require(version.upload.versionId == versionId && version.upload.noteId == job.noteId) {
            "Server returned a different conflict version"
        }
        return noteMaterialVersionSummary(version.inputJson, version.manifestJson, version.upload)
    }

    private fun validateIdentity(job: NoteUploadJob, version: NoteMaterialVersionDto) {
        val upload = version.upload
        require(upload.versionId == job.versionId && upload.noteId == job.noteId && upload.baseVersionId == job.baseVersionId) {
            "Server material identity does not match the durable upload intent"
        }
        require(materialDigest(version.inputJson) == job.inputSha256 &&
            materialDigest(version.manifestJson) == job.manifestSha256) {
            "Server returned different fixed bytes for this version"
        }
    }

    private fun <T> requireData(code: Int, data: T?, message: String): T {
        if (code != 0 || data == null) throw IOException(message.ifBlank { "Server result is not confirmed" })
        return data
    }
}

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
    suspend fun refresh(jobId: String): NoteUploadJob {
        val job = jobs.read(jobId)
        val response = api.getNoteMaterialVersion(job.versionId)
        val version = requireData(response.code, response.data, response.message)
        validateIdentity(job, version)
        val current = version.upload.currentVersionId
        var remote: NoteMaterialVersionSummary? = null
        var problem: String? = null
        if (version.upload.hasConflict && current != null && current != job.versionId) {
            remote = try {
                readRemoteSummary(job, current)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                problem = "服务器当前版本摘要暂时无法读取；本机回执与双方原文件仍保留"
                job.conflictRemoteVersion?.takeIf { it.versionId == current }
            }
        }
        return jobs.update(job.id) {
            it.withServerReceipt(version.upload, remoteVersion = remote, problem = problem)
        }
    }

    suspend fun promote(jobId: String, selectedVersionId: String): NoteUploadJob {
        var job = jobs.read(jobId)
        require(job.phase == NoteUploadPhase.Conflict || job.serverUpload?.hasConflict == true) {
            "Only a confirmed conflict can be resolved by explicit promotion"
        }
        if (job.serverUpload == null) job = refresh(jobId)
        val receipt = requireNotNull(job.serverUpload) { "Refresh the server receipt before choosing a version" }
        require(selectedVersionId == job.versionId ||
            selectedVersionId == receipt.currentVersionId ||
            selectedVersionId == job.conflictRemoteVersion?.versionId) {
            "The selected version is not one of the two conflict summaries"
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
        val remote = job.conflictRemoteVersion?.takeIf { it.versionId == selectedVersionId }
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

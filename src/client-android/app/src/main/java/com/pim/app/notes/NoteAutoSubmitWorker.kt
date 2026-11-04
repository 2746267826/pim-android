package com.pim.app.notes

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class NoteAutoSubmitWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val uploads: NoteUploadRepository,
) : CoroutineWorker(context, parameters) {
    @AssistedFactory
    interface Factory { fun create(context: Context, parameters: WorkerParameters): NoteAutoSubmitWorker }

    override suspend fun doWork(): Result {
        val versionId = inputData.getString(VERSION_ID)?.takeIf(::validNoteId) ?: return Result.failure()
        return when (uploads.processAutoSubmit(versionId)) {
            AutoSubmitWorkerOutcome.Retry -> Result.retry()
            AutoSubmitWorkerOutcome.Submitted,
            AutoSubmitWorkerOutcome.Deferred,
            AutoSubmitWorkerOutcome.Revoked -> Result.success()
        }
    }

    companion object {
        const val VERSION_ID = "auto_submit_material_version_id"
        const val TAG = "native-note-auto-submit"
    }
}

internal fun noteAutoSubmitVersionTag(versionId: String): String {
    require(validNoteId(versionId))
    return "${NoteAutoSubmitWorker.TAG}:$versionId"
}

internal fun noteAutoSubmitRequest(versionId: String): OneTimeWorkRequest {
    require(validNoteId(versionId))
    return OneTimeWorkRequestBuilder<NoteAutoSubmitWorker>()
        .setInputData(workDataOf(NoteAutoSubmitWorker.VERSION_ID to versionId))
        .addTag(NoteAutoSubmitWorker.TAG)
        .addTag(noteAutoSubmitVersionTag(versionId))
        .build()
}

internal fun cancelNoteAutoSubmit(workManager: androidx.work.WorkManager, versionId: String) {
    workManager.cancelAllWorkByTag(noteAutoSubmitVersionTag(versionId)).result.get()
}

/** Persist the submit worker after the same version's existing preparation work. */
internal fun enqueueNoteAutoSubmit(workManager: androidx.work.WorkManager, versionId: String, phase: NoteMaterialPhase): Boolean {
    val request = noteAutoSubmitRequest(versionId)
    when (phase) {
        NoteMaterialPhase.Ready -> workManager.enqueueUniqueWork(
            "note-auto-submit-$versionId", androidx.work.ExistingWorkPolicy.KEEP, request,
        ).result.get()
        NoteMaterialPhase.Preparing -> {
            val existing = workManager.getWorkInfosForUniqueWork("note-material-$versionId").get()
            if (existing.any { !it.state.isFinished && NoteAutoSubmitWorker.TAG in it.tags }) return true
            workManager.beginUniqueWork(
                "note-material-$versionId", androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, request,
            ).enqueue().result.get()
        }
        NoteMaterialPhase.Failed -> return false
    }
    return true
}

package com.pim.app.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.WorkManagerTestInitHelper
import com.pim.app.TestPimApp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteAutoSubmitSchedulingTest {
    private lateinit var workManager: WorkManager
    private val versionId = "33333333-3333-4333-8333-333333333333"

    private fun delayedAutoSubmitRequest() = OneTimeWorkRequestBuilder<NoteAutoSubmitWorker>()
        .setInputData(workDataOf(NoteAutoSubmitWorker.VERSION_ID to versionId))
        .setInitialDelay(1, TimeUnit.DAYS)
        .addTag(NoteAutoSubmitWorker.TAG)
        .addTag(noteAutoSubmitVersionTag(versionId))
        .build()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test fun submitWorkerIsDurablyBlockedBehindThatVersionsPreparation() {
        val preparation = OneTimeWorkRequestBuilder<NoteMaterialWorker>()
            .setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to versionId))
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag(NoteMaterialWorker.TAG)
            .build()
        workManager.enqueueUniqueWork("note-material-$versionId", ExistingWorkPolicy.KEEP, preparation).result.get()

        assertTrue(enqueueNoteAutoSubmit(workManager, versionId, NoteMaterialPhase.Preparing))
        assertEquals(0L, noteAutoSubmitRequest(versionId).workSpec.initialDelay)
        val submit = workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().single()
        assertEquals(WorkInfo.State.BLOCKED, submit.state)
        assertTrue(workManager.getWorkInfosForUniqueWork("note-material-$versionId").get().any { it.id == preparation.id })
    }

    @Test fun revocationTagCancelsPreparingChainSubmitOnlyAndLeavesPreparationWork() {
        val preparation = OneTimeWorkRequestBuilder<NoteMaterialWorker>()
            .setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to versionId))
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag(NoteMaterialWorker.TAG)
            .build()
        workManager.enqueueUniqueWork("note-material-$versionId", ExistingWorkPolicy.KEEP, preparation).result.get()
        workManager.beginUniqueWork("note-material-$versionId", ExistingWorkPolicy.APPEND_OR_REPLACE, delayedAutoSubmitRequest()).enqueue().result.get()

        cancelNoteAutoSubmit(workManager, versionId)
        val chain = workManager.getWorkInfosForUniqueWork("note-material-$versionId").get()
        val submit = workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().single()
        assertEquals(WorkInfo.State.CANCELLED, submit.state)
        assertTrue(chain.any { it.id == preparation.id && !it.state.isFinished })
        assertTrue(chain.any { it.id == submit.id && it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun revocationTagCancelsReadyAutoSubmitWithoutCancellingUploadQueue() {
        workManager.enqueueUniqueWork("note-auto-submit-$versionId", ExistingWorkPolicy.KEEP, delayedAutoSubmitRequest()).result.get()
        val existingUpload = OneTimeWorkRequestBuilder<NoteMaterialWorker>()
            .setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to versionId))
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag(NoteUploadWorker.TAG)
            .build()
        workManager.enqueueUniqueWork("note-upload-existing-$versionId", ExistingWorkPolicy.KEEP, existingUpload).result.get()

        cancelNoteAutoSubmit(workManager, versionId)
        val submit = workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().single()
        val upload = workManager.getWorkInfosByTag(NoteUploadWorker.TAG).get().single()
        assertEquals(WorkInfo.State.CANCELLED, submit.state)
        assertEquals(WorkInfo.State.ENQUEUED, upload.state)
    }

    @Test fun failedMaterialsDoNotScheduleAutomaticSubmission() {
        assertTrue(!enqueueNoteAutoSubmit(workManager, versionId, NoteMaterialPhase.Failed))
        assertTrue(workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().isEmpty())
    }
}

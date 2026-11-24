package com.pim.app.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import androidx.work.testing.WorkManagerTestInitHelper
import com.pim.app.TestPimApp
import com.pim.core.auth.SecurePreferencesFactory
import com.pim.core.auth.TokenManager
import com.pim.core.network.BoundUploadSession
import com.pim.core.settings.ServerSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val otherVersionId = "44444444-4444-4444-8444-444444444444"

    private fun delayedRequest(id: String = versionId) = OneTimeWorkRequestBuilder<NoteAutoSubmitWorker>()
        .setInputData(workDataOf(NoteAutoSubmitWorker.VERSION_ID to id))
        .setInitialDelay(1, TimeUnit.DAYS).addTag(NoteAutoSubmitWorker.TAG)
        .addTag(noteAutoSubmitVersionTag(id)).build()

    private fun preparation(id: String) = OneTimeWorkRequestBuilder<NoteMaterialWorker>()
        .setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to id))
        .setInitialDelay(1, TimeUnit.DAYS).addTag(NoteMaterialWorker.TAG).build()

    private fun enqueuePreparingChain(id: String, preparation: androidx.work.OneTimeWorkRequest) {
        workManager.enqueueUniqueWork("note-material-$id", ExistingWorkPolicy.KEEP, preparation).result.get()
        workManager.beginUniqueWork("note-material-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, delayedRequest(id))
            .enqueue().result.get()
    }

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("native_note_upload_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
    }

    @Test fun submitWaitsBehindPreparationAndRevocationLeavesPreparationIntact() {
        // 必须用带 setInitialDelay 的 preparation()：手写一个没有 delay 的请求时，
        // Robolectric 的同步执行器会让 NoteMaterialWorker 立刻跑完，submit 随即脱离
        // BLOCKED，整条断言链的前提（preparation 未完成）就消失了 —— CI 上间歇性
        // 失败在「取消后应为 CANCELLED」那一行，根因在这里，不在断言本身。
        val preparation = preparation(versionId)
        workManager.enqueueUniqueWork("note-material-$versionId", ExistingWorkPolicy.KEEP, preparation).result.get()
        assertTrue(enqueueNoteAutoSubmit(workManager, versionId, NoteMaterialPhase.Preparing))
        val submit = workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().single()
        assertEquals(WorkInfo.State.BLOCKED, submit.state)
        cancelNoteAutoSubmit(workManager, versionId)
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().single().state)
        // 断言「撤销 submit 不会连带取消 preparation」这件事本身，而不是「preparation 还没跑完」：
        // 后者与 worker 的实际执行赛跑 —— 在 Robolectric 的测试 WorkManager 下 NoteMaterialWorker
        // 可能瞬间完成（CI 已两次在同一行变红）。preparation 变成 SUCCEEDED 同样是「intact」，
        // 真正要抓的是它被 CANCELLED。
        assertTrue(
            workManager.getWorkInfosForUniqueWork("note-material-$versionId").get()
                .any { it.id == preparation.id && it.state != WorkInfo.State.CANCELLED }
        )
    }

    @Test fun revokingPreparingVersionCancelsOnlyItsQueuedSubmitWorker() {
        val firstPreparation = preparation(versionId)
        val otherPreparation = preparation(otherVersionId)
        enqueuePreparingChain(versionId, firstPreparation)
        enqueuePreparingChain(otherVersionId, otherPreparation)

        cancelNoteAutoSubmit(workManager, versionId)

        val chain = workManager.getWorkInfosForUniqueWork("note-material-$versionId").get()
        val submit = workManager.getWorkInfosByTag(noteAutoSubmitVersionTag(versionId)).get().single()
        val otherChain = workManager.getWorkInfosForUniqueWork("note-material-$otherVersionId").get()
        val otherSubmit = workManager.getWorkInfosByTag(noteAutoSubmitVersionTag(otherVersionId)).get().single()
        assertEquals(WorkInfo.State.CANCELLED, submit.state)
        assertTrue(chain.any { it.id == firstPreparation.id && !it.state.isFinished })
        assertTrue(chain.any { it.id == submit.id && it.state == WorkInfo.State.CANCELLED })
        assertTrue(otherChain.any { it.id == otherPreparation.id && !it.state.isFinished })
        assertEquals(WorkInfo.State.BLOCKED, otherSubmit.state)
    }

    @Test fun durableRevocationCancelsScheduledAutoSubmitsThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val owner = "55555555-5555-4555-8555-555555555555"
        val target = NoteUploadTarget(BoundUploadSession("https://notes.example", owner), "Alice")
        val store = NoteAutoSubmitStore(context)
        assertTrue(store.authorizeForVersion(target, versionId))
        assertTrue(store.authorizeForVersion(target, otherVersionId))
        val firstPreparation = preparation(versionId)
        val secondPreparation = preparation(otherVersionId)
        enqueuePreparingChain(versionId, firstPreparation)
        enqueuePreparingChain(otherVersionId, secondPreparation)

        val tokens = TokenManager(object : SecurePreferencesFactory {
            override fun open() = context.getSharedPreferences("scheduling-auth", Context.MODE_PRIVATE)
        }, nowMillis = { 1_000L })
        val repository = NoteUploadRepository(context, tokens, ServerSettingsStore(context, tokens))
        assertTrue(repository.revokeAutoSubmitDurably())

        assertNull(store.authorization())
        assertTrue(store.pendingVersionIds().isEmpty())
        assertEquals(WorkInfo.State.CANCELLED,
            workManager.getWorkInfosByTag(noteAutoSubmitVersionTag(versionId)).get().single().state)
        assertEquals(WorkInfo.State.CANCELLED,
            workManager.getWorkInfosByTag(noteAutoSubmitVersionTag(otherVersionId)).get().single().state)
        assertTrue(workManager.getWorkInfosForUniqueWork("note-material-$versionId").get()
            .any { it.id == firstPreparation.id && !it.state.isFinished })
        assertTrue(workManager.getWorkInfosForUniqueWork("note-material-$otherVersionId").get()
            .any { it.id == secondPreparation.id && !it.state.isFinished })
    }

    @Test fun failedMaterialIsNotAutoSubmitted() {
        assertTrue(!enqueueNoteAutoSubmit(workManager, versionId, NoteMaterialPhase.Failed))
        assertTrue(workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().isEmpty())
    }

    @Test fun revocationCancelsAutoSubmitButNotAlreadyQueuedUpload() {
        workManager.enqueueUniqueWork("note-auto-submit-$versionId", ExistingWorkPolicy.KEEP, delayedRequest(versionId)).result.get()
        val upload = OneTimeWorkRequestBuilder<NoteMaterialWorker>()
            .setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to versionId)).setInitialDelay(1, TimeUnit.DAYS)
            .addTag(NoteUploadWorker.TAG).build()
        workManager.enqueueUniqueWork("note-upload-existing-$versionId", ExistingWorkPolicy.KEEP, upload).result.get()
        cancelNoteAutoSubmit(workManager, versionId)
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfosByTag(NoteAutoSubmitWorker.TAG).get().single().state)
        assertEquals(WorkInfo.State.ENQUEUED, workManager.getWorkInfosByTag(NoteUploadWorker.TAG).get().single().state)
    }
}

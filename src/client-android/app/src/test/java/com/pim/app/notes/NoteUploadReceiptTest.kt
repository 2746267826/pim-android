package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.contract.*
import com.pim.core.network.ApiService
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteUploadReceiptTest {
    @get:Rule val temp = TemporaryFolder()
    private val notes get() = NoteFileStore(File(temp.root, "notes"))
    private val materials get() = NoteMaterialStore.at(temp.root)
    private val jobs get() = NoteUploadStore.at(temp.root)
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val server = "https://synthetic.invalid"

    @Test fun perFilePartReceiptSurvivesStoreReopenAndIsNeverInferredFromLocalBytes() = runBlocking {
        val local = snapshot("Local A")
        val job = jobs.create(local, server, owner, "alice")
        val receipt = upload(local, phase = "Uploading", current = null, conflict = false, isCurrent = false,
            uploaded = listOf(listOf(0), emptyList()))

        jobs.update(job.id) {
            it.copy(phase = NoteUploadPhase.Uploading, uploadedBytes = receipt.confirmedBytes(), serverUpload = receipt)
        }

        val reopened = NoteUploadStore.at(temp.root).read(job.id)
        assertEquals("Uploading", reopened.serverUpload?.phase)
        assertEquals(listOf(listOf(0), emptyList()), reopened.serverUpload?.files?.map { file -> file.uploadedParts })
        assertEquals(receipt.confirmedBytes(), reopened.uploadedBytes)
        assertTrue(reopened.uploadedBytes < reopened.totalBytes)
        assertNotEquals(NoteUploadPhase.Uploaded, reopened.phase)
    }

    @Test fun refreshUsesVersionEndpointToRestoreMissingReceiptThenRecognizesWholeVersionConfirmation() = runBlocking {
        val local = snapshot("Local A")
        val job = jobs.create(local, server, owner, "alice")
        val remote = FakeServer(local)
        remote.receipt = upload(local, phase = "Uploading", current = null, conflict = false, isCurrent = false,
            uploaded = listOf(listOf(0), emptyList()))
        jobs.update(job.id) {
            it.copy(phase = NoteUploadPhase.WaitingForNetwork, uploadedBytes = 0, serverUpload = null)
        }

        val restored = NoteUploadReconciler(jobs, remote.api).refresh(job.id)
        val persisted = NoteUploadStore.at(temp.root).read(job.id)
        assertEquals(listOf(listOf(0), emptyList()), persisted.serverUpload?.files?.map { it.uploadedParts })
        assertEquals(remote.receipt.confirmedBytes(), persisted.uploadedBytes)
        assertEquals(NoteUploadPhase.WaitingForNetwork, restored.phase)

        remote.receipt = upload(local, phase = "Uploaded", current = local.versionId, conflict = false, isCurrent = true,
            uploaded = local.files.mapIndexed { _, file -> (0 until partCount(file.sizeBytes)).toList() })
        val confirmed = NoteUploadReconciler(jobs, remote.api).refresh(job.id)
        assertEquals(NoteUploadPhase.Uploaded, confirmed.phase)
        assertEquals(local.sizeBytes, confirmed.uploadedBytes)
        assertEquals("Uploaded", confirmed.serverUpload?.phase)
        assertTrue(confirmed.serverUpload!!.isCurrent)
    }

    @Test fun promoteLocalUsesStaleCasExpectedHeadAndRecordsTheServerReceipt() = runBlocking {
        val (job, local, remoteVersion) = conflictFixture()
        val remote = FakeServer(local).apply {
            this.remoteVersionId = remoteVersion.versionId
            remoteVersionDto = versionDto(remoteVersion)
            val success = upload(local, phase = "Uploaded", current = local.versionId, conflict = false, isCurrent = true,
                uploaded = local.files.mapIndexed { _, file -> (0 until partCount(file.sizeBytes)).toList() })
            receipt = success
            promoteResult = success
        }

        val promoted = NoteUploadReconciler(jobs, remote.api).promote(job.id, local.versionId)

        assertEquals(remoteVersion.versionId, remote.promoteRequest?.expectedCurrentVersionId)
        assertEquals(local.versionId, remote.promoteVersionId)
        assertEquals(NoteUploadPhase.Uploaded, promoted.phase)
        assertEquals(local.versionId, promoted.currentVersionId)
        assertEquals(local.versionId, promoted.resolutionVersionId)
        assertNull(promoted.conflictRemoteVersion)
        assertFalse(promoted.serverUpload!!.hasConflict)
        assertTrue(promoted.serverUpload!!.isCurrent)
    }

    @Test fun promoteServerVersionClearsClientConflictWithoutDeletingEitherMaterialSet() = runBlocking {
        val (job, local, remoteVersion) = conflictFixture()
        val remote = FakeServer(local).apply {
            remoteVersionId = remoteVersion.versionId
            remoteVersionDto = versionDto(remoteVersion)
        }
        remote.promoteResult = upload(remoteVersion, phase = "Uploaded", current = remoteVersion.versionId,
            conflict = false, isCurrent = true,
            uploaded = remoteVersion.files.mapIndexed { _, file -> (0 until partCount(file.sizeBytes)).toList() })

        val promoted = NoteUploadReconciler(jobs, remote.api).promote(job.id, remoteVersion.versionId)

        assertEquals(remoteVersion.versionId, promoted.resolutionVersionId)
        assertEquals(remoteVersion.versionId, promoted.currentVersionId)
        assertEquals(NoteUploadPhase.Uploaded, promoted.phase)
        assertEquals("Conflict", promoted.serverUpload?.phase)
        assertNotNull(promoted.serverUpload)
        assertNotNull(promoted.conflictRemoteVersion)
        assertEquals(local.versionId, materials.read(local.versionId).id)
        assertTrue(materials.entries().any { it.id == local.versionId })
    }

    @Test fun staleCasRejectionLeavesConflictRecordAndBothMaterialSummariesIntact() = runBlocking {
        val (job, local, remoteVersion) = conflictFixture()
        val remote = FakeServer(local).apply {
            remoteVersionId = remoteVersion.versionId
            remoteVersionDto = versionDto(remoteVersion)
            stale = true
        }

        try {
            NoteUploadReconciler(jobs, remote.api).promote(job.id, local.versionId)
            fail("Expected the server CAS conflict to be exposed")
        } catch (error: HttpException) {
            assertEquals(409, error.code())
        }

        val preserved = NoteUploadStore.at(temp.root).read(job.id)
        assertEquals(NoteUploadPhase.Conflict, preserved.phase)
        assertEquals(local.versionId, preserved.localVersion?.versionId)
        assertEquals(remoteVersion.versionId, preserved.conflictRemoteVersion?.versionId)
        assertTrue(preserved.serverUpload!!.hasConflict)
        assertEquals(local.versionId, materials.read(local.versionId).id)
    }

    @Test fun receiptPresentationSeparatesConfirmedPartsWholeVersionAndProcessing() {
        assertEquals("0–2, 4, 7–8", formatUploadedPartIndexes(listOf(0, 2, 1, 4, 8, 7)))
        assertEquals("无", formatUploadedPartIndexes(emptyList()))

        val pending = playbackJob()
            .withReceipt(phase = "Uploading", hasConflict = false)
            .copy(phase = NoteUploadPhase.Uploading)
        assertEquals("分块已确认；整版尚未确认", uploadReceiptPhaseText(pending))

        val uploaded = pending.withReceipt(phase = "Uploaded", hasConflict = false)
            .copy(phase = NoteUploadPhase.Uploaded)
        assertEquals("整版已确认", uploadReceiptPhaseText(uploaded))

        val conflict = pending.withReceipt(phase = "Conflict", hasConflict = true)
            .copy(phase = NoteUploadPhase.Conflict)
        assertEquals("整版已确认；存在版本冲突", uploadReceiptPhaseText(conflict))
        assertEquals("整版已确认；冲突已解决，当前保留另一版本",
            uploadReceiptPhaseText(conflict.copy(resolutionVersionId = conflict.serverUpload!!.currentVersionId)))

        assertEquals("转写：未发起", processingStatusLabel("转写", asr = null, artifact = null))
        assertEquals("整理：未发起", processingStatusLabel("整理", asr = null, artifact = null))
    }

    private fun conflictFixture(): Triple<NoteUploadJob, NoteUploadSnapshot, NoteUploadSnapshot> {
        val local = snapshot("Local A")
        val remote = snapshot("Server B", materials.read(local.versionId).document)
        val job = jobs.create(local, server, owner, "alice")
        val conflictReceipt = upload(local, phase = "Conflict", current = remote.versionId, conflict = true, isCurrent = false,
            uploaded = local.files.mapIndexed { _, file -> (0 until partCount(file.sizeBytes)).toList() })
        val remoteUpload = upload(remote, phase = "Uploaded", current = remote.versionId, conflict = false, isCurrent = true,
            uploaded = remote.files.mapIndexed { _, file -> (0 until partCount(file.sizeBytes)).toList() })
        val remoteSummary = noteMaterialVersionSummary(remote.inputJson, remote.manifestJson, remoteUpload)
        jobs.update(job.id) {
            it.copy(
                phase = NoteUploadPhase.Conflict,
                uploadedBytes = it.totalBytes,
                currentVersionId = remote.versionId,
                serverUpload = conflictReceipt,
                conflictRemoteVersion = remoteSummary,
            )
        }
        return Triple(jobs.read(job.id), local, remote)
    }

    private fun snapshot(title: String, base: NoteDocument? = null): NoteUploadSnapshot {
        val note = (base ?: NoteDocument.create()).withTitle(title)
            .withBlocks(listOf(markdownBlock("# $title\n\nKeep exact fixed bytes.")))
            .nextRevision()
        notes.save(note)
        val input = materials.create(note, emptyList())
        materials.prepare(input.id)
        return materials.uploadSnapshot(input.id)
    }

    private fun upload(source: NoteUploadSnapshot, phase: String, current: String?, conflict: Boolean,
        isCurrent: Boolean, uploaded: List<List<Int>>): NoteMaterialUploadDto {
        val limits = limits()
        return NoteMaterialUploadDto(
            baseVersionId = null,
            createdAt = "2026-11-23T00:00:00Z",
            currentVersionId = current,
            expiresAt = "2026-11-30T00:00:00Z",
            files = source.files.mapIndexed { index, file ->
                NoteMaterialUploadFileDto(index, file.kind.name, partCount(file.sizeBytes), file.path, file.sha256,
                    file.sizeBytes, uploaded[index])
            },
            hasConflict = conflict,
            isCurrent = isCurrent,
            limits = limits,
            noteId = source.noteId,
            phase = phase,
            versionId = source.versionId,
        )
    }

    private fun limits() = NoteMaterialUploadLimitsDto(20_000, 8_388_608, 10, 20L * 1024 * 1024 * 1024,
        4L * 1024 * 1024 * 1024, 2_000, 1_048_576, 168)
    private fun partCount(size: Long) = ((size + 1_048_575) / 1_048_576).toInt()
    private fun versionDto(source: NoteUploadSnapshot): NoteMaterialVersionDto {
        val upload = upload(source, "Uploaded", source.versionId, false, true,
            source.files.mapIndexed { _, file -> (0 until partCount(file.sizeBytes)).toList() })
        return NoteMaterialVersionDto(source.inputJson, source.manifestJson, upload)
    }

    private inner class FakeServer(val local: NoteUploadSnapshot) {
        var receipt = upload(local, "Uploading", null, false, false, local.files.map { emptyList() })
        var remoteVersionId: String? = null
        var remoteVersionDto: NoteMaterialVersionDto? = null
        var promoteVersionId: String? = null
        var promoteRequest: PromoteNoteMaterialVersionRequest? = null
        var promoteResult = receipt
        var stale = false
        val api: ApiService = Proxy.newProxyInstance(ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)) { _, method, args ->
            when (method.name) {
                "getNoteMaterialVersion" -> {
                    val id = args!![0] as String
                    val version = if (id == local.versionId) {
                        NoteMaterialVersionDto(local.inputJson, local.manifestJson, receipt)
                    } else {
                        require(id == remoteVersionId) { "Unexpected version read $id" }
                        checkNotNull(remoteVersionDto)
                    }
                    NoteMaterialVersionDtoApiResponse(0, version, "ok", "2026-11-23T00:00:00Z")
                }
                "promoteNoteMaterialVersion" -> {
                    promoteVersionId = args!![0] as String
                    promoteRequest = args[1] as PromoteNoteMaterialVersionRequest
                    if (stale) {
                        val response = retrofit2.Response.error<Any>(
                            409,
                            """{"code":40901,"message":"stale head"}""".toResponseBody(),
                        )
                        failCall(args, HttpException(response))
                    } else {
                        NoteMaterialUploadDtoApiResponse(0, promoteResult, "ok", "2026-11-23T00:00:00Z")
                    }
                }
                else -> error("Unexpected operation ${method.name}")
            }
        } as ApiService

        @Suppress("UNCHECKED_CAST")
        private fun failCall(args: Array<out Any?>, error: Throwable): Any {
            (args.last() as kotlin.coroutines.Continuation<Any?>).resumeWith(Result.failure(error))
            return kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }
    }

    private fun NoteUploadJob.withReceipt(phase: String, hasConflict: Boolean): NoteUploadJob {
        val receipt = NoteMaterialUploadDto(
            baseVersionId = null,
            createdAt = "2026-11-23T00:00:00Z",
            currentVersionId = if (hasConflict) "22222222-2222-4222-8222-222222222222" else null,
            expiresAt = "2026-11-30T00:00:00Z",
            files = emptyList(),
            hasConflict = hasConflict,
            isCurrent = false,
            limits = limits(),
            noteId = "11111111-1111-4111-8111-111111111111",
            phase = phase,
            versionId = "33333333-3333-4333-8333-333333333333",
        )
        return copy(serverUpload = receipt)
    }

    private fun playbackJob(): NoteUploadJob = NoteUploadJob(
        id = "44444444-4444-4444-8444-444444444444",
        versionId = "33333333-3333-4333-8333-333333333333",
        noteId = "11111111-1111-4111-8111-111111111111",
        revision = 1,
        serverIdentity = server,
        ownerId = owner,
        ownerName = "alice",
        inputSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        baseVersionId = null,
        createdAtMillis = 1,
        totalBytes = 1,
    )
}

package com.pim.app.notes

import android.graphics.Bitmap
import com.pim.app.TestPimApp
import com.pim.app.recording.*
import com.pim.core.contract.*
import com.pim.core.network.ApiService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteUploadEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val notes get() = NoteFileStore(File(temp.root, "notes"))
    private val recordings get() = RecordingFileStore(File(temp.root, "recordings"))
    private val materials get() = NoteMaterialStore.at(temp.root)
    private val jobs get() = NoteUploadStore.at(temp.root)
    private val owner = "11111111-1111-4111-8111-111111111111"

    @Test fun mixedMaterialBeyondFourMiBUploadsEveryByteAndNeverReadsCurrentNewDraft() = runBlocking {
        val original = mixed(); val audio = RecordingEngine(recordings, RecordingPolicy(), noteId = original.id)
        val samples = ShortArray(16_000 * 150) { (it % 1000).toShort() }; audio.append(samples, samples.size); audio.finish()
        val snapshot = snapshot(original, listOf(audio.session)); val job = create(snapshot); val remote = Server(snapshot)
        notes.save(original.withTitle("Current B").nextRevision())
        val result = NoteUploadEngine(jobs, materials, remote.api).run(job.id)
        assertTrue(snapshot.sizeBytes > 4 * 1024 * 1024); assertEquals(NoteUploadPhase.Uploaded, result.phase)
        assertEquals(snapshot.sizeBytes, result.uploadedBytes); assertEquals(1, remote.completes)
        assertEquals("Current B", notes.read(original.id).title)
        assertEquals(original, materials.read(snapshot.versionId).document)
        snapshot.files.forEachIndexed { index, file ->
            val bytes = remote.parts.filterKeys { it.first == index }.toSortedMap(compareBy { it.second }).values.fold(ByteArray(0)) { a, b -> a + b }
            assertEquals(file.sizeBytes, bytes.size.toLong()); assertEquals(file.sha256, materialDigest(bytes.inputStream()))
        }
    }

    @Test fun lostPartAcknowledgementReopensSameIntentAndSkipsTheAlreadyStoredPart() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot); remote.losePart = true
        try { NoteUploadEngine(jobs, materials, remote.api).run(job.id); fail("Expect lost ack") } catch (_: IOException) { }
        val first = remote.parts.keys.single()
        jobs.update(job.id) { it.copy(phase = NoteUploadPhase.WaitingForNetwork) }
        val result = NoteUploadEngine(NoteUploadStore.at(temp.root), NoteMaterialStore.at(temp.root), remote.api).run(job.id)
        assertEquals(NoteUploadPhase.Uploaded, result.phase)
        assertEquals(1, remote.partCalls.count { it == first }); assertEquals(1, remote.completes)
    }

    @Test fun lostCompletionNeverPretendsSuccessAndReconcilesWithoutPublishingAnotherVersion() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot); remote.loseComplete = true
        try { NoteUploadEngine(jobs, materials, remote.api).run(job.id); fail("Expect lost ack") } catch (_: IOException) { }
        assertNotEquals(NoteUploadPhase.Uploaded, jobs.read(job.id).phase)
        val writes = remote.partCalls.size
        val result = NoteUploadEngine(jobs, materials, remote.api).run(job.id)
        assertEquals(NoteUploadPhase.Uploaded, result.phase); assertEquals(1, remote.completes); assertEquals(writes, remote.partCalls.size)
    }

    @Test fun pauseStopsSubsequentPartsAndExplicitResumeKeepsReceivedBytes() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot)
        remote.onPart = { jobs.update(job.id) { it.copy(phase = NoteUploadPhase.Paused) } }
        try { NoteUploadEngine(jobs, materials, remote.api).run(job.id); fail("Expect pause") } catch (_: UploadPausedException) { }
        assertEquals(NoteUploadPhase.Paused, jobs.read(job.id).phase); assertEquals(1, remote.partCalls.size); assertEquals(0, remote.completes)
        remote.onPart = {}; jobs.update(job.id) { it.copy(phase = NoteUploadPhase.Queued) }
        assertEquals(NoteUploadPhase.Uploaded, NoteUploadEngine(jobs, materials, remote.api).run(job.id).phase)
        assertEquals(remote.parts.size, remote.partCalls.size)
    }

    @Test fun conflictRetainsOriginalBaseAndNeverPromotesOverAnotherVersion() = runBlocking {
        val original = mixed(); val first = snapshot(original); val firstJob = create(first)
        jobs.update(firstJob.id) { it.copy(phase = NoteUploadPhase.Uploaded, uploadedBytes = first.sizeBytes) }
        val next = snapshot(original.withTitle("B").nextRevision()); val nextJob = create(next)
        assertEquals(first.versionId, nextJob.baseVersionId)
        val remote = Server(next); remote.conflict = true
        val result = NoteUploadEngine(jobs, materials, remote.api).run(nextJob.id)
        assertEquals(NoteUploadPhase.Conflict, result.phase); assertEquals(first.versionId, remote.request!!.baseVersionId)
        assertEquals(remote.otherHead, result.currentVersionId)
    }

    @Test fun unknownRemotePhaseCannotBeSavedAsSuccessfulUpload() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot); remote.unknownPhase = true
        try { NoteUploadEngine(jobs, materials, remote.api).run(job.id); fail("Must reject unknown phase") } catch (_: IllegalArgumentException) { }
        assertNotEquals(NoteUploadPhase.Uploaded, jobs.read(job.id).phase); assertEquals(0, remote.partCalls.size)
    }

    @Test fun corruptedPinnedBytesFailBeforeSendingAReplacementFile() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot)
        val first = snapshot.files.first(); File(temp.root, "note-material-versions/blobs/${first.sha256}").appendBytes(byteArrayOf(3))
        try { NoteUploadEngine(jobs, materials, remote.api).run(job.id); fail("Must preserve fixed source") } catch (_: IllegalArgumentException) { }
        assertTrue(remote.parts.isEmpty()); assertNotEquals(NoteUploadPhase.Uploaded, jobs.read(job.id).phase)
    }

    @Test fun queueHasNoTokensAndCorruptJobDoesNotHideOtherDurableIntents() {
        val snapshot = snapshot(mixed()); val job = create(snapshot)
        val path = File(temp.root, "note-uploads/${job.id}.json"); val text = path.readText()
        assertFalse(text.contains("accessToken")); assertFalse(text.contains("refreshToken")); assertFalse(text.contains("Bearer"))
        File(temp.root, "note-uploads/${newNoteId()}.json").writeText("broken")
        val loaded = NoteUploadStore.at(temp.root).load()
        assertTrue(loaded.loadFailed); assertEquals(job.id, loaded.jobs.single().id)
        assertEquals(job.id, create(snapshot).id)
    }

    @Test fun atomicBackupOnlyCheckpointIsRecoveredWithoutRebinding() {
        val job = create(snapshot(mixed())); val file = File(temp.root, "note-uploads/${job.id}.json")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertEquals(job, NoteUploadStore.at(temp.root).load().jobs.single())
    }

    @Test fun retryAfterKeepsServerRequestedDelayIncludingHttpDateAndOverflow() {
        assertEquals(121_000L, uploadRetryNotBefore("120", 1_000))
        assertEquals(0L, uploadRetryNotBefore("Thu, 01 Jan 1970 00:00:00 GMT", 0))
        assertEquals(Long.MAX_VALUE, uploadRetryNotBefore(Long.MAX_VALUE.toString(), 1_000))
        assertEquals(1_000L, uploadRetryNotBefore("invalid", 1_000))
    }

    @Test fun boundedWorkerRunsContinueTheSameIntentWithoutRepeatingStoredParts() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot)
        var state = job; var rounds = 0; var previous = 0L
        while (state.phase != NoteUploadPhase.Uploaded && rounds < 20) {
            state = NoteUploadEngine(jobs, materials, remote.api, maxParts = 1).run(job.id)
            assertTrue(state.uploadedBytes >= previous); previous = state.uploadedBytes; rounds++
        }
        assertEquals(NoteUploadPhase.Uploaded, state.phase)
        assertEquals(snapshot.files.size + 1, rounds)
        assertEquals(remote.parts.size, remote.partCalls.size)
        assertEquals(1, remote.completes)
    }

    @Test fun corruptedIntentSizeIsRejectedBeforeAnyNetworkAndUpdatesCannotRebindMetadata() = runBlocking {
        val snapshot = snapshot(mixed()); val job = create(snapshot); val remote = Server(snapshot)
        try { jobs.update(job.id) { it.copy(totalBytes = it.totalBytes + 1) }; fail("Cannot rebind intent") } catch (_: IllegalArgumentException) { }
        val file = File(temp.root, "note-uploads/${job.id}.json")
        val before = NoteJson.parseToJsonElement(file.readText()).let { it as kotlinx.serialization.json.JsonObject }
        file.writeText(kotlinx.serialization.json.JsonObject(before + ("totalBytes" to kotlinx.serialization.json.JsonPrimitive(job.totalBytes + 1))).toString())
        try { NoteUploadEngine(jobs, materials, remote.api).run(job.id); fail("Cannot send corrupted metadata") } catch (_: IllegalArgumentException) { }
        assertNull(remote.request); assertTrue(remote.parts.isEmpty())
    }

    @Test fun loopbackTargetsDoNotWaitForPublicConnectivityButOtherTargetsStillDo() {
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, noteUploadNetworkType("http://127.0.0.1:7622"))
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, noteUploadNetworkType("http://[::1]:7622"))
        assertEquals(androidx.work.NetworkType.CONNECTED, noteUploadNetworkType("https://server.example"))
        assertEquals(androidx.work.NetworkType.CONNECTED, noteUploadNetworkType("http://192.168.1.50:5858"))
    }

    private fun create(snapshot: NoteUploadSnapshot) = jobs.create(snapshot, "https://synthetic.invalid", owner, "alice")
    private fun snapshot(note: NoteDocument, recordings: List<RecordingSession> = emptyList()): NoteUploadSnapshot {
        notes.save(note); val input = materials.create(note, recordings); materials.prepare(input.id); return materials.uploadSnapshot(input.id)
    }
    private fun mixed(): NoteDocument {
        val note = NoteDocument.create().withTitle("Fixed A")
        val png = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(20, 15, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        val photo = notes.importPhoto(note.id, png.inputStream())
        return note.withBlocks(listOf(markdownBlock("# A\n\nKeep exact text."), photo, inkBlock(320f))).nextRevision()
    }

    private class Server(val source: NoteUploadSnapshot) {
        val parts = linkedMapOf<Pair<Int, Int>, ByteArray>(); val partCalls = mutableListOf<Pair<Int, Int>>()
        val otherHead = newNoteId(); var completes = 0; var completed = false; var conflict = false
        var losePart = false; var loseComplete = false; var unknownPhase = false; var onPart: () -> Unit = {}
        var request: BeginNoteMaterialUploadRequest? = null
        val api: ApiService = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            when (method.name) {
                "beginNoteMaterialUpload" -> {
                    val received = args!![0] as BeginNoteMaterialUploadRequest
                    assertEquals(source.inputJson, received.inputJson); assertEquals(source.manifestJson, received.manifestJson)
                    assertEquals(source.versionId, received.versionId); assertTrue(received.storageConsent)
                    if (request != null) assertEquals(request, received); request = received
                    envelope()
                }
                "uploadNoteMaterialPart" -> {
                    val key = args!![1] as Int to (args[2] as Int)
                    val bytes = Buffer().also { (args[4] as RequestBody).writeTo(it) }.readByteArray()
                    assertEquals(args[3], materialDigest(bytes.inputStream())); parts[key] = bytes; partCalls += key; onPart()
                    if (losePart) { losePart = false; failCall(args, IOException("synthetic lost part ack")) }
                    else StringApiResponse(0, "stored", "ok", "2026-10-28T00:00:00Z")
                }
                "completeNoteMaterialUpload" -> {
                    completes++; completed = true
                    if (loseComplete) { loseComplete = false; failCall(args!!, IOException("synthetic lost complete ack")) }
                    else envelope()
                }
                else -> error("Unexpected operation ${method.name}; no implicit promotion or unbound requests allowed")
            }
        } as ApiService
        @Suppress("UNCHECKED_CAST")
        private fun failCall(args: Array<out Any?>, error: Throwable): Any {
            (args.last() as kotlin.coroutines.Continuation<Any?>).resumeWith(Result.failure(error))
            return kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }
        private fun envelope(): NoteMaterialUploadDtoApiResponse {
            val size = 1_048_576
            val limits = NoteMaterialUploadLimitsDto(20_000, 8_388_608, 10, 20L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024, 2_000, size, 168)
            val files = source.files.mapIndexed { index, file -> NoteMaterialUploadFileDto(index, file.kind.name,
                ((file.sizeBytes + size - 1) / size).toInt(), file.path, file.sha256, file.sizeBytes, parts.keys.filter { it.first == index }.map { it.second }) }
            val phase = if (unknownPhase) "Unexpected" else if (completed) { if (conflict) "Conflict" else "Uploaded" } else "Uploading"
            return NoteMaterialUploadDtoApiResponse(0, NoteMaterialUploadDto(request?.baseVersionId, "2026-10-28T00:00:00Z",
                if (conflict) otherHead else if (completed) source.versionId else request?.baseVersionId, "2026-11-01T00:00:00Z",
                files, conflict && completed, completed && !conflict, limits, source.noteId, phase, source.versionId), "ok", "2026-10-28T00:00:00Z")
        }
    }
}

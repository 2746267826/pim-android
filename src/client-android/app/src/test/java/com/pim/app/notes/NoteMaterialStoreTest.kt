package com.pim.app.notes

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.pim.app.TestPimApp
import com.pim.app.recording.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
class NoteMaterialStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val notes get() = NoteFileStore(File(temp.root, "notes"))
    private val recordings get() = RecordingFileStore(File(temp.root, "recordings"))
    private val store get() = NoteMaterialStore.at(temp.root)
    private fun folder(id: String) = File(temp.root, "note-material-versions/$id")
    private fun blobs() = File(temp.root, "note-material-versions/blobs").listFiles().orEmpty().filter { validMaterialDigest(it.name) }

    @Test fun completeVersionHasOrderedEditableSourcePhotoMatchingInkAndEverySessionSegmentAndChunk() {
        val note = mixedNote()
        val first = record(note.id, 15, 4, 6)
        val second = record(note.id, 3)
        record(newNoteId(), 12)
        val input = store.create(note, listOf(second, first))
        val manifest = store.prepare(input.id)
        assertEquals(2, store.entries().single().recordingCount)
        assertEquals(4, store.entries().single().segmentCount)
        val audio = manifest.files.filter { it.kind == NoteMaterialKind.AudioChunk }
        assertEquals(first.chunks.size + second.chunks.size, audio.size)
        assertEquals(first.samples + second.samples, audio.sumOf { it.samples!! })
        assertEquals(setOf(first.id, second.id), audio.map { it.recordingId }.toSet())
        val files = export(input.id)
        assertEquals(note, NoteDocument.decode(files.getValue("note.json").toString(Charsets.UTF_8)))
        assertEquals(input, NoteJson.decodeFromString<NoteMaterialInput>(files.getValue("material-version.json").toString(Charsets.UTF_8)))
        manifest.files.forEach { material ->
            val bytes = files.getValue(material.path)
            assertEquals(material.sizeBytes, bytes.size.toLong())
            assertEquals(material.sha256, materialDigest(bytes.inputStream()))
            assertFalse(material.path.startsWith("/")); assertFalse(material.path.contains("..")); assertFalse(material.path.contains(":"))
        }
        assertTrue(files.getValue("document.md").toString(Charsets.UTF_8).contains("**exact**"))
        val ink = note.blocks.single { it.string("kind") == "ink" }
        val png = files.getValue(NoteFileStore.inkPath(ink))
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(320, bitmap.width); assertEquals(240, bitmap.height); bitmap.recycle()
        val json = files.getValue("materials.json").toString(Charsets.UTF_8)
        assertFalse(json.contains(temp.root.path)); assertFalse(json.contains("content://"))
    }

    @Test fun editingOriginalAndSupplementaryRecordingCannotChangePreparedVersion() {
        val original = mixedNote()
        val first = record(original.id, 8)
        val input = store.create(original, listOf(first))
        store.prepare(input.id)
        val before = export(input.id)
        val newer = original.withTitle("B").withBlocks(original.blocks.map { block ->
            if (block.string("kind") == "ink") withInk(block, inkOf(block).copy(strokes = emptyList())) else block
        }).nextRevision()
        notes.save(newer)
        val second = record(original.id, 9)
        val next = store.create(newer, listOf(first, second))
        store.prepare(next.id)
        val after = export(input.id)
        assertNotEquals(input.id, next.id)
        assertEquals(before.keys, after.keys)
        before.forEach { (path, bytes) -> assertArrayEquals(path, bytes, after.getValue(path)) }
        assertEquals(listOf(first), store.read(input.id).recordings)
        assertEquals(newer, notes.read(original.id))
    }

    @Test fun duplicateTapAndReorderedSessionListReuseSameDurableInputId() {
        val note = mixedNote()
        val first = record(note.id, 6)
        val second = record(note.id, 7)
        val input = store.create(note, listOf(first, second))
        assertEquals(input, store.create(note, listOf(second, first)))
        store.prepare(input.id)
        assertEquals(input, store.create(note, listOf(first, second)))
        assertEquals(1, store.entries().size)
    }

    @Test fun unchangedLargeMaterialsArePinnedOnlyOnceAcrossVersions() {
        val original = mixedNote()
        val audio = record(original.id, 20)
        val first = store.create(original, listOf(audio))
        val firstManifest = store.prepare(first.id)
        val count = blobs().size
        val newer = original.withTitle("New title").nextRevision()
        val second = store.create(newer, listOf(audio))
        val secondManifest = store.prepare(second.id)
        val firstAudio = firstManifest.files.filter { it.kind == NoteMaterialKind.AudioChunk }
        assertEquals(firstAudio, secondManifest.files.filter { it.kind == NoteMaterialKind.AudioChunk })
        assertEquals(count + 1, blobs().size) // Only note.json changed; the Markdown projection is identical.
        assertTrue(blobs().none { it.name.endsWith(".pending") })
    }

    @Test fun readyVersionDoesNotDependOnMutableOriginalPhotoInkCacheOrAudio() {
        val note = mixedNote()
        val audio = record(note.id, 6)
        val input = store.create(note, listOf(audio))
        store.prepare(input.id)
        val expected = export(input.id)
        val photo = note.blocks.single { it.string("kind") == "photo" }
        notes.photoFile(note.id, photo.string("assetId")).writeText("later damage")
        recordings.audioFile(audio.id, audio.chunks.first()).writeText("later damage")
        File(temp.root, "notes/${note.id}/${NoteFileStore.inkPath(note.blocks.single { it.string("kind") == "ink" })}").delete()
        store.prepare(input.id)
        val actual = export(input.id)
        expected.forEach { (path, bytes) -> assertArrayEquals(bytes, actual.getValue(path)) }
    }

    @Test fun incompletePreparationResumesOriginalRequestAfterCancellationAndNewerEdits() {
        val note = mixedNote()
        val audio = record(note.id, 20, 4, 6)
        val input = store.create(note, listOf(audio))
        assertThrows(CancellationException::class.java) {
            store.prepare(input.id) {
                val journal = File(folder(input.id), "files.json")
                if (journal.isFile && NoteJson.decodeFromString<List<NoteMaterialFile>>(readMaterialFile(journal)).size >= 3) throw CancellationException("test cancellation")
            }
        }
        assertFalse(File(folder(input.id), "manifest.json").exists())
        assertEquals(NoteMaterialPhase.Preparing, store.entries().single().phase)
        val newer = note.withTitle("Changed while waiting").nextRevision()
        notes.save(newer)
        record(note.id, 10)
        val restoredStore = NoteMaterialStore.at(temp.root)
        restoredStore.prepare(input.id)
        assertEquals(input, restoredStore.read(input.id))
        assertEquals(note, NoteDocument.decode(export(input.id).getValue("note.json").toString(Charsets.UTF_8)))
        assertEquals(listOf(audio), restoredStore.read(input.id).recordings)
        assertEquals(newer, notes.read(note.id))
    }

    @Test fun missingEarlyOrLateAudioChunkPreventsCompletenessAndRestoringItAllowsSameVersionRetry() {
        val note = mixedNote()
        val audio = record(note.id, 22, 4, 6)
        val input = store.create(note, listOf(audio))
        val last = recordings.audioFile(audio.id, audio.chunks.last())
        val bytes = last.readBytes(); last.delete()
        assertThrows(IllegalArgumentException::class.java) { store.prepare(input.id) }
        store.fail(input.id, NoteMaterialProblem.Incomplete)
        assertEquals(NoteMaterialPhase.Failed, store.entries().single().phase)
        assertFalse(File(folder(input.id), "manifest.json").exists())
        assertThrows(Exception::class.java) { export(input.id) }
        last.writeBytes(bytes)
        store.retry(input.id); store.prepare(input.id)
        assertEquals(NoteMaterialPhase.Ready, store.entries().single().phase)
        assertEquals(input, store.read(input.id))
    }

    @Test fun photoByteMismatchCannotBeRepinnedAsANewOriginalOnRetry() {
        val note = mixedNote()
        val input = store.create(note, emptyList())
        val photo = note.blocks.single { it.string("kind") == "photo" }
        notes.photoFile(note.id, photo.string("assetId")).appendText("tampered")
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { store.prepare(input.id) } }
        assertFalse(File(folder(input.id), "manifest.json").exists())
        assertEquals(input, store.read(input.id))
    }

    @Test fun wavHeaderAndSampleCountMismatchBlockSnapshotWithoutRepairingSource() {
        val note = mixedNote()
        val audio = record(note.id, 10)
        val file = recordings.audioFile(audio.id, audio.chunks.single())
        val original = file.readBytes()
        val mismatched = audio.copy(chunks = audio.chunks.map { it.copy(samples = it.samples + 1) })
        val input = store.create(note, listOf(mismatched))
        assertThrows(IllegalArgumentException::class.java) { store.prepare(input.id) }
        assertArrayEquals(original, file.readBytes())
        assertFalse(File(folder(input.id), "manifest.json").exists())
    }

    @Test fun pinnedByteDamageIsDetectedOnExportAndRetryNeverReplacesItFromCurrentSource() {
        val note = mixedNote()
        val input = store.create(note, emptyList())
        val manifest = store.prepare(input.id)
        val pinned = File(temp.root, "note-material-versions/blobs/${manifest.files.first().sha256}")
        pinned.appendText("damaged")
        assertThrows(IllegalArgumentException::class.java) { export(input.id) }
        assertEquals(NoteMaterialPhase.Failed, store.entries().single().phase)
        assertThrows(IllegalArgumentException::class.java) { store.prepare(input.id) }
        assertTrue(pinned.readText().endsWith("damaged"))
        assertEquals(note, store.read(input.id).document)
    }

    @Test fun truncatedCompletenessManifestCannotHideMissingMaterials() {
        val note = mixedNote()
        val input = store.create(note, listOf(record(note.id, 10)))
        val manifest = store.prepare(input.id)
        writeMaterialFile(File(folder(input.id), "manifest.json")) {
            it.write(NoteJson.encodeToString(manifest.copy(files = manifest.files.dropLast(1))).toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { export(input.id) }
        assertEquals(NoteMaterialProblem.Unreadable, store.entries().single().problem)
        assertEquals(input, store.read(input.id))
    }

    @Test fun committedManifestRemainsReadyIfProcessEndedBeforeStatusWrite() {
        val note = mixedNote()
        val input = store.create(note, emptyList())
        store.prepare(input.id)
        File(folder(input.id), "status.json").delete()
        assertEquals(NoteMaterialPhase.Ready, NoteMaterialStore.at(temp.root).entries().single().phase)
        assertEquals(note, NoteDocument.decode(export(input.id).getValue("note.json").toString(Charsets.UTF_8)))
    }

    @Test fun corruptRequestRemainsVisibleAndCannotPreventPreparingAnotherExplicitNewVersion() {
        val note = mixedNote()
        val broken = store.create(note, emptyList())
        File(folder(broken.id), "input.json").writeText("broken input, retain me")
        val other = NoteDocument.create()
        notes.save(other)
        val next = store.create(other, emptyList())
        store.prepare(next.id)
        assertEquals("broken input, retain me", File(folder(broken.id), "input.json").readText())
        assertEquals(NoteMaterialProblem.Unreadable, store.entries().single { it.id == broken.id }.problem)
        assertEquals(NoteMaterialPhase.Ready, store.entries().single { it.id == next.id }.phase)
    }

    @Test fun frozenInkRendersFromItsVectorNotLivePreviewCacheAndDoesNotRewriteThatCache() {
        val note = mixedNote()
        val block = note.blocks.single { it.string("kind") == "ink" }
        val cache = File(temp.root, "notes/${note.id}/${NoteFileStore.inkPath(block)}")
        cache.writeText("damaged live preview")
        val input = store.create(note, emptyList())
        store.prepare(input.id)
        val files = export(input.id)
        val bytes = files.getValue(NoteFileStore.inkPath(block))
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(bitmap); bitmap.recycle()
        assertEquals("damaged live preview", cache.readText())
        assertEquals(note, store.read(input.id).document)
    }

    @Test fun unsupportedBlocksArePreservedInFixedRequestButNeverReportedComplete() {
        val original = mixedNote()
        val note = original.withBlocks(original.blocks + buildJsonObject { put("id", newNoteId()); put("kind", "future"); put("payload", "opaque") }).nextRevision()
        notes.save(note)
        val input = store.create(note, emptyList())
        assertThrows(IllegalStateException::class.java) { store.prepare(input.id) }
        assertEquals(note, store.read(input.id).document)
        assertFalse(File(folder(input.id), "manifest.json").exists())
    }

    @Test fun activeForeignDamagedOrEmptyRecordingCannotBeFrozenAsComplete() {
        val note = mixedNote()
        val audio = record(note.id, 10)
        val invalid = listOf(audio.copy(phase = RecordingPhase.Recording), audio.copy(phase = RecordingPhase.Paused),
            audio.copy(noteId = newNoteId()), audio.copy(problem = RecordingProblem.Damaged), audio.copy(problem = RecordingProblem.Storage),
            audio.copy(chunks = emptyList()), audio.copy(id = "../../private"), audio.copy(schemaVersion = 99))
        invalid.forEach { session -> assertThrows(IllegalArgumentException::class.java) { store.create(note, listOf(session)) } }
        assertTrue(store.entries().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.read("../private") }
    }

    @Test fun recoveredInterruptedAudioCanBePreservedWithoutClaimingUninterruptedRecording() {
        val note = mixedNote()
        val audio = record(note.id, 10).copy(phase = RecordingPhase.Interrupted, problem = RecordingProblem.ProcessEnded)
        val input = store.create(note, listOf(audio))
        store.prepare(input.id)
        val files = export(input.id)
        val restored = NoteJson.decodeFromString<RecordingSession>(files.getValue("recordings/${audio.id}/manifest.json").toString(Charsets.UTF_8))
        assertEquals(RecordingProblem.ProcessEnded, restored.problem)
        assertEquals(RecordingPhase.Interrupted, restored.phase)
    }

    @Test fun syntheticAudioLargerThanLegacyFourMbLimitIsNotTruncatedOrBufferedAsOneAttachment() {
        val note = mixedNote()
        val engine = RecordingEngine(recordings, noteId = note.id)
        val samples = ShortArray(32_000) { (it % 1000).toShort() }
        repeat(80) { engine.append(samples, samples.size) }
        engine.finish()
        val input = store.create(note, listOf(engine.session))
        val manifest = store.prepare(input.id)
        val audio = manifest.files.filter { it.kind == NoteMaterialKind.AudioChunk }
        assertTrue(audio.sumOf { it.sizeBytes } > 4 * 1024 * 1024)
        val files = export(input.id)
        assertEquals(engine.session.samples, audio.sumOf { (files.getValue(it.path).size - 44L) / 2 })
    }

    @Test fun destinationWriteFailureNeverDeletesVersionOrOriginals() {
        val note = mixedNote()
        val input = store.create(note, listOf(record(note.id, 10)))
        val manifest = store.prepare(input.id)
        assertThrows(IOException::class.java) { store.export(input.id, object : java.io.OutputStream() {
            override fun write(b: Int) { throw IOException("synthetic destination full") }
        }) }
        assertEquals(NoteMaterialPhase.Ready, store.entries().single().phase)
        assertEquals(manifest, store.prepare(input.id))
        assertEquals(note, notes.read(note.id))
        assertTrue(export(input.id).containsKey("note.json"))
    }

    @Test fun workerUsesPersistedInputNotCurrentEditorAndDoesNotStartMicrophoneOrNetwork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestPimApp>()
        val appNotes = NoteFileStore(File(app.noBackupFilesDir, "notes"))
        val original = NoteDocument.create().withTitle("Fixed A").nextRevision()
        appNotes.save(original)
        val appStore = NoteMaterialStore.at(app.noBackupFilesDir)
        val input = appStore.create(original, emptyList())
        appNotes.save(original.withTitle("Current B").nextRevision())
        val worker = TestListenableWorkerBuilder<NoteMaterialWorker>(app).setInputData(workDataOf(NoteMaterialWorker.VERSION_ID to input.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val bytes = ByteArrayOutputStream().also { appStore.export(input.id, it) }.toByteArray()
        assertTrue(bytes.isNotEmpty())
        assertEquals(original, appStore.read(input.id).document)
        assertEquals("Current B", appNotes.read(original.id).title)
        assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
    }

    @Test fun ownershipScanRefusesUnknownDamagedManifestInsteadOfDroppingItsAudio() {
        val app = ApplicationProvider.getApplicationContext<TestPimApp>()
        val id = newNoteId()
        File(app.noBackupFilesDir, "recordings/$id/manifest.json").apply { parentFile!!.mkdirs(); writeText("damaged") }
        val repository = RecordingRepository(app)
        assertThrows(IllegalArgumentException::class.java) { repository.materialSessions(newNoteId()) }
        assertEquals("damaged", File(app.noBackupFilesDir, "recordings/$id/manifest.json").readText())
    }

    private fun mixedNote(): NoteDocument {
        val note = NoteDocument.create()
        val photoBytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(20, 15, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        val photo = notes.importPhoto(note.id, photoBytes.inputStream())
        val ink = withInk(inkBlock(320f), InkData(strokes = listOf(InkStroke(listOf(InkPoint(10f, 20f), InkPoint(80f, 90f))))))
        return note.withBlocks(listOf(markdownBlock("# A\n\n**exact**\n"), photo, ink, markdownBlock("tail\n"))).nextRevision().also { notes.save(it) }
    }

    private fun record(noteId: String, count: Int, chunk: Long = 100, segment: Long = 100): RecordingSession {
        val engine = RecordingEngine(recordings, RecordingPolicy(chunk, segment, 2), noteId = noteId)
        engine.append(ShortArray(count) { (it * 33).toShort() }, count)
        engine.finish()
        return engine.session
    }

    private fun export(id: String): Map<String, ByteArray> {
        val output = ByteArrayOutputStream().also { store.export(id, it) }
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; assertFalse(files.containsKey(entry.name)); files[entry.name] = zip.readBytes() }
        }
        return files
    }
}

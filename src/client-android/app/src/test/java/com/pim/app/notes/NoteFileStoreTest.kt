package com.pim.app.notes

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.app.recording.RecordingEngine
import com.pim.app.recording.RecordingFileStore
import com.pim.app.recording.RecordingProblem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteFileStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val store get() = NoteFileStore(temp.root)

    @Test fun documentPackageContainsExactEditableSourceRelativePhotoAndRealInkPng() {
        var note = NoteDocument.create()
        val photo = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.BLUE); it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle() }
        }.toByteArray()
        val photoBlock = store.importPhoto(note.id, ByteArrayInputStream(photo))
        val ink = withInk(inkBlock(320f), InkData(strokes = listOf(InkStroke(listOf(InkPoint(30f, 40f), InkPoint(80f, 90f))))))
        note = note.withBlocks(listOf(markdownBlock("# 原稿\n\n**保留**\n"), photoBlock, ink, markdownBlock("结尾"))).nextRevision()
        store.save(note)
        assertEquals(note, store.read(note.id))
        File(temp.root, "${note.id}/${NoteFileStore.inkPath(ink)}").writeText("damaged derived preview")
        val zipBytes = ByteArrayOutputStream().also { store.exportDocument(note, it) }.toByteArray()
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        assertEquals(note, NoteDocument.decode(files.getValue("note.json").toString(Charsets.UTF_8)))
        val markdown = files.getValue("document.md").toString(Charsets.UTF_8)
        assertTrue(markdown.contains("# 原稿\n\n**保留**\n"))
        assertFalse(markdown.contains("no_backup")); assertFalse(markdown.contains("content://"))
        assertArrayEquals(photo, files.getValue(NoteFileStore.photoPath(photoBlock)))
        val png = files.getValue(NoteFileStore.inkPath(ink))
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull(bitmap); assertEquals(320, bitmap.width); assertEquals(240, bitmap.height)
        assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) != bitmap.getPixel(0, 0) } })
        bitmap.recycle()
        assertEquals(setOf("note.json", "document.md", NoteFileStore.photoPath(photoBlock), NoteFileStore.inkPath(ink)), files.keys)
    }

    @Test fun staleSaveIsRejectedAndExportOfOldSnapshotDoesNotReplaceNewSource() {
        val old = NoteDocument.create()
        val newer = old.withTitle("新稿").nextRevision()
        store.save(old); store.save(newer)
        assertThrows(IllegalArgumentException::class.java) { store.save(old) }
        store.exportDocument(old, ByteArrayOutputStream())
        assertEquals(newer, store.read(old.id))
    }

    @Test fun interruptedAtomicWriteKeepsLastCompleteDocument() {
        val note = NoteDocument.create()
        store.save(note)
        val atomic = AtomicFile(File(temp.root, "${note.id}/note.json"))
        val stream = atomic.startWrite()
        stream.write("{broken".toByteArray()); stream.close()
        assertEquals(note, store.read(note.id))
    }

    @Test fun damagedManifestRemainsVisibleAndIsNeverReplacedWithBlank() {
        val id = newNoteId()
        val file = File(temp.root, "$id/note.json").apply { parentFile!!.mkdirs(); writeText("broken") }
        assertTrue(store.entries().single().damaged)
        assertEquals("broken", file.readText())
    }

    @Test fun missingPhotoBlocksCompleteExportAndOriginalAssociationIsRetained() {
        val block = buildJsonObject { put("id", newNoteId()); put("kind", "photo"); put("assetId", newNoteId()) }
        val note = NoteDocument.create().withBlocks(listOf(block))
        store.save(note)
        assertThrows(IllegalArgumentException::class.java) { store.exportDocument(note, ByteArrayOutputStream()) }
        assertEquals(block, store.read(note.id).blocks.single())
    }

    @Test fun unknownExtensionIsPreservedButCannotBeFalselyExportedAsComplete() {
        val block = buildJsonObject { put("id", newNoteId()); put("kind", "future"); put("material", "opaque-reference") }
        val note = NoteDocument.create().withBlocks(listOf(block))
        store.save(note)
        assertEquals(note, store.read(note.id))
        assertThrows(IllegalArgumentException::class.java) { store.exportDocument(note, ByteArrayOutputStream()) }
    }

    @Test fun invalidPhotoDoesNotMutateDocumentOrPublishPlaceholder() {
        val note = NoteDocument.create()
        store.save(note)
        assertThrows(IllegalArgumentException::class.java) { store.importPhoto(note.id, ByteArrayInputStream("not an image".toByteArray())) }
        assertEquals(note, store.read(note.id))
        assertThrows(IllegalArgumentException::class.java) { store.photoFile(note.id, "../../private") }
    }

    @Test fun privateCameraProviderCannotExposeNotesAudioOrDiagnosticFiles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val authority = "${app.packageName}.note-camera"
        val uri = FileProvider.getUriForFile(app, authority, File(app.cacheDir, "note-camera/${newNoteId()}.jpg"))
        assertEquals("content", uri.scheme)
        for (file in listOf(File(app.noBackupFilesDir, "notes/raw.json"), File(app.noBackupFilesDir, "recordings/raw.wav"), File(app.filesDir, "diagnostics/exports/private.zip"))) {
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(app, authority, file) }
        }
        val provider = app.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
    }

    @Test fun recordingOwnershipSurvivesColdRecoveryAndDoesNotMigrateToNewNote() {
        val noteId = newNoteId()
        val recordingStore = RecordingFileStore(temp.newFolder())
        val engine = RecordingEngine(recordingStore, noteId = noteId)
        engine.append(shortArrayOf(100, 200), 2)
        val recovered = recordingStore.archives(recover = true).single().session!!
        assertEquals(noteId, recovered.noteId)
        assertEquals(RecordingProblem.ProcessEnded, recovered.problem)
        assertNotEquals(newNoteId(), recovered.noteId)
        engine.finish(null)
    }

    @Test fun backgroundSaverPersistsLatestRevisionWithoutStartingAnyService() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = NoteRepository(app)
        repository.initialize()
        val id = repository.create()
        repeat(30) { n -> repository.update(id) { it.withTitle("修订 $n") } }
        withTimeout(10_000) { repository.state.first { it.savedRevisions[id] == it.documents[id]?.revision } }
        val saved = NoteFileStore(File(app.noBackupFilesDir, "notes")).read(id)
        assertEquals("修订 29", saved.title)
        assertEquals(30L, saved.revision)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun coldPhotoResultWaitsForDocumentLoadRatherThanReportingAnUntrackedInsert() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = NoteRepository(app)
        assertThrows(IllegalStateException::class.java) { repository.update(newNoteId()) { it } }
        val note = NoteDocument.create()
        NoteFileStore(File(app.noBackupFilesDir, "notes")).save(note)
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        assertFalse(repository.state.value.loaded)
        repository.insertPhoto(note.id, NoteInsertion(note.blocks.single().string("id"), 0)) { ByteArrayInputStream(bytes) }
        withTimeout(10_000) { repository.state.first { it.savedRevisions[note.id] == it.documents[note.id]?.revision } }
        assertEquals("photo", NoteFileStore(File(app.noBackupFilesDir, "notes")).read(note.id).blocks[1].string("kind"))
    }

    @Test fun changedPhotoBytesBlockCompleteExportEvenWhenFileStillExists() {
        val note = NoteDocument.create()
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        val photo = store.importPhoto(note.id, ByteArrayInputStream(bytes))
        val attached = note.withBlocks(listOf(photo))
        store.save(attached)
        store.photoFile(note.id, photo.string("assetId")).appendText("tampered")
        assertThrows(IllegalArgumentException::class.java) { store.exportDocument(attached, ByteArrayOutputStream()) }
        assertEquals(attached, store.read(note.id))
    }
}

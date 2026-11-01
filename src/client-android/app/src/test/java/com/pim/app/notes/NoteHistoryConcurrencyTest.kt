package com.pim.app.notes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteHistoryConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun oversizedSnapshotIsRejectedInsteadOfClaimingABoundedHistory() {
        val note = NoteDocument.create().withBlocks(
            listOf(markdownBlock("x".repeat(NoteEditHistory.MAX_SNAPSHOT_BYTES + 1)))
        )

        assertTrue(!NoteEditHistory.canStore(note))
        assertTrue(NoteEditHistory.initialOrNull(note) == null)
        val rejected = runCatching { NoteEditHistory.initial(note) }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun encodedJournalReadStopsAtTheBudgetAndPreservesTheOriginalFile() {
        val root = temp.newFolder(".edit-history")
        val note = NoteDocument.create()
        val journal = File(root, "${note.id}.json").apply {
            writeBytes(ByteArray(NoteEditHistory.MAX_ENCODED_BYTES + 1) { 'x'.code.toByte() })
        }

        val rejected = runCatching { NoteEditHistoryStore(root).read(note.id) }

        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
        assertTrue(rejected.exceptionOrNull()?.message.orEmpty().contains("bounded read budget"))
        assertEquals(NoteEditHistory.MAX_ENCODED_BYTES + 1L, journal.length())
    }

    @Test fun checkpointPersistsHistoryAtTheSameNoteRevision() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notesRoot = File(app.noBackupFilesDir, "notes")
        val repository = NoteRepository(app)
        repository.initialize()
        val id = repository.create()
        repository.update(id) { it.withTitle("绑定同一修订").withBlocks(listOf(markdownBlock("正文"))) }

        val checkpointed = repository.checkpoint(id)
        val saved = NoteFileStore(notesRoot).read(id)
        val history = NoteEditHistoryStore(File(notesRoot, ".edit-history")).read(id)!!

        assertEquals(checkpointed.revision, saved.revision)
        assertEquals(saved.revision, history.revision)
        assertEquals(JsonObject(saved.source - "revision"), history.current())
    }

    @Test fun importAndCheckpointRaceCannotLoseTheEditedNoteRevision() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notesRoot = File(app.noBackupFilesDir, "notes")
        val repository = NoteRepository(app)
        repository.initialize()
        val sourceId = repository.create()
        repository.update(sourceId) { it.withTitle("导入前") }
        repository.checkpoint(sourceId)
        val packageBytes = ByteArrayOutputStream().also {
            NoteFileStore(notesRoot).exportDocument(repository.state.value.documents.getValue(sourceId), it)
        }.toByteArray()

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delayed = object : FilterInputStream(ByteArrayInputStream(packageBytes)) {
            private fun waitOnce() {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting to release import" }
            }

            override fun read(): Int {
                waitOnce()
                return super.read()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                waitOnce()
                return super.read(bytes, offset, length)
            }
        }

        val importing = async(Dispatchers.IO) { repository.importDocument { delayed } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        repeat(20) { index -> repository.update(sourceId) { it.withTitle("并发修订 $index") } }
        val saved = repository.checkpoint(sourceId)
        release.countDown()
        val imported = importing.await()

        assertNotEquals(sourceId, imported.id)
        assertEquals("并发修订 19", repository.state.value.documents.getValue(sourceId).title)
        assertEquals(saved, NoteFileStore(notesRoot).read(sourceId))
        val history = NoteEditHistoryStore(File(notesRoot, ".edit-history")).read(sourceId)!!
        assertEquals(saved.revision, history.revision)
        assertEquals(JsonObject(saved.source - "revision"), history.current())
        assertEquals(imported, repository.state.value.documents.getValue(imported.id))
    }
}

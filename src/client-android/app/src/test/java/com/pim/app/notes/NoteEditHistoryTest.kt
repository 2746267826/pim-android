package com.pim.app.notes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.pim.app.TestPimApp::class)
class NoteEditHistoryTest {
    @Test fun typingBurstCoalescesAndStillRestoresTheWholeBlock() {
        val initial = NoteDocument.create()
        val blockId = initial.blocks.single().string("id")
        var history = NoteEditHistory.initial(initial)
        var current = initial
        repeat(40) { index ->
            val text = "长文字".repeat(index + 1)
            current = current.withBlocks(listOf(JsonObject(current.blocks.single() + ("text" to JsonPrimitive(text)))))
                .nextRevision()
            history = history.record(current, "markdown:$blockId", 1_000L + index)
        }

        assertEquals("one typing burst is one reviewable document action", 1, history.cursor)
        assertEquals(2, history.snapshots.size)
        val undone = current.withHistoryContent(history.movedTo(0).current())
        assertEquals("", undone.blocks.single().string("text"))
        assertEquals(current.revision + 1, undone.revision)
        val redone = undone.withHistoryContent(history.movedTo(1).current())
        assertEquals(current.blocks.single().string("text"), redone.blocks.single().string("text"))
    }

    @Test fun historyIsBoundedByBothCountAndEncodedBytes() {
        var current = NoteDocument.create()
        var history = NoteEditHistory.initial(current)
        repeat(100) { index ->
            current = current.withBlocks(
                current.blocks + JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(newNoteId()),
                        "kind" to JsonPrimitive("markdown"),
                        "text" to JsonPrimitive("段 $index ".repeat(200)),
                    )
                )
            ).nextRevision()
            history = history.record(current, "document", 1_000L + index * 2_000L)
        }

        assertTrue(history.snapshots.size <= NoteEditHistory.MAX_SNAPSHOTS)
        assertTrue(
            history.snapshots.sumOf { it.toByteArray(Charsets.UTF_8).size } <=
                NoteEditHistory.MAX_HISTORY_BYTES
        )
        assertEquals(history.snapshots.lastIndex, history.cursor)
    }

    @Test fun wholeDocumentUndoRedoSurvivesRepositoryRecreationAndCrossesBlockModes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = NoteRepository(app)
        repository.initialize()
        val id = repository.create()
        val original = repository.state.value.documents.getValue(id)
        val photo = JsonObject(
            mapOf(
                "id" to JsonPrimitive(newNoteId()),
                "kind" to JsonPrimitive("photo"),
                "assetId" to JsonPrimitive(newNoteId()),
            )
        )
        val ink = inkBlock(420f)
        repository.update(id) {
            it.withTitle("跨模式修订").withBlocks(listOf(markdownBlock("正文\n"), photo, ink, markdownBlock("结尾")))
        }
        val changed = repository.state.value.documents.getValue(id)
        repository.checkpoint(id)

        val reopened = NoteRepository(app)
        reopened.initialize()
        assertTrue(reopened.state.value.editHistory.getValue(id).canUndo)
        assertTrue(reopened.undo(id))
        val undone = reopened.state.value.documents.getValue(id)
        assertEquals("未命名记录", undone.title)
        assertEquals(original.blocks, undone.blocks)
        assertTrue(reopened.state.value.editHistory.getValue(id).canRedo)

        assertTrue(reopened.redo(id))
        val redone = reopened.state.value.documents.getValue(id)
        assertEquals("跨模式修订", redone.title)
        assertEquals(changed.blocks, redone.blocks)
        assertEquals(changed.blocks.filter { it.string("kind") == "ink" }.single(), ink)
    }

    @Test fun newEditAfterUndoClearsRedoAndCorruptJournalFallsBackWithoutOpeningNoteLoss() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = NoteRepository(app)
        repository.initialize()
        val id = repository.create()
        repository.update(id) { it.withTitle("A") }
        // Rapid same-field edits intentionally coalesce into one reviewable action.
        Thread.sleep(NoteEditHistory.COALESCE_MILLIS + 100)
        repository.update(id) { it.withTitle("B") }
        assertTrue(repository.undo(id))
        assertEquals("A", repository.state.value.documents.getValue(id).title)
        repository.update(id) { it.withTitle("C") }
        assertFalse(repository.state.value.editHistory.getValue(id).canRedo)

        val notesRoot = File(app.noBackupFilesDir, "notes")
        val damagedId = newNoteId()
        val damaged = NoteDocument.create().let { note ->
            note.copy(source = JsonObject(note.source + ("id" to JsonPrimitive(damagedId))))
        }
        NoteFileStore(notesRoot).save(damaged)
        File(notesRoot, ".edit-history/$damagedId.json").apply {
            parentFile!!.mkdirs()
            writeText("damaged journal, retain me")
        }
        val reopened = NoteRepository(app)
        reopened.initialize()
        assertEquals(damagedId, reopened.state.value.documents.getValue(damagedId).id)
        assertEquals(NoteHistoryAvailability(), reopened.state.value.editHistory.getValue(damagedId))
        assertEquals("damaged journal, retain me", File(notesRoot, ".edit-history/$damagedId.json").readText())
    }
}

package com.pim.app.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class NativeNotesA01BehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<NativeNotesActivity>()
    private val model get() = compose.activity.model
    private var syntheticSession: String? = null

    @After fun finishSyntheticRecordingOnly() {
        val id = syntheticSession ?: return
        compose.runOnUiThread {
            if (model.recordings.state.value.session?.id == id) model.recordings.finish()
        }
        compose.waitUntil(5_000) { !model.recordings.state.value.active }
        syntheticSession = null
    }

    @Test fun repeatedCreateOpensOneEditorAndNeverStartsRecording() {
        compose.waitUntil(15_000) {
            model.notes.state.value.loaded && model.materials.state.value.loaded
        }
        val beforeIds = model.notes.state.value.entries.map { it.id }.toSet()
        val beforePreparing = model.recordings.state.value.preparing
        val beforeSession = model.recordings.state.value.session?.id
        val beforeArchives = model.recordings.state.value.archives.size
        var first: String? = null
        var second: String? = null

        compose.runOnUiThread {
            first = model.create()
            second = model.create()
        }

        compose.waitUntil(5_000) { model.selectedId.value == first }
        assertNotNull(first)
        assertNull(second)
        assertEquals(beforeIds + first!!, model.notes.state.value.entries.map { it.id }.toSet())
        assertEquals(beforePreparing, model.recordings.state.value.preparing)
        assertEquals(beforeSession, model.recordings.state.value.session?.id)
        assertEquals(beforeArchives, model.recordings.state.value.archives.size)

        val selected = first!!
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5_000) { model.selectedId.value == selected }
        assertEquals(beforeIds + selected, model.notes.state.value.entries.map { it.id }.toSet())
        assertEquals(beforePreparing, model.recordings.state.value.preparing)
        assertEquals(beforeSession, model.recordings.state.value.session?.id)
        assertEquals(beforeArchives, model.recordings.state.value.archives.size)
    }

    @Test fun missingSelectionDoesNotBlockCreatingARecoveryNote() {
        compose.waitUntil(15_000) {
            model.notes.state.value.loaded && model.materials.state.value.loaded
        }
        val missingId = java.util.UUID.randomUUID().toString()
        assertFalse(model.notes.state.value.documents.containsKey(missingId))
        compose.runOnUiThread { model.open(missingId) }
        compose.waitUntil(5_000) { model.selectedId.value == missingId }

        var created: String? = null
        compose.runOnUiThread { created = model.create() }
        compose.waitUntil(5_000) { created != null && model.selectedId.value == created }

        val newId = checkNotNull(created)
        assertNotEquals(missingId, newId)
        assertTrue(model.notes.state.value.documents.containsKey(newId))
    }

    @Test fun anotherActiveNoteOffersChoiceAndKeepsItsSessionOwnership() {
        compose.waitUntil(15_000) {
            model.notes.state.value.loaded && model.materials.state.value.loaded
        }
        var first: String? = null
        compose.runOnUiThread { first = model.create() }
        compose.waitUntil(5_000) { model.selectedId.value == first }
        val originalId = checkNotNull(first)
        compose.runOnUiThread {
            model.notes.update(originalId) { it.withTitle("原录音笔记") }
        }
        compose.waitUntil(5_000) {
            val note = model.notes.state.value.documents[originalId]
            note != null && model.notes.state.value.savedRevisions[originalId] == note.revision
        }
        compose.runOnUiThread { model.recordings.start(originalId) }
        compose.waitUntil(5_000) { model.recordings.state.value.active }
        val sessionId = checkNotNull(model.recordings.state.value.session).id
        syntheticSession = sessionId

        compose.onNodeWithContentDescription("返回记录列表").performClick()
        compose.waitUntil(5_000) { model.selectedId.value == null }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5_000) {
            model.selectedId.value != null && model.selectedId.value != originalId
        }
        val secondId = checkNotNull(model.selectedId.value)
        assertNotEquals(originalId, secondId)
        compose.waitUntil(5_000) {
            val note = model.notes.state.value.documents[secondId]
            note != null && model.notes.state.value.savedRevisions[secondId] == note.revision
        }

        compose.onNodeWithTag("note-start-recording")
            .assertTextContains("原录音笔记", substring = true)
            .performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("recording-conflict-open").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("《原录音笔记》仍在录音", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-conflict-continue").performClick()
        assertEquals(sessionId, model.recordings.state.value.session?.id)
        assertEquals(originalId, model.recordings.state.value.session?.noteId)
        assertFalse(model.recordings.state.value.preparing)

        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("recording-conflict-open").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("recording-conflict-open").performClick()
        compose.waitUntil(5_000) { model.selectedId.value == originalId }
        assertEquals(sessionId, model.recordings.state.value.session?.id)
        assertEquals(originalId, model.recordings.state.value.session?.noteId)
    }
}

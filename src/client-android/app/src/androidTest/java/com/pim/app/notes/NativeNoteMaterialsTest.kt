package com.pim.app.notes

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.rule.GrantPermissionRule
import com.pim.app.recording.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class NativeNoteMaterialsTest {
    private val compose = createAndroidComposeRule<NativeNotesActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*(
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    ))).around(compose)
    private val model get() = compose.activity.model
    private fun note() = model.notes.state.value.documents.getValue(model.selectedId.value!!)
    private val store get() = NoteMaterialStore.at(compose.activity.noBackupFilesDir)

    @After fun finishThisTestRecording() {
        model.recordings.state.value.session?.takeIf { it.active }?.let { session ->
            RecordingController.finish(compose.activity, session.id)
            compose.waitUntil(10_000) { !model.recordings.state.value.active }
        }
    }

    @Test fun realMultipleRecordingsFreezeAllAudioThenEditingAndSupplementaryCaptureCreateNewVersion() {
        create()
        val id = note().id
        val first = capture()
        RecordingController.finish(compose.activity, first)
        compose.waitUntil(10_000) { !model.recordings.state.value.active }
        val second = capture()
        finishNote()
        val versionA = ready(id, 1).single()
        val inputA = store.read(versionA.id)
        assertEquals(setOf(first, second), inputA.recordings.map { it.id }.toSet())
        assertTrue(inputA.recordings.all { it.samples > 0 && !it.active })
        assertEquals(2, versionA.recordingCount)
        val exportedA = exportedFiles(versionA.id)
        val exactA = inputA.document
        assertEquals(exactA, NoteDocument.decode(exportedA.getValue("note.json").toString(Charsets.UTF_8)))
        assertEquals(inputA.recordings.sumOf { it.chunks.size }, exportedA.keys.count { it.endsWith(".wav") })

        compose.onNodeWithTag("note-title").performScrollTo().performTextReplacement("Synthetic B, after fixed A")
        closeSoftKeyboard(); saved()
        compose.onNodeWithTag("note-material-status").assertTextContains("有新修改", substring = true)
        val third = capture()
        finishNote()
        val versions = ready(id, 2)
        val versionB = versions.single { it.id != versionA.id }
        assertEquals(setOf(first, second, third), store.read(versionB.id).recordings.map { it.id }.toSet())
        assertEquals(exactA, store.read(versionA.id).document)
        val stillA = exportedFiles(versionA.id)
        exportedA.forEach { (path, bytes) -> assertArrayEquals(path, bytes, stillA.getValue(path)) }
        assertEquals(3, versionB.recordingCount)
        assertFalse(model.recordings.state.value.active)
    }

    @Test fun failedOriginalPhotoPreparationRetriesSameVersionAfterNewerEditing() {
        create()
        val id = note().id
        val photoBytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(20, 15, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        compose.runOnUiThread {
            model.prepareMaterial(id, NoteInsertion(note().blocks.single().string("id"), 0))
            model.importPhoto({ photoBytes.inputStream() })
        }
        compose.waitUntil(10_000) { !model.busy.value && note().blocks.any { it.string("kind") == "photo" } }
        saved()
        val photo = note().blocks.single { it.string("kind") == "photo" }
        val source = model.notes.photoFile(id, photo.string("assetId"))
        assertTrue(source.delete()) // Deliberate fault in this synthetic test note only.
        finishNote()
        compose.waitUntil(20_000) { model.materials.state.value.entries.any { it.noteId == id && it.phase == NoteMaterialPhase.Failed } }
        val failed = model.materials.state.value.entries.single { it.noteId == id }
        val fixed = store.read(failed.id)
        assertEquals(NoteMaterialProblem.Incomplete, failed.problem)
        compose.onNodeWithTag("note-title").performScrollTo().performTextReplacement("Newer title is not retry input")
        closeSoftKeyboard(); saved()
        source.writeBytes(photoBytes)
        compose.onNodeWithTag("note-material-status").performClick()
        compose.onNodeWithTag("material-retry-${failed.id}").performScrollTo().performClick()
        val version = ready(id, 1).single()
        assertEquals(failed.id, version.id)
        assertEquals(fixed, store.read(version.id))
        assertEquals(fixed.document, NoteDocument.decode(exportedFiles(version.id).getValue("note.json").toString(Charsets.UTF_8)))
        assertEquals("Newer title is not retry input", note().title)
        assertFalse(model.recordings.state.value.active)
        compose.onNodeWithText("返回编辑").performScrollTo().performClick()
    }

    @Test fun duplicateFinishAndActivityRecreationKeepOneVersionAndExportSelectionRemainsPinned() {
        create()
        val id = note().id
        finishNote()
        val first = ready(id, 1).single()
        finishNote()
        compose.waitUntil(10_000) { !model.busy.value }
        assertEquals(listOf(first.id), model.materials.state.value.entries.filter { it.noteId == id }.map { it.id })
        compose.runOnUiThread { model.prepareMaterialExport(first.id) }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { model.selectedId.value == id && model.materials.state.value.loaded }
        compose.onNodeWithTag("note-title").performScrollTo().performTextReplacement("Still editable B")
        closeSoftKeyboard(); saved()
        var exportId: String? = null
        compose.runOnUiThread { exportId = model.consumeMaterialExport() }
        assertEquals(first.id, exportId)
        val target = File(compose.activity.cacheDir, "synthetic-material-test-${first.id}.zip")
        compose.runOnUiThread { model.exportMaterial(exportId!!) { target.outputStream() } }
        compose.waitUntil(20_000) { !model.busy.value && model.message.value?.contains("固定材料包已导出") == true }
        assertTrue(target.isFile && target.length() > 0)
        assertEquals(store.read(first.id).document, NoteDocument.decode(exportedFiles(first.id).getValue("note.json").toString(Charsets.UTF_8)))
        assertEquals("Still editable B", note().title)
        assertFalse(model.recordings.state.value.active)
    }

    private fun create() {
        compose.waitUntil(15_000) { model.notes.state.value.loaded && model.materials.state.value.loaded }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5000) { model.selectedId.value != null }
        compose.onNodeWithTag("note-title").performTextReplacement("Synthetic materials A")
        closeSoftKeyboard(); saved()
    }

    private fun capture(): String {
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) >= 3200 }
        return model.recordings.state.value.session!!.id
    }

    private fun ready(noteId: String, count: Int): List<NoteMaterialEntry> {
        compose.waitUntil(30_000) {
            !model.busy.value && model.materials.state.value.entries.count { it.noteId == noteId && it.phase == NoteMaterialPhase.Ready } == count
        }
        return model.materials.state.value.entries.filter { it.noteId == noteId }
    }

    private fun saved() = compose.waitUntil(15_000) { model.notes.state.value.savedRevisions[note().id] == note().revision }

    private fun finishNote() {
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-confirm-finish").performClick()
        compose.waitUntil(15_000) { model.completion.value != null && !model.busy.value }
        compose.onNodeWithTag("note-continue-editing").performClick()
    }

    private fun exportedFiles(id: String): Map<String, ByteArray> {
        val bytes = ByteArrayOutputStream().also { store.export(id, it) }.toByteArray()
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        val manifest = NoteJson.parseToJsonElement(files.getValue("materials.json").toString(Charsets.UTF_8)).jsonObject
        assertEquals(id, manifest.string("versionId"))
        return files
    }
}

package com.pim.app.notes

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.recording.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class NativeNotesDocumentTest {
    private val compose = createAndroidComposeRule<NativeNotesActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*(
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    ))).around(compose)
    private val model get() = compose.activity.model
    private fun note(): NoteDocument = model.notes.state.value.documents.getValue(model.selectedId.value!!)
    private fun saved() = compose.waitUntil(15_000) { model.notes.state.value.savedRevisions[note().id] == note().revision }

    @After fun finishOnlyThisTestRecording() {
        model.recordings.state.value.session?.takeIf { it.active }?.let {
            RecordingController.finish(compose.activity, it.id)
            compose.waitUntil(10_000) { !model.recordings.state.value.active }
        }
    }

    @Test fun caretInsertionRealTouchInkResizeAndReopenPreserveEditableSource() {
        create()
        val text = "# Session\n\nbefore\nafter"
        edit(text, text.indexOf("after"))
        assertFalse(model.recordings.state.value.active)
        compose.onNodeWithTag("note-insert-menu").performClick()
        compose.onNodeWithTag("note-insert-ink").performClick()
        val inkId = note().blocks.single { it.string("kind") == "ink" }.string("id")
        assertEquals("# Session\n\nbefore\n", note().blocks[0].string("text"))
        assertEquals("after", note().blocks[2].string("text"))
        compose.onNodeWithTag("ink-draw-$inkId").performScrollTo().performClick()
        compose.onNodeWithTag("ink-canvas-$inkId").performTouchInput {
            down(Offset(30f, 30f)); moveTo(Offset(160f, 100f), 100); up()
        }
        compose.waitUntil(5000) { inkOf(note().blocks[1]).strokes.isNotEmpty() }
        val strokes = inkOf(note().blocks[1]).strokes
        compose.onNodeWithTag("ink-resize-$inkId").performScrollTo().performTouchInput {
            down(center); moveBy(Offset(90f, 120f), 100); up()
        }
        compose.waitUntil(5000) { inkOf(note().blocks[1]).height > 240f }
        val resized = inkOf(note().blocks[1])
        assertTrue(resized.width > 320f)
        assertEquals(strokes, resized.strokes)
        saved()
        val snapshot = note()
        assertEquals(snapshot, NoteFileStore(File(compose.activity.noBackupFilesDir, "notes")).read(snapshot.id))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { model.selectedId.value == snapshot.id }
        assertEquals(snapshot, note())
        compose.onNodeWithTag("note-preview").performClick()
        compose.onNodeWithTag("note-preview").performClick()
        assertEquals(snapshot, note())
        compose.onNodeWithContentDescription("返回记录列表").performClick()
        compose.onNodeWithTag("note-open-${snapshot.id}").performScrollTo().performClick()
        assertEquals(snapshot, note())
    }

    @Test fun imageResultImportsRealBytesAtSavedAnchorAndFailedImageDoesNotAddBlock() {
        create()
        edit("beforeafter", 6)
        val sourceId = note().blocks.single().string("id")
        val bytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }.toByteArray()
        compose.runOnUiThread {
            model.prepareMaterial(note().id, NoteInsertion(sourceId, 6))
            model.importPhoto({ ByteArrayInputStream(bytes) })
        }
        compose.waitUntil(10_000) { !model.busy.value && note().blocks.size == 3 }
        assertEquals("before", note().blocks[0].string("text"))
        assertEquals("after", note().blocks[2].string("text"))
        val photo = note().blocks[1]
        val file = model.notes.photoFile(note().id, photo.string("assetId"))
        assertArrayEquals(bytes, file.readBytes())
        assertNotNull(NoteFileStore.photoPreview(file))
        saved()
        val before = note()
        compose.runOnUiThread {
            model.prepareMaterial(note().id, NoteInsertion(sourceId, 0))
            model.importPhoto({ ByteArrayInputStream("invalid".toByteArray()) })
        }
        compose.waitUntil(10_000) { !model.busy.value && model.message.value?.contains("导入失败") == true }
        assertEquals(before, note())
        assertTrue(file.exists())
    }

    @Test fun realRecordingRemainsOwnedByOriginalNoteDuringEditingNavigationAndRecreation() {
        create(); saved()
        val firstId = note().id
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) > 0 }
        assertEquals(firstId, model.recordings.state.value.session!!.noteId)
        edit("# Edited during capture", 5)
        saved()
        val samples = model.recordings.state.value.session!!.samples
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { model.selectedId.value == firstId }
        SystemClock.sleep(300)
        assertTrue(model.recordings.state.value.session!!.samples > samples)
        compose.onNodeWithContentDescription("返回记录列表").performClick()
        create(); saved()
        val otherId = note().id
        assertNotEquals(firstId, otherId)
        assertEquals(firstId, model.recordings.state.value.session!!.noteId)
        compose.onNodeWithTag("note-start-recording").assertIsNotEnabled()
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.id == RecordingNotifications.ID }.notification
        notification.contentIntent.send()
        compose.waitUntil(5000) { model.selectedId.value == firstId }
        finishNote()
        compose.waitUntil(10_000) { !model.recordings.state.value.active && !model.busy.value }
        saved()
        assertEquals(RecordingPhase.Completed, model.recordings.state.value.session!!.phase)
        assertEquals(firstId, model.recordings.state.value.session!!.noteId)
        assertTrue(note().source.containsKey("endedAtMillis"))
        assertTrue(model.notes.state.value.documents.containsKey(otherId))
    }

    @Test fun emulatedSystemCameraCaptureAndCancellationCoexistWithRealRecording() {
        create(); closeSoftKeyboard(); saved()
        val id = note().id
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) > 0 }
        val before = model.recordings.state.value.session!!.samples
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            compose.onNodeWithTag("note-insert-menu").performClick()
            compose.onNodeWithText("拍照").performClick()
            cameraNode("shutter_button").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            cameraNode("done_button").performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            compose.waitUntil(20_000) { !model.busy.value && note().blocks.any { it.string("kind") == "photo" } }
            val photo = note().blocks.single { it.string("kind") == "photo" }
            val file = model.notes.photoFile(id, photo.string("assetId"))
            assertTrue(file.length() > 0)
            assertNotNull(NoteFileStore.photoPreview(file))
            assertTrue(model.recordings.state.value.active)
            assertEquals(id, model.recordings.state.value.session!!.noteId)
            assertTrue(model.recordings.state.value.session!!.samples > before)
            saved()
            val count = note().blocks.size
            compose.onNodeWithTag("note-insert-menu").performClick()
            compose.onNodeWithText("拍照").performClick()
            cameraNode("shutter_button")
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(10_000) { model.message.value?.contains("拍照已取消") == true }
            assertEquals(count, note().blocks.size)
            finishNote()
            compose.waitUntil(10_000) { !model.recordings.state.value.active && !model.busy.value }
            saved()
            assertEquals(RecordingPhase.Completed, model.recordings.state.value.session!!.phase)
        } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags } }
    }

    private fun cameraNode(id: String): android.view.accessibility.AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = root?.findAccessibilityNodeInfosByViewId("com.android.camera2:id/$id")?.firstOrNull { it.isEnabled && it.isVisibleToUser }
            if (node != null) return node
            SystemClock.sleep(100)
        }
        error("Dedicated emulator camera control not found: $id")
    }

    private fun create() {
        compose.waitUntil(10_000) { model.notes.state.value.loaded }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5000) { model.selectedId.value != null }
        compose.onNodeWithTag("note-title").performTextReplacement("Isolated synthetic lesson")
    }

    private fun edit(text: String, offset: Int) {
        val mode = "markdown-mode-${note().blocks.first { it.string("kind") == "markdown" }.string("id")}"
        if (compose.onAllNodes(hasTestTag(mode) and hasText("Markdown 源码")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(mode).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(mode) and hasText("富文本")).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.runOnUiThread {
            val editor = descendants(compose.activity.window.decorView).filterIsInstance<MarkdownEditText>().first()
            editor.requestFocus(); editor.setText(text); editor.setSelection(offset)
        }
        compose.waitForIdle(); closeSoftKeyboard(); compose.waitForIdle()
    }
    private fun finishNote() {
        compose.onNodeWithTag("note-finish").performClick()
        compose.onNodeWithTag("note-confirm-finish").performClick()
        compose.waitUntil(15_000) { model.completion.value != null && !model.busy.value }
        compose.onNodeWithTag("note-continue-editing").performClick()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

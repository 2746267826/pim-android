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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import com.pim.app.RequiresDevice

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

    @RequiresDevice
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
        openMaterial(inkId)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-material-sheet").fetchSemanticsNodes().isNotEmpty() }
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
        compose.onNodeWithText("关闭材料").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("note-material-sheet").fetchSemanticsNodes().isEmpty() }
        saved()
        val snapshot = note()
        assertEquals(snapshot, NoteFileStore(File(compose.activity.noBackupFilesDir, "notes")).read(snapshot.id))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(5000) { model.selectedId.value == snapshot.id }
        assertEquals(snapshot, note())
        compose.onNodeWithTag("note-preview").performClick()
        awaitPreview("编辑笔记")
        compose.onNodeWithTag("note-preview").performClick()
        awaitPreview("预览笔记")
        assertEquals(snapshot, note())
        compose.onNodeWithContentDescription("返回记录列表").performClick()
        compose.waitUntil(5000) { model.selectedId.value == null }
        compose.onNodeWithTag("notes-library").performScrollToNode(hasTestTag("note-open-${snapshot.id}"))
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
        openMaterial(photo.string("id"))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-material-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("照片材料").assertIsDisplayed()
        compose.onNodeWithText("关闭材料").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("note-material-sheet").fetchSemanticsNodes().isEmpty() }
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

    @Test fun oneContinuousEditorEditsBothSidesOfNativeMaterialWithoutChangingIt() {
        create()
        closeSoftKeyboard()
        compose.runOnUiThread {
            model.notes.update(note().id) {
                it.withBlocks(listOf(markdownBlock("前段"), inkBlock(320f), markdownBlock("后段")))
            }
        }
        saved()
        compose.onNodeWithTag("note-document").performScrollToIndex(2)
        compose.waitUntil(15_000) { webOrNull() != null }
        compose.waitUntil(15_000) { eval("Boolean(document.querySelector('.tiptap'))") == "true" }
        assertEquals("true", eval(
            "document.querySelectorAll('.tiptap').length===1 && document.querySelectorAll('.pim-region').length===2 && Boolean(document.querySelector('[data-pim-material]'))"
        ))
        val material = note().blocks[1]
        eval("""
            const region=document.querySelectorAll('.pim-region')[1];
            const range=document.createRange(); range.selectNodeContents(region.querySelector('p')); range.collapse(false);
            const selection=window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
            document.querySelector('.tiptap').focus();
            document.execCommand('insertText', false, '。追加'); true
        """.trimIndent())
        compose.waitUntil(10_000) { note().blocks[2].string("text").contains("追加") }
        assertEquals("前段", note().blocks[0].string("text"))
        assertEquals(material, note().blocks[1])
        saved()
    }

    @RequiresDevice
    @Test fun realRecordingRemainsOwnedByOriginalNoteDuringEditingNavigationAndRecreation() {
        create(); saved()
        val firstId = note().id
        compose.onNodeWithTag("note-title").performTextReplacement("原录音笔记")
        closeSoftKeyboard(); saved()
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) > 0 }
        val sessionId = model.recordings.state.value.session!!.id
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
        compose.onNodeWithTag("note-start-recording").assertIsEnabled().assertTextContains("原录音笔记", substring = true)
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recording-conflict-open").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("《原录音笔记》仍在录音", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("recording-conflict-continue").performClick()
        assertEquals(sessionId, model.recordings.state.value.session!!.id)
        assertFalse(model.recordings.state.value.preparing)
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recording-conflict-open").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("recording-conflict-open").performClick()
        compose.waitUntil(5_000) { model.selectedId.value == firstId }
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

    @RequiresDevice
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

    private fun awaitPreview(description: String) {
        try {
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("note-preview") and hasContentDescription(description) and isEnabled()).fetchSemanticsNodes().size == 1 }
        } catch (failure: Throwable) {
            println("Preview transition failed: busy=${model.busy.value}, message=${model.message.value}, finishing=${compose.activity.isFinishing}")
            println(compose.onRoot(useUnmergedTree = true).printToString().take(24_000))
            val folder = File(compose.activity.getExternalFilesDir(null), "native-note-upload-qa").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder, "document-preview-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw failure
        }
    }

    private fun create() {
        compose.waitUntil(10_000) { model.notes.state.value.loaded }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5000) { model.selectedId.value != null }
        compose.onNodeWithTag("note-title").performTextReplacement("Isolated synthetic lesson")
    }

    private fun edit(text: String, offset: Int) {
        compose.onNodeWithTag("note-document").performScrollToIndex(2)
        compose.waitUntil(15_000) { webOrNull() != null }
        compose.waitUntil(15_000) { eval("Boolean(document.querySelector('.tiptap'))") == "true" }
        val block = note().blocks.first { it.string("kind") == "markdown" }
        compose.runOnUiThread {
            model.notes.update(note().id) { doc ->
                doc.replaceBlock(block.string("id")) { JsonObject(it + ("text" to JsonPrimitive(text))) }
            }
        }
        compose.waitUntil(10_000) { note().blocks.first { it.string("kind") == "markdown" }.string("text") == text }
        compose.waitUntil(10_000) { eval("Boolean(document.querySelector('.tiptap'))") == "true" }
        eval("""
            const region=document.querySelector('.pim-region');
            const walker=document.createTreeWalker(region, NodeFilter.SHOW_TEXT);
            let remaining=$offset, node;
            while(node=walker.nextNode()) {
              if(remaining<=node.textContent.length) {
                const range=document.createRange(); range.setStart(node, remaining); range.collapse(true);
                const selection=window.getSelection(); selection.removeAllRanges(); selection.addRange(range); break;
              }
              remaining-=node.textContent.length;
            }
            document.querySelector('.tiptap').focus(); true
        """.trimIndent())
        compose.waitForIdle(); closeSoftKeyboard(); compose.waitForIdle()
    }
    private fun openMaterial(id: String) {
        compose.waitUntil(10_000) { eval("Boolean(document.querySelector('[data-pim-material=\"$id\"]'))") == "true" }
        eval("document.querySelector('[data-pim-material=\"$id\"]').dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));true")
        compose.waitForIdle()
    }
    private fun webOrNull(): OfflineMarkdownView? {
        var result: OfflineMarkdownView? = null
        compose.runOnUiThread {
            result = descendants(compose.activity.window.decorView).filterIsInstance<OfflineMarkdownView>().firstOrNull()
        }
        return result
    }
    private fun eval(script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            val editor = webOrNull()
            if (editor == null) done.countDown() else editor.evaluateJavascript(script) { result = it; done.countDown() }
        }
        check(done.await(10, TimeUnit.SECONDS)) { "Editor WebView did not respond" }
        return result
    }
    private fun finishNote() {
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-confirm-finish").performClick()
        compose.waitUntil(15_000) { model.completion.value != null && !model.busy.value }
        compose.onNodeWithTag("note-continue-editing").performClick()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

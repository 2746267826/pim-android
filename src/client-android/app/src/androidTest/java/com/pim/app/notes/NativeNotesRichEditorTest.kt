package com.pim.app.notes

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.recording.RecordingController
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class NativeNotesRichEditorTest {
    private val compose = createAndroidComposeRule<NativeNotesActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*(
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    ))).around(compose)
    private val model get() = compose.activity.model
    private fun note() = model.notes.state.value.documents.getValue(model.selectedId.value!!)

    @After fun stopOnlyOwnRecording() {
        model.recordings.state.value.session?.takeIf { it.active }?.let {
            RecordingController.finish(compose.activity, it.id)
            compose.waitUntil(10_000) { !model.recordings.state.value.active }
        }
    }

    @Test fun defaultRichEditorCommitsChineseCompositionAndRapidInputBeforeFinishingWithRealRecording() {
        create(); ready(); saved()
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) >= 3200 }
        val session = model.recordings.state.value.session!!.id
        compose.waitUntil(10_000) { eval("document.querySelector('.tiptap')?.getAttribute('contenteditable')") == "\"true\"" }
        compose.onNodeWithTag("rich-markdown-${note().blocks.single().string("id")}").performTouchInput { click(androidx.compose.ui.geometry.Offset(30f, 30f)) }
        eval("document.querySelector('.tiptap').focus(); true")
        var connection: android.view.inputmethod.InputConnection? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { connection = web().onCreateInputConnection(EditorInfo()) }
            connection != null
        }
        val committed = CountDownLatch(1)
        var inputError: Throwable? = null
        // Chromium owns an IME handler thread; invoking its connection on the UI thread is invalid.
        val handler = connection!!.handler ?: android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try { checkNotNull(connection).apply {
                beginBatchEdit(); setComposingText("中文组", 1); setComposingText("中文组合输入", 1)
                finishComposingText(); endBatchEdit()
            } } catch (error: Throwable) { inputError = error }
            finally { committed.countDown() }
        }
        check(committed.await(10, TimeUnit.SECONDS))
        inputError?.let { throw it }
        compose.waitUntil(10_000) { note().blocks.single().string("text").contains("中文组合输入") }
        eval("for(let i=0;i<30;i++) document.execCommand('insertText', false, ' ' + i); true")
        // Immediately cross the native boundary, without waiting for Compose to echo each keystroke.
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-confirm-finish").performClick()
        compose.waitUntil(20_000) { !model.busy.value && model.completion.value != null }
        val text = note().blocks.single().string("text")
        assertEquals("中文组合输入" + (0 until 30).joinToString("") { " $it" }, text)
        val input = NoteMaterialStore.at(compose.activity.noBackupFilesDir).read(model.completion.value!!.versionId)
        assertEquals(text, input.document.blocks.single().string("text"))
        assertEquals(session, input.recordings.single().id)
        assertTrue(input.recordings.single().samples > 0)
        compose.onNodeWithTag("note-continue-editing").performClick()
        saved()
        compose.activityRule.scenario.recreate()
        ready()
        assertEquals(text, note().blocks.single().string("text"))
    }

    @Test fun richCaretSplitsFormattingAroundNativeInkWithoutChangingOldSchemaOrVectorIdentity() {
        create()
        val blockId = note().blocks.single().string("id")
        compose.runOnUiThread { model.notes.update(note().id) { doc -> doc.replaceBlock(blockId) { JsonObject(it + ("text" to JsonPrimitive("**beforeafter**"))) } } }
        ready()
        compose.waitUntil(10_000) { eval("document.querySelector('.tiptap strong')?.textContent") == "\"beforeafter\"" }
        val before = note()
        eval("const n=document.querySelector('.tiptap strong').firstChild; const r=document.createRange(); r.setStart(n,6);r.collapse(true);const s=window.getSelection();s.removeAllRanges();s.addRange(r);document.querySelector('.tiptap').focus();true")
        compose.onNodeWithTag("note-insert-menu").performClick()
        compose.onNodeWithTag("note-insert-ink").performClick()
        compose.waitUntil(10_000) { note().blocks.size == 3 }
        assertEquals("**before**", note().blocks[0].string("text"))
        assertEquals("**after**", note().blocks[2].string("text"))
        assertEquals(blockId, note().blocks[0].string("id"))
        assertEquals(1, note().source.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(1, before.blocks.size)
        val ink = note().blocks[1]
        saved()
        val exact = note()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { note() == exact }
        assertEquals(ink, note().blocks[1])
        assertEquals(exact, NoteFileStore(File(compose.activity.noBackupFilesDir, "notes")).read(exact.id))
    }

    @Test fun nativeFormatToolbarStaysInTheViewportAndRealHistoryCanUndoAndRedo() {
        create(); ready()
        compose.onNodeWithTag("rich-markdown-${note().blocks.single().string("id")}").performTouchInput { click(androidx.compose.ui.geometry.Offset(30f, 30f)) }
        eval("document.querySelector('.tiptap').focus();document.execCommand('insertText',false,'正文');true")
        compose.waitUntil(10_000) { note().blocks.single().string("text") == "正文" }
        withEditorEvidence("format-tools") {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-text-tools").fetchSemanticsNodes().size == 1 }
        }
        compose.onNodeWithTag("note-text-tools").assertIsDisplayed()
        val tools = compose.onNodeWithTag("note-text-tools").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("note-dock").fetchSemanticsNode().boundsInRoot
        assertTrue(tools.bottom <= dock.top)
        captureEditorEvidence("format-tools-visible")
        compose.onNodeWithTag("note-format-bold").performClick()
        eval("document.execCommand('insertText',false,'结论');true")
        compose.waitUntil(10_000) { note().blocks.single().string("text").contains("**结论**") }
        val beforeUndo = note().blocks.single().string("text")
        compose.onNodeWithTag("note-format-undo").performScrollTo().performClick()
        compose.waitUntil(10_000) { note().blocks.single().string("text") != beforeUndo }
        compose.onNodeWithTag("note-format-redo").performScrollTo().performClick()
        compose.waitUntil(10_000) { note().blocks.single().string("text") == beforeUndo }
    }

    @Test fun richCaretRemainsInsideTheNativeViewportAboveTheKeyboardAndToolsInAMixedRecording() {
        create()
        compose.runOnUiThread { model.notes.update(note().id) { it.withTitle("研究方法 · 第四讲").withBlocks(listOf(
            markdownBlock("## 从问题到证据\n\n比较两份数据之前，先核对时间、区域与样本的范围。方法的适用条件，和结论一样重要。\n\n- 保留原始材料"),
            inkBlock(320f), markdownBlock("留给自己的问题")
        )) } }
        ready(); saved()
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) >= 3200 }
        val id = note().blocks.first().string("id")
        val receivedCaret = AtomicReference("")
        compose.runOnUiThread {
            val view = web()
            val forward = view.onCaret
            view.onCaret = { top, bottom -> receivedCaret.set("$top..$bottom"); forward(top, bottom) }
        }
        compose.onNodeWithTag("rich-markdown-$id").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(30f, 30f)) }
        eval("document.querySelector('.tiptap').focus();true")
        compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.waitUntil(10_000) { eval("JSON.parse(window.PimEditor.snapshot(true)).focused") == "true" }
        // Finish native IME focus before selecting the exact paragraph, not the earlier touch position.
        eval("const n=document.querySelector('.tiptap p').firstChild;const r=document.createRange();r.setStart(n,n.textContent.length);r.collapse(true);const s=window.getSelection();s.removeAllRanges();s.addRange(r);JSON.parse(window.PimEditor.snapshot(true)).before")
        val expectedBefore = note().blocks.first().string("text").substringBefore("\n\n- ")
        compose.waitUntil(10_000) { eval("JSON.parse(window.PimEditor.snapshot(true)).before") == JsonPrimitive(expectedBefore).toString() }
        var lastBounds = ""
        withEditorEvidence("caret-viewport", { lastBounds }) { compose.waitUntil(10_000) {
            val bounds = NoteJson.parseToJsonElement(eval("(()=>{const r=window.getSelection().getRangeAt(0).getBoundingClientRect();return {top:r.top,bottom:r.bottom}})()")).jsonObject
            val webOrigin = IntArray(2)
            val rootOrigin = IntArray(2)
            val beforeLayout = IntArray(2)
            var scale = 1f
            compose.runOnUiThread { web().getLocationOnScreen(beforeLayout) }
            val documentNode = compose.onNodeWithTag("note-document").fetchSemanticsNode()
            val toolsNode = compose.onAllNodesWithTag("note-text-tools").fetchSemanticsNodes().singleOrNull()
            var document = androidx.compose.ui.geometry.Rect.Zero
            var tools: androidx.compose.ui.geometry.Rect? = null
            // Semantics queries wait for placement. Sample native and Compose bounds after that same barrier.
            compose.runOnIdle {
                web().getLocationOnScreen(webOrigin)
                compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).getLocationOnScreen(rootOrigin)
                scale = compose.activity.resources.displayMetrics.density
                document = documentNode.boundsInRoot
                tools = toolsNode?.boundsInRoot
            }
            val top = webOrigin[1] + bounds.getValue("top").jsonPrimitive.float * scale
            val bottom = webOrigin[1] + bounds.getValue("bottom").jsonPrimitive.float * scale
            lastBounds = "caret=$top..$bottom, nativeCaret=${receivedCaret.get()}, beforeLayout=${beforeLayout.toList()}, rootOrigin=${rootOrigin.toList()}, document=$document, tools=$tools, webOrigin=${webOrigin.toList()}"
            val currentTools = tools
            currentTools != null && bottom > top && top >= rootOrigin[1] + document.top &&
                bottom <= rootOrigin[1] + minOf(document.bottom, currentTools.top)
        } }
        android.util.Log.i("PimCaretEvidence", lastBounds)
        assertEquals(JsonPrimitive(expectedBefore).toString(), eval("JSON.parse(window.PimEditor.snapshot(true)).before"))
        captureEditorEvidence("caret-visible")
        eval("document.execCommand('insertText',false,'补充');true")
        compose.waitUntil(10_000) { note().blocks.first().string("text").contains("重要。补充") }
        compose.onNodeWithTag("note-finish").performClick()
        withEditorEvidence("first-finish") {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        }
        compose.onNodeWithTag("note-confirm-finish").assertIsDisplayed().performClick()
        compose.waitUntil(20_000) { !model.busy.value && model.completion.value != null }
        assertFalse(model.recordings.state.value.active)
        val input = NoteMaterialStore.at(compose.activity.noBackupFilesDir).read(model.completion.value!!.versionId)
        assertEquals(note().blocks, input.document.blocks)
        assertTrue(input.recordings.single().samples > 0)
    }

    @Test fun merelyOpeningPreviewingAndRecreatingDoesNotCanonicalizeExistingMarkdown() {
        create()
        val source = "# 旧原文\n\n保留两空格  \n第二行\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n- [x] 完成\n"
        compose.runOnUiThread { model.notes.update(note().id) { it.withBlocks(listOf(markdownBlock(source))) } }
        ready(); saved()
        val original = note()
        compose.onNodeWithTag("note-preview").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("note-preview") and hasContentDescription("编辑笔记") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-preview").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("note-preview") and hasContentDescription("预览笔记") and isEnabled()).fetchSemanticsNodes().size == 1 }
        compose.activityRule.scenario.recreate()
        ready()
        assertEquals(original, note())
        assertEquals(source, note().blocks.single().string("text"))
        assertEquals("true", eval("Boolean(document.querySelector('.tiptap table') && document.querySelector('.tiptap input[type=checkbox]'))"))
    }

    @Test fun unsupportedSourceRemainsEditableVerbatimAndEditorCannotFetchOrNavigateToRemoteContent() {
        create(); ready()
        compose.runOnUiThread {
            assertTrue(web().settings.blockNetworkLoads)
            assertFalse(web().settings.allowContentAccess)
            assertFalse(web().settings.allowFileAccess)
        }
        eval("window.fetchResult='pending'; fetch('https://example.com/private').then(()=>window.fetchResult='unexpected',()=>window.fetchResult='blocked');true")
        compose.waitUntil(10_000) { eval("window.fetchResult") == "\"blocked\"" }
        val id = note().blocks.single().string("id")
        compose.runOnUiThread { model.notes.update(note().id) { doc -> doc.replaceBlock(id) { JsonObject(it + ("text" to JsonPrimitive("[外链](https://example.com)"))) } } }
        compose.waitUntil(10_000) { eval("Boolean(document.querySelector('.tiptap a'))") == "true" }
        eval("document.querySelector('.tiptap a').click();true")
        compose.runOnUiThread { assertEquals(NoteEditorPolicy.PAGE, web().url) }
        val source = "<span data-private=\"keep\">原稿</span>\n\n![引用](private.png)\n"
        compose.runOnUiThread { model.notes.update(note().id) { doc -> doc.replaceBlock(id) { JsonObject(it + ("text" to JsonPrimitive(source))) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("这段含 HTML", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(source, note().blocks.single().string("text"))
        compose.runOnUiThread {
            val editor = descendants(compose.activity.window.decorView).filterIsInstance<MarkdownEditText>().single()
            editor.requestFocus(); editor.setSelection(editor.text.length); editor.append("补充原文")
        }
        closeSoftKeyboard(); saved()
        assertEquals(source + "补充原文", note().blocks.single().string("text"))
    }

    private fun create() {
        compose.waitUntil(15_000) { model.notes.state.value.loaded && model.materials.state.value.loaded }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5000) { model.selectedId.value != null }
        closeSoftKeyboard()
    }
    private fun saved() = compose.waitUntil(15_000) { model.notes.state.value.savedRevisions[note().id] == note().revision }
    private fun ready() {
        // Lazy native-view order is not document order; realize and identify the expected leaf.
        compose.onNodeWithTag("note-document").performScrollToIndex(2)
        compose.waitUntil(15_000) {
            var attached = false
            compose.runOnUiThread { attached = webOrNull() != null }
            attached
        }
        compose.waitUntil(15_000) { eval("Boolean(document.querySelector('.tiptap'))") == "true" }
    }
    private fun webOrNull(): OfflineMarkdownView? = descendants(compose.activity.window.decorView).filterIsInstance<OfflineMarkdownView>()
        .firstOrNull { it.blockId == note().blocks.first { block -> block.string("kind") == "markdown" }.string("id") }
    private fun web(): OfflineMarkdownView = checkNotNull(webOrNull()) { "The expected Markdown leaf is not attached" }
    private fun eval(script: String): String {
        var result = ""
        val latch = CountDownLatch(1)
        compose.runOnUiThread { web().evaluateJavascript(script) { result = it; latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)) { "Editor JS did not respond" }
        return result
    }
    private fun withEditorEvidence(name: String, detail: () -> String = { "" }, action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            val path = captureEditorEvidence(name)
            val dom = eval("JSON.stringify({active:document.activeElement?.className,viewport:window.innerHeight,selection:window.getSelection()?.rangeCount?window.getSelection().getRangeAt(0).getBoundingClientRect().toJSON():null,snapshot:JSON.parse(window.PimEditor.snapshot(true))})")
            var native = ""
            compose.runOnUiThread {
                val location = IntArray(2); web().getLocationOnScreen(location)
                native = "webOrigin=${location.toList()}, webHeight=${web().height}, scrollY=${web().scrollY}, focused=${web().hasFocus()}"
            }
            throw AssertionError("$name: ${detail()}; $native; DOM=$dom; tree=${compose.onRoot().printToString()}; screenshot=$path", error)
        }
    }
    private fun captureEditorEvidence(name: String): String {
        val config = compose.activity.resources.configuration
        val path = "/sdcard/Download/pim-rich-editor-$name-${config.screenWidthDp}-${config.fontScale}.png"
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p $path"
        )).use { it.readBytes() }
        return path
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

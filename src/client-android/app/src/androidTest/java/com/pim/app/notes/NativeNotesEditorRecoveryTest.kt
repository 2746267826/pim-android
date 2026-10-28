package com.pim.app.notes

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.pim.app.recording.RecordingController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Real renderer termination and lazy-view replacement; bridge and retirement delays are injected faults. */
class NativeNotesEditorRecoveryTest {
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

    @Test fun terminatedRendererKeepsRecordingAndRequiresExplicitSourceReviewAcrossRecreation() {
        create(listOf(markdownBlock("已收到的原文")))
        val id = note().blocks.single().string("id")
        ready(id)
        startRecording()
        val view = web(id)
        focus(id)
        // Hold actual text snapshots before native delivery, then kill the real rendering process.
        // This is a controlled bridge-delay fault, not a claim about a particular hardware IME.
        eval(view, """
            const send=window.PimNoteBridge.postMessage.bind(window.PimNoteBridge);
            window.heldSnapshots=0;
            window.PimNoteBridge.postMessage=raw=>{
              const message=JSON.parse(raw);
              if(message.type==='snapshot'){window.heldSnapshots++;return;}
              send(raw);
            };
            document.execCommand('insertText',false,'尚未送达的字符');
            const pending=JSON.parse(window.PimEditor.snapshot(true));
            pending.type='composing';pending.composing=true;
            send(JSON.stringify(pending));true
        """.trimIndent())
        assertEquals("true", eval(view, "window.heldSnapshots>0"))
        assertEquals("已收到的原文", note().blocks.single().string("text"))
        var terminated = false
        compose.runOnUiThread {
            check(Build.VERSION.SDK_INT >= 29)
            terminated = checkNotNull(view.webViewRenderProcess).terminate()
        }
        assertTrue("The actual renderer process must accept termination", terminated)
        reviewVisible(id)
        compose.onNodeWithTag("note-save-status", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("待核对正文")
        val samples = model.recordings.state.value.session!!.samples
        compose.waitUntil(10_000) { model.recordings.state.value.session!!.samples > samples + 3200 }
        assertEquals("已收到的原文", note().blocks.single().string("text"))
        assertFinishBlocked()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { model.notes.state.value.loaded && model.selectedId.value != null }
        reviewVisible(id)
        compose.runOnUiThread {
            assertTrue(descendants(compose.activity.window.decorView).filterIsInstance<MarkdownEditText>()
                .any { it.text.toString() == "已收到的原文" })
        }
        capture("renderer-review")
        assertFinishBlocked()
        // No arbitrary edit or second Finish tap is required to acknowledge the displayed source.
        compose.onNodeWithTag("note-review-source-$id").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("note-review-source-$id").assertDoesNotExist()
        finishOnce()
        val fixed = NoteMaterialStore.at(compose.activity.noBackupFilesDir).read(model.completion.value!!.versionId)
        assertEquals("已收到的原文", fixed.document.blocks.single().string("text"))
        assertTrue(fixed.recordings.single().samples > 0)
        assertFalse(model.recordings.state.value.active)
        capture("renderer-recovered")
    }

    @Test fun rapidLazyScrollingAndFirstFinishKeepEveryBlockAndNewInput() {
        create((0 until 24).map { markdownBlock(if (it == 0) "原有正文" else "第${it}段：保留每一段记录的完整内容。\n\n".repeat(3)) })
        val id = note().blocks.first().string("id")
        ready(id); startRecording()
        closeSoftKeyboard()
        compose.onNodeWithTag("note-document").performScrollToIndex(10)
        compose.onNodeWithTag("note-document").performScrollToIndex(note().blocks.lastIndex + 2)
        compose.onNodeWithTag("note-document").performScrollToIndex(2)
        ready(id)
        focus(id)
        eval(web(id), "document.execCommand('insertText',false,' 新输入');true")
        compose.waitUntil(10_000) { note().blocks.first().string("text").contains("新输入") }
        val expected = note().blocks
        finishOnce()
        val fixed = NoteMaterialStore.at(compose.activity.noBackupFilesDir).read(model.completion.value!!.versionId)
        assertEquals(expected, fixed.document.blocks)
        assertEquals(24, fixed.document.blocks.size)
        assertTrue(fixed.document.blocks.first().string("text").contains("新输入"))
        assertTrue(fixed.recordings.single().samples > 0)
    }

    @Test fun delayedNativeViewHandoffCannotAcceptNewInputOrClearItsPendingStateEarly() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = NoteEditorCoordinator(scope)
        val source = AtomicReference("原有正文")
        val id = newNoteId()
        lateinit var frame: android.widget.FrameLayout
        fun attach(): OfflineMarkdownView = OfflineMarkdownView(compose.activity).also { view ->
            view.blockId = id; view.readCurrentText = { source.get() }; view.onText = source::set
            view.onPending = { coordinator.pending(view, it) }
            view.onReviewRequired = { coordinator.review(id, it) }
            coordinator.register(id, view)
            frame.addView(view, android.widget.FrameLayout.LayoutParams(700, 300))
            view.bind(source.get(), true, mapOf("font-size" to "16px", "line-height" to "24px", "minimum-height" to "48px"))
        }
        lateinit var old: OfflineMarkdownView
        lateinit var replacement: OfflineMarkdownView
        var frameAttached = false
        var oldAttached = false
        var replacementAttached = false
        try {
            compose.runOnUiThread {
                frame = android.widget.FrameLayout(compose.activity)
                compose.activity.addContentView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400))
                frameAttached = true
                old = attach(); oldAttached = true
            }
            compose.waitUntil(10_000) { eval(old, "Boolean(document.querySelector('.tiptap'))") == "true" }
            eval(old, """
                const original=window.PimEditor.snapshot.bind(window.PimEditor);
                window.holdRetirement=true;
                window.PimEditor.snapshot=(split,freeze)=>freeze&&window.holdRetirement
                  ? JSON.stringify({composing:true}) : original(split,freeze);true
            """.trimIndent())
            compose.runOnUiThread {
                frame.removeView(old); coordinator.retire(old)
                replacement = attach(); replacementAttached = true
                assertNull("Do not initialize from an unconfirmed predecessor", replacement.url)
            }
            eval(old, "window.holdRetirement=false;true")
            compose.waitUntil(10_000) { eval(replacement, "Boolean(document.querySelector('.tiptap'))") == "true" }
            eval(replacement, "document.querySelector('.tiptap').focus();document.execCommand('insertText',false,'新输入');true")
            compose.waitUntil(10_000) { source.get().contains("新输入") }
            eval(replacement, "const sendPending=window.PimNoteBridge.postMessage.bind(window.PimNoteBridge);window.releasePending=()=>sendPending(window.PimEditor.snapshot(true));window.PimNoteBridge.postMessage=raw=>{if(JSON.parse(raw).type!=='snapshot')sendPending(raw)};const pending=JSON.parse(window.PimEditor.snapshot(true));pending.type='composing';pending.composing=true;sendPending(JSON.stringify(pending));true")
            compose.waitUntil(5000) { coordinator.pending }
            compose.runOnUiThread {
                old.onPending(false) // A queued callback from the retired instance must not clear its successor.
                assertTrue(coordinator.pending)
            }
            eval(replacement, "window.releasePending();true")
            compose.waitUntil(5000) { !coordinator.pending }
            val confirmed = CountDownLatch(1)
            var frozen: String? = null
            var problem: Throwable? = null
            scope.launch {
                try { coordinator.withCommitted { frozen = source.get() } }
                catch (error: Throwable) { problem = error }
                finally { confirmed.countDown() }
            }
            check(confirmed.await(10, TimeUnit.SECONDS)); problem?.let { throw it }
            assertEquals(source.get(), frozen)
            assertTrue(checkNotNull(frozen).contains("新输入"))
        } finally {
            compose.runOnUiThread {
                if (replacementAttached) coordinator.retire(replacement)
                if (oldAttached) coordinator.retire(old)
                if (frameAttached) (frame.parent as? ViewGroup)?.removeView(frame)
                scope.cancel()
            }
        }
    }

    private fun create(blocks: List<JsonObject>) {
        compose.waitUntil(15_000) { model.notes.state.value.loaded && model.materials.state.value.loaded }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5000) { model.selectedId.value != null }
        compose.runOnUiThread { model.notes.update(note().id) { it.withBlocks(blocks) } }
        closeSoftKeyboard()
    }
    private fun startRecording() {
        compose.waitUntil(15_000) { model.notes.state.value.savedRevisions[note().id] == note().revision }
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && model.recordings.state.value.session!!.samples >= 3200 }
    }
    private fun focus(id: String) {
        compose.onNodeWithTag("rich-markdown-$id").performScrollTo().performTouchInput { click(androidx.compose.ui.geometry.Offset(30f, 30f)) }
        eval(web(id), "document.querySelector('.tiptap').focus();true")
        compose.waitUntil(10_000) { eval(web(id), "JSON.parse(window.PimEditor.snapshot(true)).focused") == "true" }
    }
    private fun ready(id: String) {
        val index = note().blocks.indexOfFirst { it.string("id") == id }
        compose.onNodeWithTag("note-document").performScrollToIndex(index + 2)
        compose.waitUntil(15_000) { webOrNull(id) != null }
        compose.waitUntil(15_000) { eval(web(id), "Boolean(document.querySelector('.tiptap'))") == "true" }
    }
    private fun reviewVisible(id: String) {
        compose.onNodeWithTag("note-document").performScrollToIndex(2)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-review-source-$id").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-review-source-$id").performScrollTo().assertIsDisplayed()
    }
    private fun assertFinishBlocked() {
        assertNull(model.completion.value)
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("note-confirm-finish").assertDoesNotExist()
        assertNull(model.completion.value)
        assertTrue(model.recordings.state.value.active)
    }
    private fun finishOnce() {
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-confirm-finish").performClick()
        compose.waitUntil(20_000) { !model.busy.value && model.completion.value != null }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-continue-editing").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-continue-editing").assertIsDisplayed()
    }
    private fun webOrNull(id: String): OfflineMarkdownView? {
        var view: OfflineMarkdownView? = null
        compose.runOnUiThread { view = descendants(compose.activity.window.decorView).filterIsInstance<OfflineMarkdownView>().firstOrNull { it.blockId == id } }
        return view
    }
    private fun web(id: String) = checkNotNull(webOrNull(id))
    private fun eval(view: OfflineMarkdownView, script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        compose.runOnUiThread { view.evaluateJavascript(script) { result = it; done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS)) { "WebView script did not respond" }
        return result
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val config = compose.activity.resources.configuration
        val path = "/sdcard/Download/pim-editor-recovery-$name-${config.screenWidthDp}-${config.fontScale}.png"
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p $path")).use { it.readBytes() }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

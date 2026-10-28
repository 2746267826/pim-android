package com.pim.app.notes

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.pim.app.recording.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class NativeNotesExperienceTest {
    private val compose = createAndroidComposeRule<NativeNotesActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*(
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    ))).around(compose)
    private val model get() = compose.activity.model
    private fun note() = model.notes.state.value.documents.getValue(model.selectedId.value!!)

    @After fun finishOnlyOwnRecording() {
        model.recordings.state.value.session?.takeIf { it.active }?.let {
            RecordingController.finish(compose.activity, it.id)
            compose.waitUntil(10_000) { !model.recordings.state.value.active }
        }
    }

    @Test fun documentOwnsTheScreenAndFloatingControlsDoNotCoverTheDocumentEnd() {
        create()
        compose.runOnUiThread {
            val ink = InkData(width = 320f, height = 180f, strokes = listOf(
                InkStroke(listOf(InkPoint(24f, 140f), InkPoint(24f, 30f))),
                InkStroke(listOf(InkPoint(24f, 140f), InkPoint(288f, 140f))),
                InkStroke(listOf(InkPoint(34f, 120f), InkPoint(90f, 100f), InkPoint(150f, 62f), InkPoint(210f, 74f), InkPoint(276f, 32f)))
            ))
            model.notes.update(note().id) { it.withBlocks(listOf(
                markdownBlock("## 从问题到证据\n\n比较两份数据之前，先核对时间、区域与样本的范围。方法的适用条件，和结论一样重要。\n\n- 提出一个可以被验证的问题\n- 保留原始材料，区分事实与推断"),
                withInk(inkBlock(320f), ink),
                markdownBlock("### 留给自己的问题\n\n如果样本不足，结论应该怎样表述？下课后找一个反例，再回头检查今天的假设。")
            )) }
        }
        saved()
        compose.onNodeWithTag("note-finish").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("note-insert-menu").assertHeightIsAtLeast(48.dp)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val document = compose.onNodeWithTag("note-document").fetchSemanticsNode().boundsInRoot
        assertTrue("The document must receive most vertical space, even on a compact phone", document.height >= root.height * .70f)
        assertDockBounds()
        evidence("editor")
        compose.onNodeWithTag("note-document").performScrollToNode(hasText("正文支持 Markdown · 右下角插入照片与手写"))
        val end = compose.onNodeWithText("正文支持 Markdown · 右下角插入照片与手写").fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("note-dock").fetchSemanticsNode().boundsInRoot
        assertTrue("The final content must scroll above the floating controls", end.bottom <= dock.top)
        compose.onNodeWithTag("note-finish").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回记录列表").performClick()
        compose.onNodeWithTag("note-open-${noteId}").assertIsDisplayed()
        evidence("library")
    }

    @Test fun realRecordingPillStaysUsableWithKeyboardAndPreviewDoesNotRewriteTheSource() {
        create()
        startRecording()
        edit("## 课堂笔记\n\n记录中也能编辑，全部保存在本机。", keyboard = true)
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        assertDockBounds()
        val pill = compose.onNodeWithTag("recording-pill").fetchSemanticsNode().boundsInRoot
        val insert = compose.onNodeWithTag("note-insert-menu").fetchSemanticsNode().boundsInRoot
        assertTrue(pill.right < insert.left)
        compose.onNodeWithTag("recording-pause-resume").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(5_000) { model.recordings.state.value.session?.phase == RecordingPhase.Paused }
        compose.onNodeWithTag("recording-pause-resume").performClick()
        compose.waitUntil(5_000) { model.recordings.state.value.session?.phase == RecordingPhase.Recording }
        evidence("keyboard-recording")
        closeSoftKeyboard(); saved()
        val before = note()
        compose.onNodeWithTag("note-preview").performClick()
        assertEquals(before, note())
        compose.onNodeWithTag("note-insert-menu").assertIsNotEnabled()
        evidence("preview-recording")
        compose.onNodeWithTag("note-preview").performClick()
        assertEquals(before, note())
        assertTrue(model.recordings.state.value.active)
    }

    @Test fun endingRequiresConfirmationAndTheResultDoesNotPretendToBeCloudProcessing() {
        create(); startRecording()
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("继续记录").performClick()
        assertTrue(model.recordings.state.value.active)
        assertFalse(note().source.containsKey("endedAtMillis"))
        compose.onNodeWithTag("note-finish").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("note-confirm-finish").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("note-confirm-finish").performClick()
        compose.waitUntil(20_000) { !model.busy.value && model.completion.value != null }
        assertFalse(model.recordings.state.value.active)
        compose.onNodeWithTag("note-completion").assertIsDisplayed()
        compose.onNodeWithText("可在材料版本中核对目标并上传。上传只保存原材料，不自动发起转写或 AI 分析。").assertIsDisplayed()
        evidence("completed")
        compose.onNodeWithTag("note-continue-editing").performScrollTo().performClick()
        compose.onNodeWithTag("note-title").performScrollTo().performTextReplacement("结束后仍可编辑")
        closeSoftKeyboard(); saved()
        assertEquals("结束后仍可编辑", note().title)
    }

    @Test fun stopOnlyAudioThenSupplementaryRecordingKeepBothSessionsInTheSameNote() {
        create(); startRecording()
        val first = model.recordings.state.value.session!!.id
        compose.onNodeWithTag("note-recordings").performScrollTo().performClick()
        compose.onNodeWithTag("note-stop-recording").performScrollTo().performClick()
        compose.waitUntil(10_000) { !model.recordings.state.value.active }
        assertFalse(note().source.containsKey("endedAtMillis"))
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-recordings-sheet").fetchSemanticsNodes().isEmpty() }
        edit("停止一段录音以后，仍然可以继续写。")
        saved(); startRecording()
        val second = model.recordings.state.value.session!!
        assertNotEquals(first, second.id)
        assertEquals(note().id, second.noteId)
        assertTrue(model.recordings.state.value.archives.any { it.session?.let { saved -> saved.id == first && saved.noteId == note().id } == true })
        compose.onNodeWithTag("note-recordings").performScrollTo().performClick()
        compose.onNodeWithTag("note-recordings-sheet").performScrollToNode(hasText("录音 1 ·", substring = true))
        compose.onNodeWithText("录音 1 ·", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("note-recordings-sheet").performScrollToNode(hasText("录音 2 ·", substring = true))
        compose.onNodeWithText("录音 2 ·", substring = true).assertIsDisplayed()
        evidence("recordings")
    }

    private var noteId: String? = null
    private fun create() {
        compose.waitUntil(15_000) { model.notes.state.value.loaded && model.materials.state.value.loaded }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(5_000) { model.selectedId.value != null }
        noteId = note().id
        compose.onNodeWithTag("note-title").performTextReplacement("研究方法 · 第四讲")
        closeSoftKeyboard(); saved()
    }

    private fun saved() = compose.waitUntil(15_000) { model.notes.state.value.savedRevisions[note().id] == note().revision }
    private fun startRecording() {
        saved()
        compose.onNodeWithTag("note-start-recording").performClick()
        compose.waitUntil(10_000) { model.recordings.state.value.active && (model.recordings.state.value.session?.samples ?: 0) >= 3200 }
    }
    private fun edit(text: String, keyboard: Boolean = false) {
        val mode = "markdown-mode-${note().blocks.first { it.string("kind") == "markdown" }.string("id")}"
        if (compose.onAllNodes(hasTestTag(mode) and hasText("Markdown 源码")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(mode).performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(mode) and hasText("富文本")).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.runOnUiThread {
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val editor = descendants(compose.activity.window.decorView).filterIsInstance<MarkdownEditText>().first()
            editor.requestFocus(); editor.setText(text); editor.setSelection(text.length)
            if (keyboard) (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
        compose.waitForIdle()
        if (!keyboard) closeSoftKeyboard()
    }
    private fun assertDockBounds() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag("note-dock").fetchSemanticsNode().boundsInRoot
        assertTrue(dock.left >= root.left && dock.right <= root.right)
        assertTrue(dock.bottom <= root.bottom && dock.height > 0)
        compose.onNodeWithTag("note-insert-menu").assertIsDisplayed()
    }
    private fun evidence(name: String) {
        val config = compose.activity.resources.configuration
        val suffix = "${config.screenWidthDp}-${config.fontScale}"
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/pim-native-notes-$name-$suffix.png"
        )).use { it.readBytes() }
    }
}

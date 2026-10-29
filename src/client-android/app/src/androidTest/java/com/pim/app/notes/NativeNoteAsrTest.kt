package com.pim.app.notes

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.pim.app.MainActivity
import com.pim.app.recording.RecordingEngine
import com.pim.app.recording.RecordingFileStore
import com.pim.app.recording.RecordingPolicy
import com.pim.app.recording.RecordingSession
import com.pim.core.auth.*
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.network.*
import com.pim.core.settings.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import retrofit2.HttpException

/**
 * Runs only against the isolated Test API + loopback synthetic ASR host.
 *
 * The fixture is deliberately synthetic: 121 seconds of generated PCM produces
 * three provider slices. No real recording, classroom data, paid ASR endpoint or
 * production service participates in this test.
 */
class NativeNoteAsrTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            isolatedServerOnly()
            qaTarget = parseTarget(qa("target"))
            qa("control?reset=1")
        }
    }).around(compose)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = compose.activity.applicationContext
    private lateinit var base: String
    private lateinit var qaTarget: QaTarget
    private var editor: NativeNotesActivity? = null
    private val uploads get() = NoteUploadStore.at(context.noBackupFilesDir)

    @After
    fun closeOwnEditor() {
        editor?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        editor = null
    }

    @Test
    fun ordinaryAccountUsesPinnedOlderVersionForThreeSegmentsAndTaskSurvivesRebuildAndAccountSwitch() =
        captureCaseFailure("normal-three-slices-account-switch") {
        login("notes-qa-alice")
        val fixture = prepareFixture(versionCount = 2)
        val versionB = checkNotNull(fixture.versionB) { "Two-version QA fixture must expose version B" }
        val sourceA = awaitRemoteSource(fixture.versionA)
        val sourceB = awaitRemoteSource(versionB)
        assertFalse(sourceA.isCurrent)
        assertTrue(sourceB.isCurrent)
        assertEquals(1, sourceA.recordings)
        assertEquals(16_000L * 121, sourceA.totalSamples)

        assertEquals(0, qaSummary().sentCalls)
        assertNoTaskForVersion(fixture.versionA)
        assertNoTaskForVersion(versionB)

        openAsrForVersion(fixture.versionA)
        compose.onNodeWithTag("note-asr-screen").assertIsDisplayed()
        compose.onNodeWithTag("note-asr-source-${fixture.versionA}").assertExists()
        compose.onNodeWithText(qaTarget.model.displayName).assertExists()
        assertTrue(compose.onAllNodesWithText(qaTarget.model.model, substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            compose.onAllNodesWithText(qaTarget.model.modelRevision, substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(compose.onAllNodesWithText(qaTarget.model.presetId, substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            "The exact destination endpoint must be visible before consent",
            compose.onAllNodesWithText(qaTarget.model.endpoint, substring = true).fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithTag("note-asr-after-upload-notice").assertExists()
        compose.onNodeWithTag("note-asr-submit-${fixture.versionA}").assertIsNotEnabled()
        shot("normal-consent")
        compose.onNodeWithTag("note-asr-consent-check").performScrollTo().performClick()
        compose.onNodeWithTag("note-asr-submit-${fixture.versionA}").assertIsEnabled().performScrollTo().performClick()

        val task = awaitTaskForVersion(fixture.versionA)
        assertEquals(fixture.versionA, task.versionId)
        assertEquals(3, task.totalSegments)
        assertEquals(16_000L * 121, task.totalSamples)
        assertEquals(qaTarget.model.modelRevision, task.destination.modelRevision)
        assertEquals(qaTarget.model.endpoint, task.destination.endpoint)
        assertEquals(qaTarget.model.presetId, task.destination.presetId)
        assertEquals(qaTarget.model.presetVersion, task.destination.presetVersion)
        assertEquals("Uploading material must not trigger ASR", 0, qaSummary().sentCalls)

        triggerJob(task.taskId)
        val completed = awaitTask(task.taskId) { it.phase == "Succeeded" }
        assertEquals(3, completed.completedSegments)
        assertEquals(completed.totalSamples, completed.completedSamples)
        assertEquals(3, qaSummary().sentCalls)
        val segments = awaitSegments(task.taskId) { all ->
            all.size == 3 && all.all { it.status == "Succeeded" && !it.transcript.isNullOrBlank() }
        }
        assertEquals(listOf(960_000L, 960_000L, 16_000L), segments.map { it.samples })

        closeEditor()
        showLibrary()
        openTask(task.taskId)
        compose.onNodeWithTag("note-asr-phase-${task.taskId}").assertIsDisplayed()
        compose.onNodeWithTag("note-asr-background-unavailable-${task.taskId}").assertExists()
        assertTranscriptVisible(task.taskId, segments[0])
        assertTranscriptVisible(task.taskId, segments[2])
        shot("normal-segments")

        closeEditor()
        login("notes-qa-bob")
        assertTrue(runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.none { it.taskId == task.taskId })
        try {
            runBlocking { reader().getNoteAsrTask(task.taskId) }
            fail("Bob must not read Alice's ASR task")
        } catch (error: HttpException) {
            assertEquals(404, error.code())
        }
        showLibrary()
        openAsrList()
        val visibleAliceTask = compose.onAllNodesWithTag("note-asr-task-open-${task.taskId}").fetchSemanticsNodes()
        if (visibleAliceTask.isNotEmpty()) {
            scrollToNodeEventually("note-asr-tasks", hasTestTag("note-asr-task-open-${task.taskId}"))
            compose.onNodeWithTag("note-asr-task-open-${task.taskId}").performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("note-asr-binding-mismatch-${task.taskId}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("note-asr-binding-mismatch-${task.taskId}").assertExists()
            assertTextNotInLazyList("note-asr-detail-${task.taskId}", segments[0].transcript!!)
        }

        login("notes-qa-alice")
        instrumentation.runOnMainSync { compose.activity.recreate() }
        compose.waitForIdle()
        showLibrary()
        openTask(task.taskId)
        assertTranscriptVisible(task.taskId, segments[1])
        shot("normal-after-rebuild-owner-return")
    }

    @Test
    fun localEditorHandoffFromFrozenAEditsBAndReturnsWithoutLosingOriginal() =
        captureCaseFailure("local-editor-handoff") {
        login("notes-qa-alice")
        val fixture = prepareFixture(versionCount = 1)
        val originalNote = fixture.model.notes.state.value.documents.getValue(fixture.noteId)
        val originalBlock = originalNote.blocks.single()
        val blockId = originalBlock.string("id")
        val originalText = originalBlock.string("text")
        val fixedABefore = NoteMaterialStore.at(context.noBackupFilesDir).read(fixture.versionA)
        assertEquals(originalText, fixedABefore.document.blocks.single().string("text"))

        compose.onNodeWithTag("note-document").performScrollToIndex(2)
        compose.waitUntil(15_000) { editorWebOrNull(blockId) != null }
        val web = checkNotNull(editorWebOrNull(blockId))
        assertEquals(
            "true",
            editorEval(
                web,
                "document.querySelector('.tiptap').focus();" +
                    "document.execCommand('insertText',false,' 本机编辑新稿B');true",
            ),
        )
        compose.waitUntil(15_000) {
            fixture.model.notes.state.value.documents.getValue(fixture.noteId)
                .blocks.single().string("text").contains("本机编辑新稿B")
        }
        waitSaved(fixture.model, fixture.noteId)
        val editedText = fixture.model.notes.state.value.documents.getValue(fixture.noteId)
            .blocks.single().string("text")
        assertNotEquals(originalText, editedText)

        val oldEditor = checkNotNull(editor)
        compose.onNodeWithContentDescription("返回记录列表").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("notes-library").fetchSemanticsNodes().isNotEmpty()
        }
        editor = null
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-open-${fixture.noteId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note-open-${fixture.noteId}").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            val current = resumedEditor()
            current != null && current !== oldEditor &&
                current.model.notes.state.value.loaded &&
                current.model.selectedId.value == fixture.noteId
        }
        val reopened = checkNotNull(resumedEditor())
        editor = reopened
        compose.waitUntil(15_000) {
            reopened.model.notes.state.value.documents.getValue(fixture.noteId)
                .blocks.single().string("text") == editedText
        }
        assertEquals(editedText, reopened.model.notes.state.value.documents.getValue(fixture.noteId)
            .blocks.single().string("text"))
        assertEquals(
            originalText,
            NoteMaterialStore.at(context.noBackupFilesDir).read(fixture.versionA)
                .document.blocks.single().string("text"),
        )

        val versionB = runBlocking {
            reopened.model.materials.freeze(
                reopened.model.notes.state.value.documents.getValue(fixture.noteId),
                fixture.sessions,
            )
        }
        awaitMaterialReady(reopened.model, versionB)
        val target = checkNotNull(reopened.model.uploads.target())
        val jobB = runBlocking { reopened.model.uploads.submit(versionB, target) }
        compose.waitUntil(120_000) {
            uploads.jobs().any { it.id == jobB && it.phase == NoteUploadPhase.Uploaded }
        }
        awaitRemoteSourceValue(versionB, requireCurrent = true)
        assertFalse(awaitRemoteSourceValue(fixture.versionA, requireCurrent = false).isCurrent)
        assertEquals(
            originalText,
            NoteMaterialStore.at(context.noBackupFilesDir).read(fixture.versionA)
                .document.blocks.single().string("text"),
        )
        assertEquals(editedText, NoteMaterialStore.at(context.noBackupFilesDir).read(versionB)
            .document.blocks.single().string("text"))
        openAsrForVersion(fixture.versionA)
        compose.onNodeWithTag("note-asr-source-${fixture.versionA}").assertExists()
        shot("local-editor-handoff-a-b")
    }

    @Test
    fun failureRecoverySelectsOnlyFailedSegmentAndKeepsSuccessfulTranscripts() =
        captureCaseFailure("failure-recovery") {
        login("notes-qa-alice")
        val fixture = prepareFixture(versionCount = 1)
        val task = submitFromUi(fixture.versionA)
        assertEquals(0, qaSummary().sentCalls)

        qa("control?failAfter=2")
        triggerJob(task.taskId)
        val failedTask = awaitTask(task.taskId) { it.phase == "NeedsAttention" }
        assertEquals(2, failedTask.completedSegments)
        val failedSegments = awaitSegments(task.taskId) { all ->
            all.size == 3 && all.count { it.status == "Failed" } == 1 &&
                all.count { it.status == "Succeeded" } == 2
        }
        val failed = failedSegments.single { it.status == "Failed" }
        val before = qaSummary()
        assertEquals(3, before.sentCalls)
        assertEquals(1, before.failedCalls)
        val beforeBySha = before.calls.groupingBy { it.sha256 }.eachCount()

        closeEditor()
        showLibrary()
        openTask(task.taskId)
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-retry-${task.taskId}"))
        compose.onNodeWithTag("note-asr-retry-${task.taskId}").performClick()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-select-0"))
        compose.onNodeWithTag("note-asr-segment-select-0").assertIsNotEnabled()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-select-${failed.index}"))
        compose.onNodeWithTag("note-asr-segment-select-${failed.index}").assertIsEnabled().performClick()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-select-2"))
        compose.onNodeWithTag("note-asr-segment-select-2").assertIsNotEnabled()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-retry-continue-${task.taskId}"))
        compose.onNodeWithTag("note-asr-retry-continue-${task.taskId}").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("note-asr-retry-charge-check").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note-asr-retry-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("note-asr-retry-charge-check").performClick()
        compose.onNodeWithTag("note-asr-retry-confirm").assertIsEnabled().performClick()
        awaitSegments(task.taskId) { all ->
            all.single { it.index == failed.index }.attempts.size >= 2
        }

        qa("control?clearFailure=1")
        triggerJob(task.taskId)
        awaitTask(task.taskId) { it.phase == "Succeeded" }
        val recovered = awaitSegments(task.taskId) { all ->
            all.size == 3 && all.all { it.status == "Succeeded" && !it.transcript.isNullOrBlank() }
        }
        val after = qaSummary()
        assertEquals(4, after.sentCalls)
        val afterBySha = after.calls.groupingBy { it.sha256 }.eachCount()
        val successfulBefore = before.calls.filter { it.status == "succeeded" }.map { it.sha256 }.toSet()
        val failedSha = before.calls.single { it.status == "failed" }.sha256
        assertTrue(successfulBefore.isNotEmpty())
        for (sha in successfulBefore) assertEquals(beforeBySha[sha], afterBySha[sha])
        assertEquals((beforeBySha[failedSha] ?: 0) + 1, afterBySha[failedSha])

        assertTranscriptVisible(task.taskId, recovered[0])
        assertTranscriptVisible(task.taskId, recovered[2])
        shot("failure-recovered")
    }

    @Test
    fun lostCreateAcknowledgementKeepsOneServerTaskAndNeverAutoResendsOrCreatesAnother() =
        captureCaseFailure("lost-create-acknowledgement") {
        login("notes-qa-alice")
        val fixture = prepareFixture(versionCount = 1)
        openAsrForVersion(fixture.versionA)
        compose.onNodeWithTag("note-asr-consent-check").performScrollTo().performClick()
        qa("fault?kind=create-response")
        compose.onNodeWithTag("note-asr-submit-${fixture.versionA}").assertIsEnabled().performScrollTo().performClick()

        val task = awaitTaskForVersion(fixture.versionA)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-asr-task-open-${task.taskId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        scrollToNodeEventually("note-asr-tasks", hasTestTag("note-asr-task-open-${task.taskId}"))
        compose.onNodeWithTag("note-asr-task-open-${task.taskId}").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-verification-pending-${task.taskId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, qaSummary().sentCalls)
        assertEquals(1, runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.count {
            it.versionId == fixture.versionA
        })

        waitForEnabledTag("note-asr-refresh-task-${task.taskId}")
        compose.onNodeWithTag("note-asr-refresh-task-${task.taskId}").performClick()
        compose.waitForIdle()
        assertEquals("Refreshing an unknown receipt must only query the same TaskId", 0, qaSummary().sentCalls)
        assertEquals(1, runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.count {
            it.versionId == fixture.versionA
        })
        assertEquals(0, qa("summary").let { parseSummary(it).sentCalls })
        shot("unknown-create-receipt")

        runBlocking { reader().stopNoteAsrTask(task.taskId) }
    }

    @Test
    fun stopDuringSecondSyntheticCallKeepsCompletedTextAndDoesNotSendThirdSlice() =
        captureCaseFailure("stop-during-second-call") {
        login("notes-qa-alice")
        val fixture = prepareFixture(versionCount = 1)
        val task = submitFromUi(fixture.versionA)
        closeEditor()
        showLibrary()
        openTask(task.taskId)

        qa("control?delayMs=2500")
        triggerJob(task.taskId)
        val running = awaitTask(task.taskId) { it.phase == "Running" && it.completedSegments >= 1 }
        assertTrue(running.completedSegments in 1..2)
        val completedTexts = awaitSegments(task.taskId) { all ->
            all.any { it.status == "Succeeded" && !it.transcript.isNullOrBlank() }
        }.filter { it.status == "Succeeded" && !it.transcript.isNullOrBlank() }
        assertTrue(completedTexts.isNotEmpty())

        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-stop-${task.taskId}"))
        compose.onNodeWithTag("note-asr-stop-${task.taskId}").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("确认停止") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("确认停止") and hasClickAction()).performClick()
        val stopped = awaitTask(task.taskId) { it.phase == "Stopped" }
        assertTrue(stopped.completedSegments in 1..2)
        val finalSegments = runBlocking { fetchSegments(task.taskId) }.items
        assertTrue(finalSegments.single { it.index == 0 }.status == "Succeeded")
        assertNotNull(finalSegments.single { it.index == 0 }.transcript)
        assertNull(finalSegments.single { it.index == 2 }.transcript)
        val summary = qaSummary()
        assertTrue("Stop must prevent the third slice, observed=${summary.sentCalls}", summary.sentCalls < 3)

        assertTranscriptVisible(task.taskId, completedTexts.first())
        shot("stopped-with-partial-results")
        qa("control?delayMs=0")
    }

    @Test
    fun syntheticUnknownSliceStaysNeedsAttentionAcrossRefreshAndRunUntilExplicitRecovery() =
        captureCaseFailure("synthetic-unknown") {
        login("notes-qa-alice")
        val fixture = prepareFixture(versionCount = 1)
        val task = submitFromUi(fixture.versionA)

        // Produce a real first successful slice, then stop during the second
        // call. The QA fixture replaces the second checkpoint with the exact
        // Unknown + running-receipt shape; this is synthetic, not a process kill.
        qa("control?delayMs=4000")
        triggerJob(task.taskId)
        awaitTask(task.taskId) { it.phase == "Running" && it.completedSegments >= 1 }
        awaitSentCalls(2)
        runBlocking { reader().stopNoteAsrTask(task.taskId) }
        awaitTask(task.taskId) { it.phase == "Stopped" }
        qa("unknown?taskId=${task.taskId}&segment=1")
        qa("control?delayMs=0")
        val unknown = awaitTask(task.taskId) { it.phase == "NeedsAttention" }
        assertEquals(1, unknown.completedSegments)
        assertEquals(2, unknown.failedSegments)
        assertEquals(2, qaSummary().sentCalls)

        val beforeRefresh = qaSummary()
        val successfulSliceSha = beforeRefresh.calls.first().sha256
        val successfulSliceSends = beforeRefresh.calls.count { it.sha256 == successfulSliceSha }
        val taskCountBefore = runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.count {
            it.versionId == fixture.versionA
        }
        assertEquals(1, taskCountBefore)
        closeEditor()
        showLibrary()
        openAsrList()
        waitForEnabledTag("note-asr-refresh")
        compose.onNodeWithTag("note-asr-refresh").performClick()
        scrollToNodeEventually("note-asr-tasks", hasTestTag("note-asr-task-open-${task.taskId}"))
        assertTaskPhase(task.taskId, "NeedsAttention", "存在失败或未确认片段")
        shot("unknown-list-needs-attention")
        compose.onNodeWithTag("note-asr-task-open-${task.taskId}").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-detail-${task.taskId}").fetchSemanticsNodes().isNotEmpty()
        }
        waitForEnabledTag("note-asr-refresh-task-${task.taskId}")
        compose.onNodeWithTag("note-asr-refresh-task-${task.taskId}").performClick()
        compose.waitForIdle()
        assertEquals("Refreshing an unknown slice must not submit work", beforeRefresh.sentCalls, qaSummary().sentCalls)
        assertEquals(taskCountBefore, runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.count {
            it.versionId == fixture.versionA
        })

        triggerJob(task.taskId)
        waitForQaRunIdle(task.taskId)
        Thread.sleep(500)
        assertEquals("Re-running an inactive unknown checkpoint must not resend", beforeRefresh.sentCalls, qaSummary().sentCalls)
        assertEquals(taskCountBefore, runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.count {
            it.versionId == fixture.versionA
        })

        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-retry-${task.taskId}"))
        compose.onNodeWithTag("note-asr-retry-${task.taskId}").performClick()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-${task.taskId}-0"))
        assertSegmentStatus(task.taskId, 0, "Succeeded", "内部片 1 · 成功")
        compose.onNodeWithTag("note-asr-segment-select-0").assertIsOff().assertIsNotEnabled()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-${task.taskId}-1"))
        assertSegmentStatus(task.taskId, 1, "Unknown", "内部片 2 · 待核对")
        compose.onNodeWithTag("note-asr-segment-select-1")
            .assertIsEnabled().assertIsOff().performClick().assertIsOn()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-${task.taskId}-2"))
        assertSegmentStatus(task.taskId, 2, "Blocked", "内部片 3 · 已阻止")
        compose.onNodeWithTag("note-asr-segment-select-2").assertIsOff()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-retry-continue-${task.taskId}"))
        compose.onNodeWithTag("note-asr-retry-continue-${task.taskId}").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("可能重复", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note-asr-retry-charge-check").performClick()
        compose.onNodeWithTag("note-asr-retry-confirm").assertIsEnabled().performClick()

        val afterRetryIntent = awaitSegments(task.taskId) {
            it.single { segment -> segment.index == 1 }.attempts.size >= 2
        }
        assertEquals(2, afterRetryIntent.single { it.index == 1 }.attempts.size)
        assertEquals(1, afterRetryIntent.single { it.index == 0 }.attempts.size)
        assertEquals(beforeRefresh.sentCalls, qaSummary().sentCalls)
        triggerJob(task.taskId)
        waitForQaRunIdle(task.taskId)
        awaitTask(task.taskId) { it.phase == "NeedsAttention" }
        val afterRecovery = awaitSegments(task.taskId) {
            it.single { segment -> segment.index == 1 }.status == "Succeeded" &&
                it.single { segment -> segment.index == 2 }.status == "Blocked"
        }
        assertEquals(1, afterRecovery.single { it.index == 0 }.attempts.size)
        val afterSends = qaSummary()
        assertEquals(beforeRefresh.sentCalls + 1, afterSends.sentCalls)
        assertEquals(successfulSliceSends, afterSends.calls.count { it.sha256 == successfulSliceSha })
        assertEquals(taskCountBefore, runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.count {
            it.versionId == fixture.versionA
        })

        waitForEnabledTag("note-asr-refresh-task-${task.taskId}")
        compose.onNodeWithTag("note-asr-refresh-task-${task.taskId}").performClick()
        scrollToNodeEventually("note-asr-detail-${task.taskId}", hasTestTag("note-asr-segment-${task.taskId}-1"))
        assertSegmentStatus(task.taskId, 1, "Succeeded", "内部片 2 · 成功")
        shot("unknown-synthetic-explicit-recovery")
    }

    private fun isolatedServerOnly() {
        val supplied = InstrumentationRegistry.getArguments().getString("nativeNoteAsrQaApi")
        assumeTrue("No isolated note ASR QA server supplied", supplied != null)
        base = checkNotNull(supplied)
        val target = PimServerEndpoints.from(base)
        require(target.webOrigin.host == "127.0.0.1" && target.webOrigin.port != 5858) {
            "ASR device QA must use its own loopback Test API port"
        }
    }

    private fun login(username: String) {
        closeAsrSurface()
        closeEditor()
        showMain()
        compose.onNodeWithTag("pim-nav-settings").performClick()
        val logouts = compose.onAllNodesWithText("退出登录").fetchSemanticsNodes()
        if (logouts.isNotEmpty()) compose.onNodeWithText("退出登录").performScrollTo().performClick()
        val apiField = hasSetTextAction() and hasText("API 地址")
        compose.onNode(apiField).performScrollTo().performTextReplacement(base)
        closeSoftKeyboard()
        compose.onNodeWithText("保存", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("用户名")).performScrollTo().performTextReplacement(username)
        compose.onNode(hasSetTextAction() and hasText("密码")).performScrollTo().performTextReplacement("password123")
        closeSoftKeyboard()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("登录") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("登录", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(20_000) { freshTokens().snapshot().ownerName == username }
    }

    private fun closeAsrSurface() {
        if (compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isEmpty()) return
        val taskListBack = compose.onAllNodesWithContentDescription("返回任务列表").fetchSemanticsNodes()
        if (taskListBack.isNotEmpty()) {
            compose.onNodeWithContentDescription("返回任务列表").performClick()
        }
        val close = compose.onAllNodesWithTag("note-asr-close").fetchSemanticsNodes()
        if (close.isNotEmpty()) compose.onNodeWithTag("note-asr-close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isEmpty()
        }
    }

    private data class PreparedFixture(
        val model: NotesViewModel,
        val noteId: String,
        val versionA: String,
        val versionB: String?,
        val sessions: List<RecordingSession>,
    )

    private fun prepareFixture(versionCount: Int): PreparedFixture {
        require(versionCount in 1..2)
        showLibrary()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("notes-create") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(15_000) {
            instrumentation.runOnMainSync {
                editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NativeNotesActivity>().firstOrNull()
            }
            editor != null
        }
        val activity = checkNotNull(editor)
        val model = activity.model
        compose.waitUntil(10_000) { model.notes.state.value.loaded && model.materials.state.value.loaded }
        compose.waitUntil(10_000) { model.selectedId.value != null }
        val noteId = checkNotNull(model.selectedId.value)
        compose.runOnUiThread {
            model.notes.update(noteId) {
                it.withTitle("ASR 固定稿 A").withBlocks(listOf(markdownBlock(
                    "# 合成 ASR 课堂\n\n这是用于验证固定版本、明确同意和逐段任务状态的合成正文。"
                )))
            }
        }
        waitSaved(model, noteId)

        val recording = RecordingEngine(
            RecordingFileStore(File(context.noBackupFilesDir, "recordings")),
            RecordingPolicy(),
            noteId = noteId
        )
        val pcm = ShortArray(16_000 * 121) { index ->
            (((index / 16_000) * 73 + index % 97) % 30_000).toShort()
        }
        recording.append(pcm, pcm.size)
        recording.finish()
        val sessions = listOf(recording.session)
        val versionA = runBlocking {
            model.materials.freeze(model.notes.state.value.documents.getValue(noteId), sessions)
        }
        awaitMaterialReady(model, versionA)

        var versionB: String? = null
        if (versionCount == 2) {
            compose.runOnUiThread {
                model.notes.update(noteId) {
                    it.withTitle("ASR 新稿 B").withBlocks(listOf(markdownBlock(
                        "# ASR 新稿 B\n\n这份更新后的正文不能替换已经固定并上传的版本 A。"
                    )))
                }
            }
            waitSaved(model, noteId)
            versionB = runBlocking {
                model.materials.freeze(model.notes.state.value.documents.getValue(noteId), sessions)
            }
            awaitMaterialReady(model, versionB)
        }

        val target = checkNotNull(model.uploads.target()) { "QA account must be bound to the isolated server" }
        val jobA = runBlocking { model.uploads.submit(versionA, target) }
        compose.waitUntil(120_000) {
            uploads.jobs().any { it.id == jobA && it.phase == NoteUploadPhase.Uploaded }
        }
        awaitRemoteSource(versionA)
        val jobB = versionB?.let { runBlocking { model.uploads.submit(it, target) } }
        if (jobB != null) {
            compose.waitUntil(120_000) {
                uploads.jobs().any { it.id == jobB && it.phase == NoteUploadPhase.Uploaded }
            }
            awaitRemoteSource(checkNotNull(versionB) { "Two-version QA fixture must expose version B" })
        }
        return PreparedFixture(model, noteId, versionA, versionB, sessions)
    }

    private fun resumedEditor(): NativeNotesActivity? {
        var current: NativeNotesActivity? = null
        instrumentation.runOnMainSync {
            current = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<NativeNotesActivity>()
                .firstOrNull()
        }
        return current
    }

    private fun editorWebOrNull(blockId: String): OfflineMarkdownView? {
        var view: OfflineMarkdownView? = null
        compose.runOnUiThread {
            view = editor?.window?.decorView?.let { root ->
                descendants(root).filterIsInstance<OfflineMarkdownView>()
                    .firstOrNull { it.blockId == blockId }
            }
        }
        return view
    }

    private fun editorEval(view: OfflineMarkdownView, script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        compose.runOnUiThread { view.evaluateJavascript(script) { result = it; done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS)) { "Editor WebView script did not respond" }
        return result
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun waitSaved(model: NotesViewModel, noteId: String) = compose.waitUntil(15_000) {
        val note = model.notes.state.value.documents[noteId]
        note != null && model.notes.state.value.savedRevisions[noteId] == note.revision
    }

    private fun awaitMaterialReady(model: NotesViewModel, versionId: String) = compose.waitUntil(30_000) {
        model.materials.state.value.entries.any { it.id == versionId && it.phase == NoteMaterialPhase.Ready }
    }

    private fun openAsrForVersion(versionId: String) {
        compose.onNodeWithContentDescription("更多文档操作").performClick()
        compose.onNodeWithTag("note-material-versions").performClick()
        compose.onNodeWithTag("material-asr-$versionId").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-consent-$versionId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-consent-check").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun submitFromUi(versionId: String): NoteAsrTaskDto {
        openAsrForVersion(versionId)
        compose.onNodeWithTag("note-asr-consent-check").performScrollTo().performClick()
        compose.onNodeWithTag("note-asr-submit-$versionId").assertIsEnabled().performScrollTo().performClick()
        return awaitTaskForVersion(versionId)
    }

    private fun closeEditor() {
        editor?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        editor = null
        compose.waitForIdle()
    }

    private fun showMain() {
        instrumentation.runOnMainSync {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        compose.waitForIdle()
    }

    private fun showLibrary() {
        showMain()
        compose.onNodeWithTag("pim-nav-notes").performClick()
    }

    private fun openAsrList() {
        if (compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isNotEmpty()) return
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note-asr-open").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openTask(taskId: String) {
        openAsrList()
        waitForEnabledTag("note-asr-refresh")
        compose.onNodeWithTag("note-asr-refresh").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-asr-task-open-$taskId").fetchSemanticsNodes().isNotEmpty()
        }
        scrollToNodeEventually("note-asr-tasks", hasTestTag("note-asr-task-open-$taskId"))
        compose.onNodeWithTag("note-asr-task-open-$taskId").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-asr-detail-$taskId").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTranscriptVisible(taskId: String, segment: NoteAsrSegmentDto) {
        val transcript = checkNotNull(segment.transcript)
        compose.onNodeWithTag("note-asr-detail-$taskId").assertExists()
        waitForEnabledTag("note-asr-refresh-task-$taskId")
        compose.onNodeWithTag("note-asr-refresh-task-$taskId").performClick()
        scrollToNodeEventually("note-asr-detail-$taskId", hasTestTag("note-asr-segment-$taskId-${segment.index}"))
        compose.onNodeWithText(transcript, substring = true).assertExists()
    }

    private fun assertTaskPhase(taskId: String, wirePhase: String, exactLabel: String) {
        scrollToNodeEventually("note-asr-tasks", hasTestTag("note-asr-phase-$taskId"))
        val matcher = hasTestTag("note-asr-phase-$taskId") and
            hasStateDescription(wirePhase) and hasText(exactLabel)
        compose.waitUntil(30_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(matcher).assertCountEquals(1)
    }

    private fun assertSegmentStatus(
        taskId: String,
        index: Int,
        wireStatus: String,
        exactLabel: String,
    ) {
        scrollToNodeEventually("note-asr-detail-$taskId", hasTestTag("note-asr-segment-$taskId-$index"))
        val matcher = hasTestTag("note-asr-segment-status-$taskId-$index") and
            hasStateDescription(wireStatus) and hasText(exactLabel)
        compose.onAllNodes(matcher).assertCountEquals(1)
    }

    private fun scrollToNodeEventually(parentTag: String, matcher: SemanticsMatcher) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            try {
                compose.onNodeWithTag(parentTag).performScrollToNode(matcher)
                return
            } catch (_: AssertionError) {
                compose.waitForIdle()
                Thread.sleep(250)
            }
        }
        throw AssertionError("Could not scroll $parentTag to the expected node")
    }

    private fun waitForEnabledTag(tag: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTextNotInLazyList(parentTag: String, text: String) {
        val found = try {
            compose.onNodeWithTag(parentTag).performScrollToNode(hasText(text, substring = true))
            true
        } catch (_: AssertionError) {
            false
        }
        assertFalse("Bound-owner mismatch exposed prior-owner transcript text", found)
    }

    private fun captureCaseFailure(caseName: String, body: () -> Unit) {
        var failure: Throwable? = null
        try {
            body()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            captureCaseEvidence(caseName, failure)
        }
    }

    private fun captureCaseEvidence(caseName: String, failure: Throwable?) {
        val notes = mutableListOf<String>()
        try {
            val safeName = caseName.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
            val status = if (failure == null) "pass" else "fail"
            val root = File(
                context.getExternalFilesDir(null) ?: context.filesDir,
                "native-note-asr-qa/case-evidence",
            ).apply { mkdirs() }
            val dir = File(root, "$status-$safeName-${System.currentTimeMillis()}").apply { mkdirs() }

            try {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    File(dir, "screen.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                } else {
                    notes += "screenshot unavailable"
                }
            } catch (error: Throwable) {
                notes += "screenshot failed: ${error.message}"
            }

            try {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                val tree = StringBuilder("rootCount=${roots.size}\n")
                var visited = 0
                fun appendNode(node: SemanticsNode, depth: Int) {
                    if (visited++ >= 4_000 || tree.length >= 262_144) return
                    val bounds = node.boundsInRoot
                    tree.append("  ".repeat(depth))
                        .append("node=${node.id} bounds=$bounds tag=")
                        .append(node.config)
                        .append('\n')
                    node.children.forEach { child -> appendNode(child, depth + 1) }
                }
                roots.forEachIndexed { index, rootNode ->
                    tree.append("root[$index]\n")
                    appendNode(rootNode, 1)
                }
                File(dir, "compose-tree.txt").writeText(tree.toString())
            } catch (error: Throwable) {
                notes += "compose tree failed: ${error.message}"
            }

            try {
                val taskRoot = File(context.noBackupFilesDir, "note-asr-tasks")
                val tasks = taskRoot.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".json") }
                    ?.sortedBy { it.name }
                    .orEmpty()
                val contents = buildString {
                    tasks.forEach { task ->
                        append("=== ${task.name}\n")
                        append(task.readText())
                        append('\n')
                    }
                }
                File(dir, "local-task-json.txt").writeText(contents)
                notes += "localTaskFileCount=${tasks.size}"
            } catch (error: Throwable) {
                notes += "local task files failed: ${error.message}"
            }

            val summary = try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS)
                    .build()
                val origin = PimServerEndpoints.from(base).trustedOrigin
                client.newCall(Request.Builder().url("$origin/__qa/asr/summary").build()).execute().use {
                    check(it.isSuccessful) { "QA summary returned ${it.code}" }
                    it.body!!.string()
                }
            } catch (error: Throwable) {
                notes += "qa summary failed: ${error.message}"
                null
            }
            if (summary != null) File(dir, "qa-summary.json").writeText(summary)

            val result = buildString {
                append("case=$caseName\n")
                append("status=${if (failure == null) "PASS" else "FAIL"}\n")
                append("capturedAt=${java.time.OffsetDateTime.now()}\n")
                append("failure=${failure?.let { "${it::class.qualifiedName}: ${it.message}" } ?: "none"}\n")
                append("sentCalls=${summary?.let { parseSummary(it).sentCalls } ?: "unavailable"}\n")
                append("runningTaskIds=${summary?.let { parseSummary(it).runningTaskIds } ?: "unavailable"}\n")
                notes.forEach { append("captureNote=$it\n") }
                failure?.let { append("\n").append(it.stackTraceToString()) }
            }
            File(dir, "case-result.txt").writeText(result)
        } catch (error: Throwable) {
            notes += "capture wrapper failed: ${error.message}"
            try {
                val fallbackRoot = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "native-note-asr-qa/case-evidence",
                ).apply { mkdirs() }
                File(fallbackRoot, "capture-wrapper-error-${System.currentTimeMillis()}.txt").writeText(
                    buildString {
                        append("case=$caseName\n")
                        append("originalFailure=$failure\n")
                        append("captureFailure=$error\n")
                        notes.forEach { append("captureNote=$it\n") }
                    },
                )
            } catch (_: Throwable) {
                // Capture must never replace the original test failure.
            }
        }
    }

    private fun triggerJob(taskId: String) {
        waitForQaRunIdle(taskId)
        val root = Json.parseToJsonElement(qa("run?taskId=$taskId")).jsonObject
        assertTrue(root.getValue("accepted").jsonPrimitive.boolean)
    }

    private fun waitForQaRunIdle(taskId: String) {
        val deadline = System.nanoTime() + 30_000_000_000L
        var last = emptyList<String>()
        while (System.nanoTime() < deadline) {
            last = qaSummary().runningTaskIds
            if (taskId !in last) return
            Thread.sleep(100)
        }
        throw AssertionError("QA provider run for $taskId remained active; runningTaskIds=$last")
    }

    private fun awaitTaskForVersion(versionId: String): NoteAsrTaskDto {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val task = runBlocking { reader().getNoteAsrTasks().data.orEmpty() }
                .firstOrNull { it.versionId == versionId }
            if (task != null) return task
            Thread.sleep(250)
        }
        throw AssertionError("No ASR task appeared for fixed version $versionId")
    }

    private fun awaitTask(taskId: String, predicate: (NoteAsrTaskDto) -> Boolean): NoteAsrTaskDto {
        val deadline = System.nanoTime() + 120_000_000_000L
        var last: NoteAsrTaskDto? = null
        while (System.nanoTime() < deadline) {
            val current = runBlocking { reader().getNoteAsrTask(taskId).data }
            last = current
            if (current != null && predicate(current)) return current
            Thread.sleep(250)
        }
        throw AssertionError("ASR task $taskId did not reach the expected state; last=$last")
    }

    private fun awaitSegments(
        taskId: String,
        predicate: (List<NoteAsrSegmentDto>) -> Boolean = { true }
    ): List<NoteAsrSegmentDto> {
        val deadline = System.nanoTime() + 30_000_000_000L
        var last = emptyList<NoteAsrSegmentDto>()
        while (System.nanoTime() < deadline) {
            last = runBlocking { fetchSegments(taskId) }.items
            if (predicate(last)) return last
            Thread.sleep(250)
        }
        throw AssertionError("ASR segments for $taskId did not reach the expected state: $last")
    }

    private suspend fun fetchSegments(taskId: String) =
        checkNotNull(reader().getNoteAsrSegments(taskId, offset = 0, limit = 20).data)

    private fun awaitRemoteSource(versionId: String) =
        awaitRemoteSourceValue(versionId, requireCurrent = false)

    private fun awaitRemoteSourceValue(versionId: String, requireCurrent: Boolean) =
        waitForRemote(30_000, "source $versionId") {
            runBlocking { reader().getNoteAsrSources(offset = 0, limit = 20).data?.items }
                ?.firstOrNull { it.versionId == versionId }
                ?.takeIf { !requireCurrent || it.isCurrent }
        }

    private fun assertNoTaskForVersion(versionId: String) {
        assertTrue(
            "Material upload must not create an ASR task",
            runBlocking { reader().getNoteAsrTasks().data.orEmpty() }.none { it.versionId == versionId }
        )
    }

    private fun <T : Any> waitForRemote(timeoutMs: Long, label: String, read: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            try {
                read()?.let { return it }
            } catch (_: Exception) {
                // The isolated API may still be committing the preceding upload/task.
            }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun freshTokens() = TokenManager(context)

    private fun reader(): ApiService {
        val tokens = freshTokens()
        val settings = ServerSettingsStore(context, tokens)
        val identity = tokens.snapshot()
        val binding = BoundUploadSession(checkNotNull(identity.serverIdentity), checkNotNull(identity.ownerId))
        val client = OkHttpClient.Builder().addInterceptor(
            AuthInterceptor(
                tokens,
                AuthRefreshCoordinator(tokens, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }),
                settings
            )
        ).build()
        return ApiClientProvider(client, Json { ignoreUnknownKeys = true }, settings).noteUploadApiService(binding)
    }

    private fun qa(path: String): String {
        val origin = PimServerEndpoints.from(base).trustedOrigin
        return OkHttpClient().newCall(Request.Builder().url("$origin/__qa/asr/$path").build()).execute().use { response ->
            check(response.isSuccessful) { "QA endpoint $path returned ${response.code}" }
            response.body!!.string()
        }
    }

    private data class QaCall(val sequence: Int, val sha256: String, val status: String)
    private data class QaSummary(
        val sentCalls: Int,
        val successfulCalls: Int,
        val failedCalls: Int,
        val calls: List<QaCall>,
        val runningTaskIds: List<String>
    )

    private fun qaSummary() = parseSummary(qa("summary"))

    private fun awaitSentCalls(expectedMinimum: Int) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (qaSummary().sentCalls >= expectedMinimum) return
            Thread.sleep(100)
        }
        fail("Synthetic ASR did not reach $expectedMinimum sends")
    }

    private fun parseSummary(raw: String): QaSummary {
        val root = Json.parseToJsonElement(raw).jsonObject
        return QaSummary(
            sentCalls = root.getValue("sentCalls").jsonPrimitive.int,
            successfulCalls = root.getValue("successfulCalls").jsonPrimitive.int,
            failedCalls = root.getValue("failedCalls").jsonPrimitive.int,
            calls = root.getValue("calls").jsonArray.map {
                val call = it.jsonObject
                QaCall(
                    call.getValue("sequence").jsonPrimitive.int,
                    call.getValue("sha256").jsonPrimitive.content,
                    call.getValue("status").jsonPrimitive.content
                )
            },
            runningTaskIds = root["runningTaskIds"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty(),
        )
    }

    private data class QaModel(
        val displayName: String,
        val model: String,
        val modelRevision: String,
        val endpoint: String,
        val presetId: String,
        val presetVersion: String
    )
    private data class QaTarget(val model: QaModel)

    private fun parseTarget(raw: String): QaTarget {
        val model = Json.parseToJsonElement(raw).jsonObject.getValue("model").jsonObject
        return QaTarget(QaModel(
            model.getValue("displayName").jsonPrimitive.content,
            model.getValue("model").jsonPrimitive.content,
            model.getValue("modelRevision").jsonPrimitive.content,
            model.getValue("endpoint").jsonPrimitive.content,
            model.getValue("presetId").jsonPrimitive.content,
            model.getValue("presetVersion").jsonPrimitive.content
        ))
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val profile = InstrumentationRegistry.getArguments()
            .getString("nativeNoteAsrQaProfile", "phone")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val folder = File(context.getExternalFilesDir(null), "native-note-asr-qa").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(folder, "$profile-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

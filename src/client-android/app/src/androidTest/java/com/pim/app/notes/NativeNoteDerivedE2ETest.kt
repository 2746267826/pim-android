package com.pim.app.notes

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.pim.app.MainActivity
import com.pim.app.recording.RecordingEngine
import com.pim.app.recording.RecordingFileStore
import com.pim.app.recording.RecordingPolicy
import com.pim.core.auth.TokenManager
import com.pim.core.auth.AuthRefreshOperation
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.contract.NoteDerivedArtifactDto
import com.pim.core.contract.NoteDerivedArtifactKind
import com.pim.core.contract.NoteDerivedDestinationDto
import com.pim.core.contract.NoteDerivedTaskDto
import com.pim.core.contract.NoteDerivedVersionAuthor
import com.pim.core.contract.NoteDerivedVersionDto
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.network.AuthInterceptor
import com.pim.core.network.AuthRefreshCoordinator
import com.pim.core.network.BoundUploadSession
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/**
 * Real-device ordinary-user E2E for fixed-material derived note results.
 *
 * The app talks to the actual Test API through [com.pim.core.network.ApiService].
 * ASR, text and vision providers are synthetic loopback processes. The test
 * drives product UI for note creation, material freeze, ASR consent, target
 * consent, generation, human save, regeneration, refresh and explicit recovery.
 * Test-only API reads verify durable server state and provider receipts.
 *
 * Required instrumentation arguments:
 * - nativeNoteDerivedQaApi=http://127.0.0.1:<isolated-port>/api/v1/
 * - nativeNoteDerivedQaProfile=phone|narrow
 * - nativeNoteDerivedQaUsername=<ordinary Test API account>
 */
class NativeNoteDerivedE2ETest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            isolatedServerOnly()
            qa("health")
            qa("provider/control?reset=1")
        }
    }).around(compose)

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = compose.activity.applicationContext
    private lateinit var base: String
    private var editor: NativeNotesActivity? = null
    private var activeFixture: Fixture? = null
    private var humanSaveProbe: Map<String, String>? = null

    @After
    fun closeOwnedSurfaces() {
        runCatching { closeArtifacts() }
        runCatching { closeAsrSurface() }
        editor?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        editor = null
    }

    @Test
    fun ordinaryAccountGeneratesBothKindsSavesHumanAndRegeneratesWithoutLosingHuman() =
        captureCaseEvidence("normal-human-regenerate") {
            val fixture = prepareAsrAndOpenDerived("normal")
            val providerBeforeDerived = providerSummary()
            val firstTask = startDerivedFromEntry(fixture, expectBothKinds = true)
            val firstRun = runDerivedAndVerify(fixture, firstTask.taskId, providerBeforeDerived)
            assertEquals(NoteDerivedArtifactKind.COMPLETERECORD, firstRun.artifact(NoteArtifactKind.FullRecord).kind)
            assertEquals(NoteDerivedArtifactKind.INDEPENDENTANALYSIS, firstRun.artifact(NoteArtifactKind.Analysis).kind)
            assertEquals(3, firstRun.task.steps.size)
            assertTrue(firstRun.task.steps.all { it.status == "Succeeded" })
            assertCompleteCoverage(firstRun.task)
            refreshArtifactScreen()
            assertCoverageVisibleComplete()
            shot("normal-artifacts")

            val humanText = "# 人工完整记录 ${UUID.randomUUID()}\n\n这段文字由普通用户显式保存为服务端人工稿。"
            val human = saveHumanThroughUi(NoteArtifactKind.FullRecord, humanText)
            assertEquals(NoteDerivedVersionAuthor.USER, human.author)
            assertEquals(humanText, human.contentMarkdown)
            assertEquals(human.versionId, mustArtifact(fixture.noteId, NoteArtifactKind.FullRecord).headVersionId)
            shot("human-head")

            val taskIdsBefore = derivedTasks(fixture.noteId).map { it.taskId }.toSet()
            val providerBeforeRegenerate = providerSummary()
            regenerateThroughUi(NoteArtifactKind.FullRecord)
            val regeneratedTask = waitForNewDerivedTask(fixture, taskIdsBefore)
            val regeneratedRun = runDerivedAndVerify(fixture, regeneratedTask.taskId, providerBeforeRegenerate)
            assertEquals(3, regeneratedRun.task.steps.size)
            assertTrue(regeneratedRun.task.steps.all { it.status == "Succeeded" })

            val completeAfterRegenerate = mustArtifact(fixture.noteId, NoteArtifactKind.FullRecord)
            assertEquals("Regeneration must not replace the human head", human.versionId, completeAfterRegenerate.headVersionId)
            val regeneratedCandidates = completeAfterRegenerate.versions.filter {
                it.generationTaskId == regeneratedTask.taskId
            }
            assertEquals(1, regeneratedCandidates.size)
            assertFalse(regeneratedCandidates.single().isHead)
            assertEquals(human.versionId, regeneratedCandidates.single().parentVersionId)

            refreshArtifactScreen()
            scrollToNodeEventually(
                "note-artifact-list",
                hasTestTag("note-artifact-candidate-FullRecord-${regeneratedCandidates.single().versionId}"),
            )
            compose.onNodeWithTag(
                "note-artifact-candidate-FullRecord-${regeneratedCandidates.single().versionId}",
            ).assertIsDisplayed()
            compose.onNodeWithTag(
                "note-artifact-current-FullRecord-${human.versionId}",
            ).assertExists()
            compose.onNodeWithTag("note-artifact-editor-FullRecord", useUnmergedTree = true)
                .assertTextContains(humanText, substring = true)

            val dirtyText = "# 未同步本机修改 ${UUID.randomUUID()}\n\n刷新不得覆盖这段文字。"
            enterDraftText(NoteArtifactKind.FullRecord, dirtyText)
            compose.onNodeWithTag("note-artifact-dirty-state-FullRecord")
                .assertTextContains("本机未同步", substring = true)
            refreshArtifactScreen()
            compose.onNodeWithTag("note-artifact-editor-FullRecord", useUnmergedTree = true)
                .assertTextContains(dirtyText, substring = true)
            compose.onNodeWithTag("note-artifact-dirty-state-FullRecord")
                .assertTextContains("本机未同步", substring = true)
            assertEquals("Refresh must not call a model", providerBeforeRegenerate.modelCallCount + 3, providerSummary().modelCallCount)
            shot("dirty-refresh-preserved")
        }

    @Test
    fun concurrentServerWriteKeepsBothVersionsAndTheLocalDraft() =
        captureCaseEvidence("concurrent-conflict") {
            val fixture = prepareAsrAndOpenDerived("concurrent")
            val task = startDerivedFromEntry(fixture, expectBothKinds = false)
            runDerivedAndVerify(fixture, task.taskId, providerSummary())

            val humanText = "# 人工基线 ${UUID.randomUUID()}"
            val human = saveHumanThroughUi(NoteArtifactKind.FullRecord, humanText)
            refreshArtifactScreen()
            val providerBeforeConflict = providerSummary()

            val localText = "# 本机并发稿 ${UUID.randomUUID()}\n\n必须保留。"
            enterDraftText(NoteArtifactKind.FullRecord, localText)
            saveLocalDraftThroughUi(NoteArtifactKind.FullRecord)

            // The test client is a real second API writer, not a mocked transport.
            val current = mustArtifact(fixture.noteId, NoteArtifactKind.FullRecord)
            val foreignText = "# 并发服务端稿 ${UUID.randomUUID()}"
            val foreign = runBlocking {
                reader().saveNoteDerivedManualRevision(
                    current.artifactId,
                    com.pim.core.contract.SaveNoteDerivedManualRevisionRequest(
                        contentMarkdown = foreignText,
                        expectedHeadRevision = current.revision,
                        expectedHeadVersionId = human.versionId,
                        revisionId = UUID.randomUUID().toString(),
                    ),
                ).data ?: error("Concurrent server write returned no receipt")
            }
            assertTrue(foreign.isHead)

            // UI still holds the pre-foreign revision, so this must be a server CAS conflict.
            compose.onNodeWithTag("note-artifact-save-server-FullRecord")
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            val conflicting = waitForVersion(
                fixture.noteId,
                NoteArtifactKind.FullRecord,
            ) { version ->
                version.contentMarkdown == localText && version.hasConflict && !version.isHead
            }
            assertTrue(conflicting.hasConflict)
            assertFalse(conflicting.isHead)
            val afterConflict = mustArtifact(fixture.noteId, NoteArtifactKind.FullRecord)
            assertEquals(foreign.versionId, afterConflict.headVersionId)
            val foreignVersion = getVersion(afterConflict.artifactId, foreign.versionId)
            assertEquals(foreignText, foreignVersion.contentMarkdown)

            refreshArtifactScreen()
            compose.onNodeWithTag("note-artifact-editor-FullRecord", useUnmergedTree = true)
                .assertTextContains(localText, substring = true)
            scrollToNodeEventually(
                "note-artifact-list",
                hasTestTag("note-artifact-candidate-FullRecord-${conflicting.versionId}"),
            )
            compose.onNodeWithTag("note-artifact-candidate-FullRecord-${conflicting.versionId}")
                .assertIsDisplayed()
            assertTrue(
                "Conflict must not invoke a model",
                providerSummary().modelCallCount == providerBeforeConflict.modelCallCount,
            )
            shot("concurrent-conflict")
        }

    @Test
    fun lostCreateReceiptRequiresExplicitSameTaskRecoveryAndNeverAutoResends() =
        captureCaseEvidence("unknown-start-recovery") {
            val fixture = prepareAsrAndOpenDerived("unknown")
            qa("faults/arm?kind=derived-start-response")
            val providerBefore = providerSummary()
            val task = startDerivedFromEntry(fixture, expectBothKinds = false)
            val localTask = NoteArtifactTaskStore.at(context.noBackupFilesDir)
                .tasks()
                .single { it.taskId == task.taskId }
            assertEquals(NoteArtifactIntentPhase.VerificationPending, localTask.localPhase)
            assertEquals(null, localTask.remote)
            assertEquals(
                "Lost create acknowledgement must not call a provider",
                providerBefore.modelCallCount,
                providerSummary().modelCallCount,
            )
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag("note-artifact-recover-FullRecord")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("note-artifact-recovery-notice-FullRecord")
                .assertTextContains("结果未知", substring = true)
            shot("unknown-start")

            compose.onNodeWithTag("note-artifact-recover-FullRecord")
                .performScrollTo()
                .performClick()
            val recovered = waitForDerivedTask(fixture.noteId, task.taskId) { it.revision.isNotBlank() }
            assertEquals(task.taskId, recovered.taskId)
            assertEquals(1, derivedTasks(fixture.noteId).size)
            assertEquals(
                "Explicit recovery must not invoke a model",
                providerBefore.modelCallCount,
                providerSummary().modelCallCount,
            )

            val beforeRun = providerSummary()
            runDerivedAndVerify(fixture, task.taskId, beforeRun)
            refreshArtifactScreen()
            assertCoverageVisibleComplete()
            shot("unknown-start-recovered")
        }

    private data class Fixture(
        val model: NotesViewModel,
        val noteId: String,
        val versionId: String,
        val title: String,
        val asrTask: NoteAsrTaskDto,
        val destination: NoteDerivedDestinationDto,
        val imageBytes: Map<String, ByteArray>,
    )

    private data class DerivedRun(
        val task: NoteDerivedTaskDto,
        val artifacts: Map<NoteArtifactKind, NoteDerivedArtifactDto>,
    ) {
        fun artifact(kind: NoteArtifactKind): NoteDerivedArtifactDto =
            checkNotNull(artifacts[kind]) { "Missing $kind artifact" }
    }

    private data class ProviderSummary(
        val modelCallCount: Int,
        val asrCalls: Int,
        val visionCalls: Int,
        val textCalls: Int,
        val callHashes: List<String>,
        val receivedImages: List<ProviderImage>,
    )

    private data class ProviderImage(
        val sha256: String,
        val bytes: Int,
    )

    private fun isolatedServerOnly() {
        val supplied = InstrumentationRegistry.getArguments().getString("nativeNoteDerivedQaApi")
        assumeTrue("No isolated note-derived QA server supplied", supplied != null)
        base = checkNotNull(supplied)
        val target = PimServerEndpoints.from(base)
        require(target.webOrigin.host == "127.0.0.1" && target.webOrigin.port != 5858) {
            "Derived device QA must use its own loopback Test API port"
        }
    }

    private fun prepareAsrAndOpenDerived(label: String): Fixture {
        val username = InstrumentationRegistry.getArguments()
            .getString("nativeNoteDerivedQaUsername", "note-derived-qa-alice")
        login(username)
        val prepared = prepareFixture(label)
        val baseline = providerSummary()
        val asrTask = submitAsrAndRun(prepared, baseline)
        openCompletedAsrTask(asrTask.taskId)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-asr-open-derived-${asrTask.taskId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note-asr-open-derived-${asrTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-artifact-entry").fetchSemanticsNodes().isNotEmpty()
        }
        val destination = runBlocking {
            reader().getNoteDerivedDestinations().data?.items
                ?.singleOrNull { it.modelId == prepared.destinationId }
        } ?: error("The ordinary account did not receive the expected derived destination")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-artifact-entry-destination-${destination.modelId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        scrollToNodeEventually(
            "note-artifact-entry-list",
            hasTestTag("note-artifact-entry-charge-notice"),
        )
        compose.onNodeWithTag("note-artifact-entry-charge-notice").assertExists()
        scrollToNodeEventually(
            "note-artifact-entry-list",
            hasTestTag("note-artifact-entry-destination-${destination.modelId}"),
        )
        assertTargetConsentVisible(destination)
        return Fixture(
            prepared.model,
            prepared.noteId,
            prepared.versionId,
            prepared.title,
            asrTask,
            destination,
            prepared.imageBytes,
        ).also { activeFixture = it }
    }

    private data class PreparedFixture(
        val model: NotesViewModel,
        val noteId: String,
        val versionId: String,
        val title: String,
        val destinationId: String,
        val imageBytes: Map<String, ByteArray>,
    )

    private fun prepareFixture(label: String): PreparedFixture {
        val qaFixture = Json.parseToJsonElement(qa("fixture")).jsonObject
        showLibrary()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("notes-create") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(20_000) {
            instrumentation.runOnMainSync {
                editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NativeNotesActivity>().firstOrNull()
            }
            editor != null
        }
        val activity = checkNotNull(editor)
        val model = activity.model
        compose.waitUntil(15_000) { model.notes.state.value.loaded && model.materials.state.value.loaded }
        compose.waitUntil(10_000) { model.selectedId.value != null }
        val noteId = checkNotNull(model.selectedId.value)
        val title = "Derived Android QA $label ${UUID.randomUUID()}"
        compose.runOnUiThread {
            model.notes.update(noteId) {
                it.withTitle(title).withBlocks(
                    listOf(
                        markdownBlock(
                            "# 固定材料 $label\n\n" +
                                "合成普通账号材料，用于验证真实 Test API、固定版本、" +
                                "三片 ASR、完整记录与独立分析。",
                        ),
                    ),
                )
            }
        }
        waitSaved(model, noteId)
        val markdown = model.notes.state.value.documents.getValue(noteId).blocks.single()
        val photoBytes = ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(24, 106, 87))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
        }.toByteArray()
        compose.runOnUiThread {
            model.prepareMaterial(noteId, NoteInsertion(markdown.string("id"), markdown.string("text").length))
            model.importPhoto({ photoBytes.inputStream() })
        }
        compose.waitUntil(20_000) {
            !model.busy.value &&
                model.notes.state.value.documents.getValue(noteId).blocks.any { it.string("kind") == "photo" }
        }
        val ink = inkBlock(width = 360f)
        compose.runOnUiThread {
            model.notes.update(noteId) { note ->
                note.withBlocks(note.blocks + ink)
            }
        }
        waitSaved(model, noteId)

        val recording = RecordingEngine(
            RecordingFileStore(File(context.noBackupFilesDir, "recordings")),
            RecordingPolicy(),
            noteId = noteId,
        )
        val pcm = ShortArray(16_000 * 121) { index ->
            (((index / 16_000) * 73 + index % 97) % 30_000).toShort()
        }
        recording.append(pcm, pcm.size)
        recording.finish()
        val sessions = listOf(recording.session)
        val versionId = runBlocking {
            model.materials.freeze(model.notes.state.value.documents.getValue(noteId), sessions)
        }
        compose.waitUntil(30_000) {
            model.materials.state.value.entries.any {
                it.id == versionId && it.phase == NoteMaterialPhase.Ready
            }
        }
        val uploadTarget = checkNotNull(model.uploads.target()) {
            "QA account must be bound to the isolated QA server"
        }
        val jobId = runBlocking { model.uploads.submit(versionId, uploadTarget) }
        val uploads = NoteUploadStore.at(context.noBackupFilesDir)
        compose.waitUntil(120_000) {
            uploads.jobs().any { it.id == jobId && it.phase == NoteUploadPhase.Uploaded }
        }
        awaitRemoteSource(versionId)
        val imageBytes = materialImageBytes(versionId)
        val destinationId = qaFixture.getValue("destinations").jsonObject
            .getValue("derived").jsonObject
            .getValue("id").jsonPrimitive.content
        return PreparedFixture(model, noteId, versionId, title, destinationId, imageBytes)
    }

    private fun submitAsrAndRun(fixture: PreparedFixture, baseline: ProviderSummary): NoteAsrTaskDto {
        openAsrForVersion(fixture.versionId)
        compose.onNodeWithTag("note-asr-consent-check").performScrollTo().performClick()
        compose.onNodeWithTag("note-asr-submit-${fixture.versionId}")
            .assertIsEnabled()
            .performScrollTo()
            .performClick()
        val task = awaitAsrTaskForVersion(fixture.versionId)
        qa("asr/run?taskId=${task.taskId}")
        val completed = awaitAsrTask(task.taskId) { it.phase == "Succeeded" }
        assertEquals(3, completed.totalSegments)
        assertEquals(completed.totalSamples, completed.completedSamples)
        val segments = awaitAsrSegments(task.taskId) {
            it.size == 3 && it.all { segment -> segment.status == "Succeeded" && !segment.transcript.isNullOrBlank() }
        }
        assertEquals(listOf(960_000L, 960_000L, 16_000L), segments.map { it.samples })
        val after = providerSummary()
        assertEquals(3, after.asrCalls - baseline.asrCalls)
        assertEquals(0, after.visionCalls - baseline.visionCalls)
        assertEquals(0, after.textCalls - baseline.textCalls)
        assertNotEquals(1, segments.mapNotNull { it.transcript }.distinct().size)
        shot("asr-three-slices")
        return completed
    }

    private fun openCompletedAsrTask(taskId: String) {
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
        waitForEnabledTag("note-asr-refresh-task-$taskId")
    }

    private fun assertTargetConsentVisible(destination: NoteDerivedDestinationDto) {
        assertTrue(
            "Target display name must be visible before consent",
            compose.onAllNodesWithText(destination.displayName, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "Target model must be visible before consent",
            compose.onAllNodesWithText(destination.model, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "Exact target endpoint must be visible before consent",
            compose.onAllNodesWithText(destination.endpoint, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun startDerivedFromEntry(fixture: Fixture, expectBothKinds: Boolean): NoteDerivedTaskDto {
        val noteId = latestLocalNoteId()
        assertEquals(fixture.noteId, noteId)
        val binding = checkNotNull(currentBinding()) { "Missing authenticated owner/server binding" }
        val existingTaskIds = derivedTasks(noteId).map { it.taskId }.toSet()
        val qaFixture = Json.parseToJsonElement(qa("fixture")).jsonObject
        val destinationId = qaFixture.getValue("destinations").jsonObject
            .getValue("derived").jsonObject
            .getValue("id").jsonPrimitive.content
        compose.onNodeWithTag("note-artifact-entry-destination-$destinationId").performClick()
        val full = compose.onNodeWithTag("note-artifact-entry-kind-FullRecord")
        val analysis = compose.onNodeWithTag("note-artifact-entry-kind-Analysis")
        if (expectBothKinds) {
            full.assertIsSelected()
            analysis.assertIsSelected()
        } else {
            if (compose.onAllNodesWithTag("note-artifact-entry-kind-Analysis")
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                analysis.performClick()
                analysis.assertIsNotSelected()
            }
            full.assertIsSelected()
        }
        compose.onNodeWithTag("note-artifact-entry-confirm")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(40_000) {
            compose.onAllNodesWithTag("note-artifact-screen").fetchSemanticsNodes().isNotEmpty()
        }
        val added = awaitNewDerivedTasks(noteId, existingTaskIds)
        if (added.size != 1) {
            fail(
                "One UI confirmation must create exactly one derived task; " +
                    "existing=${existingTaskIds.sorted()} added=${added.map { it.taskId }.sorted()}",
            )
        }
        return added.single().also { assertDerivedTaskIdentity(fixture, it, binding) }
    }

    private fun latestLocalNoteId(): String {
        val activity = checkNotNull(editor) { "Missing owned editor" }
        val noteId = checkNotNull(activity.model.selectedId.value) { "Missing selected note" }
        return noteId
    }

    private fun awaitNewDerivedTasks(
        noteId: String,
        existingTaskIds: Set<String>,
    ): List<NoteDerivedTaskDto> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        var current = emptyList<NoteDerivedTaskDto>()
        while (System.nanoTime() < deadline) {
            current = derivedTasks(noteId)
            val added = current.filter { it.taskId !in existingTaskIds }
            if (added.isNotEmpty()) {
                // Let rapid duplicate submissions become visible before evaluating the delta.
                Thread.sleep(1_500)
                return derivedTasks(noteId).filter { it.taskId !in existingTaskIds }
            }
            Thread.sleep(250)
        }
        fail("No new derived task persisted; existing=${existingTaskIds.sorted()}")
        error("unreachable")
    }

    private fun waitForNewDerivedTask(fixture: Fixture, existing: Set<String>): NoteDerivedTaskDto {
        val added = awaitNewDerivedTasks(fixture.noteId, existing)
        if (added.size != 1) {
            fail(
                "One regeneration confirmation must create exactly one derived task; " +
                    "existing=${existing.sorted()} added=${added.map { it.taskId }.sorted()}",
            )
        }
        val binding = checkNotNull(currentBinding()) { "Missing authenticated owner/server binding" }
        return added.single().also { assertDerivedTaskIdentity(fixture, it, binding) }
    }

    private fun assertDerivedTaskIdentity(
        fixture: Fixture,
        task: NoteDerivedTaskDto,
        binding: BoundUploadSession,
    ) {
        assertEquals(fixture.noteId, task.noteId)
        assertEquals(fixture.versionId, task.sourceVersionId)
        assertEquals(fixture.asrTask.taskId, task.asrTaskId)
        assertEquals(fixture.destination.modelId, task.destination.modelId)
        assertEquals(fixture.destination.modelRevision, task.destination.modelRevision)
        assertEquals(fixture.destination.endpoint, task.destination.endpoint)
        assertTrue(task.destination.confirmed)
        val local = checkNotNull(
            NoteArtifactTaskStore.at(context.noBackupFilesDir).tasks()
                .singleOrNull { it.taskId == task.taskId },
        ) { "Missing local intent for new task ${task.taskId}" }
        assertEquals(binding.serverIdentity, local.serverIdentity)
        assertEquals(binding.ownerId, local.ownerId)
        assertEquals(task.noteId, local.noteId)
        assertEquals(task.sourceVersionId, local.materialVersionId)
        assertEquals(task.asrTaskId, local.asrTaskId)
        assertEquals(task.destination.modelId, local.consent.modelId)
        assertEquals(task.destination.modelRevision, local.consent.modelRevision)
        assertEquals(task.destination.endpoint, local.consent.endpoint)
    }

    private fun runDerivedAndVerify(
        fixture: Fixture,
        taskId: String,
        providerBefore: ProviderSummary,
    ): DerivedRun {
        qa("derived/run?taskId=$taskId")
        val task = awaitDerivedTask(fixture.noteId, taskId) { it.phase == "Succeeded" }
        assertEquals(fixture.destination.modelId, task.destination.modelId)
        assertEquals(fixture.destination.modelRevision, task.destination.modelRevision)
        assertEquals(fixture.destination.endpoint, task.destination.endpoint)
        assertTrue(task.destination.confirmed)
        val providerAfter = providerSummary()
        assertEquals(
            "One derived task must make one vision call plus two text calls",
            3,
            providerAfter.modelCallCount - providerBefore.modelCallCount,
        )
        assertEquals(1, providerAfter.visionCalls - providerBefore.visionCalls)
        assertEquals(2, providerAfter.textCalls - providerBefore.textCalls)
        val artifacts = mustArtifacts(fixture.noteId).associateBy { it.kind.toLocal() }
        assertEquals(setOf(NoteArtifactKind.FullRecord, NoteArtifactKind.Analysis), artifacts.keys)
        assertTrue(artifacts.values.all { it.versions.any { version -> version.generationTaskId == taskId } })
        assertReceipts(fixture, taskId)
        return DerivedRun(task, artifacts)
    }

    private fun assertReceipts(fixture: Fixture, derivedTaskId: String) {
        val root = Json.parseToJsonElement(
            qa("ai/receipts?derivedTaskId=$derivedTaskId&asrTaskId=${fixture.asrTask.taskId}"),
        ).jsonObject
        val logs = root.getValue("logs").jsonArray.map { it.jsonObject }
        val derivedLogs = logs.filter {
            it["sourceObjectId"]?.jsonPrimitive?.content == derivedTaskId
        }
        assertEquals("Expected 3 actual derived SDK receipts", 3, derivedLogs.size)
        assertTrue(derivedLogs.any { it["purpose"]?.jsonPrimitive?.content == "note.derived.vision" })
        assertTrue(derivedLogs.any { it["purpose"]?.jsonPrimitive?.content == "note.derived.record-synthesis" })
        assertTrue(derivedLogs.any { it["purpose"]?.jsonPrimitive?.content == "note.derived.analysis-synthesis" })
        val visionLog = derivedLogs.single {
            it["purpose"]?.jsonPrimitive?.content == "note.derived.vision"
        }
        assertTrue(visionLog.getValue("providerAttempted").jsonPrimitive.content.toBoolean())
        assertTrue(visionLog.getValue("requestBodyCaptured").jsonPrimitive.content.toBoolean())
        assertTrue(visionLog.getValue("responseBodyComplete").jsonPrimitive.content.toBoolean())
        assertTrue(visionLog.getValue("imageInputsStored").jsonPrimitive.content.toBoolean())
        val visionLogId = visionLog.getValue("id").jsonPrimitive.content
        val imageRows = root.getValue("images").jsonArray
            .map { it.jsonObject }
            .filter { it["logId"]?.jsonPrimitive?.content == visionLogId }
        assertEquals(fixture.imageBytes.size, imageRows.size)
        assertTrue(imageRows.all { it["stored"]?.jsonPrimitive?.content?.toBoolean() == true })
        val imageHashes = imageRows.map { it.getValue("sha256").jsonPrimitive.content }.toSet()
        val imageBytesByHash = fixture.imageBytes.entries.associate { (_, bytes) ->
            sha256Hex(bytes) to bytes
        }
        assertEquals(imageBytesByHash.keys, imageHashes)
        imageRows.forEach { image ->
            val sha256 = image.getValue("sha256").jsonPrimitive.content
            assertEquals(
                imageBytesByHash.getValue(sha256).size,
                image.getValue("sizeBytes").jsonPrimitive.int,
            )
        }
        val provider = providerSummary()
        assertEquals(imageBytesByHash.keys, provider.receivedImages.map { it.sha256 }.toSet())
        imageBytesByHash.forEach { (sha256, bytes) ->
            assertTrue(
                "Provider did not receive $sha256 with the expected byte count",
                provider.receivedImages.any { it.sha256 == sha256 && it.bytes == bytes.size },
            )
        }
        val asrLogs = logs.filter {
            it["sourceObjectId"]?.jsonPrimitive?.content == fixture.asrTask.taskId
        }
        assertEquals("Expected 3 actual ASR slice receipts", 3, asrLogs.size)
    }

    private fun assertCompleteCoverage(task: NoteDerivedTaskDto) {
        assertTrue(task.coverage.complete)
        assertEquals(3, task.coverage.transcriptSegments)
        assertEquals(3, task.coverage.completedTranscriptSegments)
        assertEquals(task.coverage.photos, task.coverage.completedPhotos)
        assertEquals(task.coverage.inkPreviews, task.coverage.completedInkPreviews)
        assertTrue("Fixture must include a synthetic photo", task.coverage.photos >= 1)
        assertTrue("Fixture must include a synthetic ink preview", task.coverage.inkPreviews >= 1)
        assertTrue("Fixture must include a vision batch", task.coverage.visionBatches >= 1)
        assertTrue(task.issues.isEmpty())
    }

    private fun saveHumanThroughUi(kind: NoteArtifactKind, text: String): NoteDerivedVersionDto {
        compose.onNodeWithTag("note-artifact-kind-${kind.name}").performClick()
        selectModelCandidateForManualRevision(kind)
        enterDraftText(kind, text)
        saveLocalDraftThroughUi(kind)
        compose.waitUntil(15_000) {
            val state = editor?.model?.artifacts?.state?.value
            state?.drafts?.any { it.kind == kind && !it.dirty } == true &&
                state.artifactRefs[kind]?.revision != null
        }
        compose.onNodeWithTag("note-artifact-save-server-${kind.name}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        val before = humanSaveState(kind)
        compose.onNodeWithTag("note-artifact-save-server-${kind.name}")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        val noteId = latestLocalNoteId()
        humanSaveProbe = probeHumanSaveAfterClick(noteId, kind, text, before)
        try {
            val version = waitForVersion(noteId, kind) {
                it.author == NoteDerivedVersionAuthor.USER && it.isHead && it.contentMarkdown == text
            }
            assertFalse(version.hasConflict)
            assertEquals(kind.toWire(), version.kind)
            shot("human-saved-${kind.name}")
            return version
        } catch (error: AssertionError) {
            humanSaveProbe = humanSaveProbe.orEmpty() + mapOf(
                "finalOutcome" to "FAIL",
                "finalError" to "${error::class.qualifiedName}: ${error.message}",
            )
            throw error
        }
    }

    private fun selectModelCandidateForManualRevision(kind: NoteArtifactKind) {
        val noteId = latestLocalNoteId()
        val artifact = mustArtifact(noteId, kind)
        val candidate = artifact.versions.firstOrNull { it.isHead }
            ?: artifact.versions.firstOrNull { it.author == NoteDerivedVersionAuthor.MODEL }
            ?: error("No Model or selected candidate available for manual revision")
        val tag = "note-artifact-select-${kind.name}-${candidate.versionId}"
        scrollToNodeEventually("note-artifact-list", hasTestTag(tag))
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(15_000) {
            editor?.model?.artifacts?.state?.value?.artifactRefs?.get(kind)?.headVersionId ==
                candidate.versionId
        }
    }

    private fun humanSaveState(kind: NoteArtifactKind): Map<String, String> {
        val model = checkNotNull(editor?.model) { "Missing owned editor" }
        val state = model.artifacts.state.value
        val draft = state.drafts
            .filter { it.kind == kind && it.noteId == model.selectedId.value }
            .maxByOrNull { it.updatedAtMillis }
        val artifact = state.artifactRefs[kind]
        return linkedMapOf(
            "vmMessage" to model.message.value.toString(),
            "repositoryMessage" to state.message.toString(),
            "draftPresent" to (draft != null).toString(),
            "draftDirty" to draft?.dirty.toString(),
            "draftLocalRevision" to draft?.localRevision.toString(),
            "draftBaseVersion" to draft?.baseServerArtifactVersionId.toString(),
            "artifactRevision" to artifact?.revision.toString(),
            "artifactHeadVersion" to artifact?.headVersionId.toString(),
        )
    }

    private fun probeHumanSaveAfterClick(
        noteId: String,
        kind: NoteArtifactKind,
        text: String,
        before: Map<String, String>,
    ): Map<String, String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var after = humanSaveState(kind)
        var userVersions = emptyList<String>()
        var apiReadError: String? = null
        while (System.nanoTime() < deadline) {
            after = humanSaveState(kind)
            val read = runCatching { userVersions(noteId, kind, text) }
            userVersions = read.getOrDefault(emptyList())
            apiReadError = read.exceptionOrNull()?.let { "${it::class.qualifiedName}: ${it.message}" }
            val changedMessage = after["vmMessage"] != before["vmMessage"] ||
                after["repositoryMessage"] != before["repositoryMessage"]
            if (userVersions.isNotEmpty() || changedMessage) break
            Thread.sleep(250)
        }
        val classification = when {
            userVersions.isNotEmpty() -> "USER_version_visible"
            after["repositoryMessage"] != before["repositoryMessage"] ->
                "repository_message_after_click"
            after["vmMessage"] != before["vmMessage"] ->
                "vm_message_after_click"
            else -> "no_observable_callback_state"
        }
        return linkedMapOf(
            "clickCount" to "1",
            "buttonState" to "visible_enabled_clicked",
            "before.vmMessage" to before.getValue("vmMessage"),
            "before.repositoryMessage" to before.getValue("repositoryMessage"),
            "before.draftDirty" to before.getValue("draftDirty"),
            "before.draftLocalRevision" to before.getValue("draftLocalRevision"),
            "before.artifactRevision" to before.getValue("artifactRevision"),
            "after.vmMessage" to after.getValue("vmMessage"),
            "after.repositoryMessage" to after.getValue("repositoryMessage"),
            "after.draftDirty" to after.getValue("draftDirty"),
            "after.draftLocalRevision" to after.getValue("draftLocalRevision"),
            "after.draftBaseVersion" to after.getValue("draftBaseVersion"),
            "after.userVersionIds" to userVersions.joinToString(","),
            "after.apiReadError" to apiReadError.toString(),
            "classification" to classification,
            "finalOutcome" to "PENDING",
        )
    }

    private fun userVersions(
        noteId: String,
        kind: NoteArtifactKind,
        text: String,
    ): List<String> {
        val artifact = mustArtifact(noteId, kind)
        return artifact.versions.mapNotNull { summary ->
            runCatching { getVersion(artifact.artifactId, summary.versionId) }.getOrNull()
        }.filter {
            it.author == NoteDerivedVersionAuthor.USER && it.contentMarkdown == text
        }.map { it.versionId }
    }

    private fun enterDraftText(kind: NoteArtifactKind, text: String) {
        scrollToNodeEventually("note-artifact-list", hasTestTag("note-artifact-editor-${kind.name}"))
        compose.onNodeWithTag("note-artifact-editor-${kind.name}", useUnmergedTree = true)
            .performScrollTo()
            .performTextReplacement(text)
        closeSoftKeyboard()
        compose.waitUntil(15_000) {
            compose.onAllNodes(
                hasText(text, substring = true) and hasTestTag("note-artifact-editor-${kind.name}"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun saveLocalDraftThroughUi(kind: NoteArtifactKind) {
        compose.onNodeWithTag("note-artifact-save-${kind.name}")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("note-artifact-save-server-${kind.name}")
                .fetchSemanticsNodes().isNotEmpty() &&
                runCatching {
                    compose.onNodeWithTag("note-artifact-save-server-${kind.name}")
                        .assertIsEnabled()
                    true
                }.getOrDefault(false)
        }
    }

    private fun regenerateThroughUi(kind: NoteArtifactKind) {
        val regenerateTag = "note-artifact-regenerate-${kind.name}"
        scrollToNodeEventually("note-artifact-list", hasTestTag(regenerateTag))
        compose.onNodeWithTag(regenerateTag)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("note-artifact-confirm-regenerate-${kind.name}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("note-artifact-confirm-regenerate-${kind.name}").performClick()
    }

    private fun refreshArtifactScreen() {
        val kind = if (
            compose.onAllNodesWithTag("note-artifact-refresh-FullRecord")
                .fetchSemanticsNodes().isNotEmpty()
        ) NoteArtifactKind.FullRecord else NoteArtifactKind.Analysis
        waitForEnabledTag("note-artifact-refresh-${kind.name}")
        compose.onNodeWithTag("note-artifact-refresh-${kind.name}").performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-artifact-list").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertCoverageVisibleComplete() {
        scrollToNodeEventually("note-artifact-list", hasTestTag("note-artifact-coverage"))
        compose.onNodeWithTag("note-artifact-coverage")
            .assertTextContains("材料完整覆盖，无已知缺口。", substring = true)
    }

    private fun mustArtifact(noteId: String, kind: NoteArtifactKind): NoteDerivedArtifactDto {
        val artifact = runBlocking {
            reader().getNoteDerivedArtifacts(noteId).data.orEmpty()
                .singleOrNull { it.kind == kind.toWire() }
        }
        return checkNotNull(artifact) { "Missing $kind artifact for note" }
    }

    private fun mustArtifacts(noteId: String): List<NoteDerivedArtifactDto> = runBlocking {
        reader().getNoteDerivedArtifacts(noteId).data.orEmpty()
    }

    private fun getVersion(artifactId: String, versionId: String): NoteDerivedVersionDto = runBlocking {
        checkNotNull(reader().getNoteDerivedVersion(artifactId, versionId).data)
    }

    private fun waitForVersion(
        noteId: String,
        kind: NoteArtifactKind,
        predicate: (NoteDerivedVersionDto) -> Boolean,
    ): NoteDerivedVersionDto {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        var last = emptyList<NoteDerivedVersionDto>()
        while (System.nanoTime() < deadline) {
            val artifact = runCatching { mustArtifact(noteId, kind) }.getOrNull()
            if (artifact != null) {
                last = artifact.versions.mapNotNull { summary ->
                    runCatching { getVersion(artifact.artifactId, summary.versionId) }.getOrNull()
                }
                last.firstOrNull(predicate)?.let { return it }
            }
            Thread.sleep(250)
        }
        fail("Timed out waiting for ${kind.name} version; versions=${last.map { it.versionId }}")
        error("unreachable")
    }

    private fun derivedTasks(noteId: String): List<NoteDerivedTaskDto> = runBlocking {
        reader().getNoteDerivedTasks(noteId).data.orEmpty()
    }

    private fun awaitDerivedTask(
        noteId: String,
        taskId: String,
        predicate: (NoteDerivedTaskDto) -> Boolean,
    ): NoteDerivedTaskDto {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        var last: NoteDerivedTaskDto? = null
        while (System.nanoTime() < deadline) {
            last = derivedTasks(noteId).firstOrNull { it.taskId == taskId }
            if (last != null && predicate(last)) return last
            Thread.sleep(250)
        }
        fail("Derived task did not reach expected state; last=$last")
        error("unreachable")
    }

    private fun waitForDerivedTask(
        noteId: String,
        taskId: String,
        predicate: (NoteDerivedTaskDto) -> Boolean,
    ): NoteDerivedTaskDto = awaitDerivedTask(noteId, taskId, predicate)

    private fun awaitRemoteSource(versionId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < deadline) {
            val source = runCatching {
                runBlocking {
                    reader().getNoteAsrSources(offset = 0, limit = 50).data?.items
                        ?.firstOrNull { it.versionId == versionId }
                }
            }.getOrNull()
            if (source != null) return
            Thread.sleep(250)
        }
        fail("Timed out waiting for uploaded source $versionId")
    }

    private fun awaitAsrTaskForVersion(versionId: String): NoteAsrTaskDto {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < deadline) {
            val task = runBlocking { reader().getNoteAsrTasks().data.orEmpty() }
                .firstOrNull { it.versionId == versionId }
            if (task != null) return task
            Thread.sleep(250)
        }
        fail("No ASR task appeared for fixed version")
        error("unreachable")
    }

    private fun awaitAsrTask(
        taskId: String,
        predicate: (NoteAsrTaskDto) -> Boolean,
    ): NoteAsrTaskDto {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        var last: NoteAsrTaskDto? = null
        while (System.nanoTime() < deadline) {
            last = runBlocking { reader().getNoteAsrTask(taskId).data }
            if (last != null && predicate(last)) return last
            Thread.sleep(250)
        }
        fail("ASR task did not reach expected state; last=$last")
        error("unreachable")
    }

    private fun awaitAsrSegments(
        taskId: String,
        predicate: (List<NoteAsrSegmentDto>) -> Boolean,
    ): List<NoteAsrSegmentDto> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        var last = emptyList<NoteAsrSegmentDto>()
        while (System.nanoTime() < deadline) {
            last = runBlocking {
                reader().getNoteAsrSegments(taskId, offset = 0, limit = 20).data?.items.orEmpty()
            }
            if (predicate(last)) return last
            Thread.sleep(250)
        }
        fail("ASR segments did not reach expected state: $last")
        error("unreachable")
    }

    private fun openAsrForVersion(versionId: String) {
        closeArtifacts()
        compose.onNodeWithContentDescription("更多文档操作").performClick()
        compose.onNodeWithTag("note-material-versions").performClick()
        scrollToNodeEventually("material-version-sheet", hasTestTag("material-asr-$versionId"))
        compose.onNodeWithTag("material-asr-$versionId").assertIsDisplayed().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-asr-consent-$versionId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("note-asr-consent-check").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun login(username: String) {
        closeArtifacts()
        closeAsrSurface()
        closeEditor()
        showMain()
        compose.onNodeWithTag("pim-nav-settings").performClick()
        val logouts = compose.onAllNodesWithText("退出登录").fetchSemanticsNodes()
        if (logouts.isNotEmpty()) compose.onNodeWithText("退出登录").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("API 地址"))
            .performScrollTo()
            .performTextReplacement(base)
        compose.onNodeWithText("保存", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("用户名"))
            .performScrollTo()
            .performTextReplacement(username)
        compose.onNode(hasSetTextAction() and hasText("密码"))
            .performScrollTo()
            .performTextReplacement("password123")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("登录") and hasClickAction() and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("登录", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(25_000) { freshTokens().snapshot().ownerName == username }
    }

    private fun closeArtifacts() {
        if (compose.onAllNodesWithTag("note-artifact-screen").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("note-artifact-close").performClick()
        } else if (compose.onAllNodesWithTag("note-artifact-entry").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("note-artifact-entry-close").performClick()
        }
        compose.waitForIdle()
    }

    private fun closeAsrSurface() {
        if (compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isEmpty()) return
        val taskListBack = compose.onAllNodesWithContentDescription("返回任务列表").fetchSemanticsNodes()
        if (taskListBack.isNotEmpty()) compose.onNodeWithContentDescription("返回任务列表").performClick()
        val close = compose.onAllNodesWithTag("note-asr-close").fetchSemanticsNodes()
        if (close.isNotEmpty()) compose.onNodeWithTag("note-asr-close").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("note-asr-screen").fetchSemanticsNodes().isEmpty()
        }
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
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        compose.waitForIdle()
    }

    private fun showLibrary() {
        showMain()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("pim-nav-notes").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pim-nav-notes").performClick()
    }

    private fun waitSaved(model: NotesViewModel, noteId: String) = compose.waitUntil(20_000) {
        val note = model.notes.state.value.documents[noteId]
        note != null && model.notes.state.value.savedRevisions[noteId] == note.revision
    }

    private fun scrollToNodeEventually(
        parentTag: String,
        matcher: androidx.compose.ui.test.SemanticsMatcher,
    ) {
        compose.waitUntil(20_000) {
            runCatching {
                compose.onNodeWithTag(parentTag).performScrollToNode(matcher)
                true
            }.getOrDefault(false)
        }
    }

    private fun waitForEnabledTag(tag: String) {
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNodeWithTag(tag).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
    }

    private fun freshTokens() = TokenManager(context)

    private fun currentBinding(): BoundUploadSession? {
        val identity = freshTokens().snapshot()
        return if (identity.serverIdentity != null && identity.ownerId != null) {
            BoundUploadSession(identity.serverIdentity!!, identity.ownerId!!)
        } else null
    }

    private fun materialImageBytes(versionId: String): Map<String, ByteArray> {
        val version = runBlocking {
            reader().getNoteMaterialVersion(versionId).data
        } ?: error("Missing uploaded material version $versionId")
        val images = version.upload.files.filter { it.kind == "Photo" || it.kind == "InkPreview" }
        assertEquals(setOf("Photo", "InkPreview"), images.map { it.kind }.toSet())
        return images.associate { file ->
            val bytes = downloadNoteMaterialFile(versionId, file.index)
            assertEquals(
                "Downloaded ${file.kind} bytes must match the uploaded material manifest",
                file.sha256.lowercase(),
                sha256Hex(bytes),
            )
            assertEquals(file.sizeBytes, bytes.size.toLong())
            file.kind to bytes
        }
    }

    private fun downloadNoteMaterialFile(versionId: String, fileIndex: Int): ByteArray {
        val request = Request.Builder()
            .url("${base.trimEnd('/')}/native-notes/versions/$versionId/files/$fileIndex")
            .build()
        return authenticatedClient().newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Material file download returned ${response.code}"
            }
            checkNotNull(response.body).bytes()
        }
    }

    private fun authenticatedClient(): OkHttpClient {
        val tokens = freshTokens()
        val settings = ServerSettingsStore(context, tokens)
        return OkHttpClient.Builder().addInterceptor(
            AuthInterceptor(
                tokens,
                AuthRefreshCoordinator(tokens, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }),
                settings,
            ),
        ).build()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun reader(): ApiService {
        val tokens = freshTokens()
        val settings = ServerSettingsStore(context, tokens)
        val binding = checkNotNull(currentBinding()) { "No authenticated owner/server binding" }
        val client = OkHttpClient.Builder().addInterceptor(
            AuthInterceptor(
                tokens,
                AuthRefreshCoordinator(tokens, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }),
                settings,
            ),
        ).build()
        return ApiClientProvider(client, Json { ignoreUnknownKeys = true }, settings)
            .noteUploadApiService(binding)
    }

    private fun qa(path: String): String {
        val origin = PimServerEndpoints.from(base).trustedOrigin
        return OkHttpClient().newCall(
            Request.Builder().url("$origin/__qa/$path").build(),
        ).execute().use { response ->
            check(response.isSuccessful) { "QA endpoint $path returned ${response.code}" }
            response.body!!.string()
        }
    }

    private fun providerSummary(): ProviderSummary {
        val root = Json.parseToJsonElement(qa("provider/summary")).jsonObject
        val groups = root.getValue("groups").jsonObject
        val visionEvidence = root.getValue("visionEvidence").jsonObject
        fun group(name: String) = groups.getValue(name).jsonObject
            .getValue("calls").jsonPrimitive.int
        return ProviderSummary(
            modelCallCount = root.getValue("modelCallCount").jsonPrimitive.int,
            asrCalls = group("asr"),
            visionCalls = group("vision"),
            textCalls = group("text"),
            callHashes = root.getValue("calls").jsonArray.map {
                it.jsonObject.getValue("responseSha256").jsonPrimitive.content
            },
            receivedImages = visionEvidence.getValue("receivedImages").jsonArray.map {
                val image = it.jsonObject
                ProviderImage(
                    sha256 = image.getValue("sha256").jsonPrimitive.content,
                    bytes = image.getValue("bytes").jsonPrimitive.int,
                )
            },
        )
    }

    private fun captureCaseEvidence(caseName: String, body: () -> Unit) {
        humanSaveProbe = null
        var failure: Throwable? = null
        try {
            body()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            writeEvidence(caseName, failure)
        }
    }

    private fun writeEvidence(caseName: String, failure: Throwable?) {
        val notes = mutableListOf<String>()
        try {
            val profile = InstrumentationRegistry.getArguments()
                .getString("nativeNoteDerivedQaProfile", "phone")
            val root = File(context.getExternalFilesDir(null), "native-note-derived-qa/case-evidence")
                .apply { mkdirs() }
            val safe = caseName.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
            val status = if (failure == null) "pass" else "fail"
            val dir = File(root, "$status-$profile-$safe-${System.currentTimeMillis()}").apply { mkdirs() }

            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(dir, "screen.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }.onFailure { notes += "screenshot failed: ${it.message}" }

            runCatching {
                val fixture = activeFixture ?: return@runCatching
                val imageDir = File(dir, "actual-image-bytes").apply { mkdirs() }
                val manifest = fixture.imageBytes.map { (kind, bytes) ->
                    val sha256 = sha256Hex(bytes)
                    val name = "$kind-$sha256.png"
                    File(imageDir, name).writeBytes(bytes)
                    mapOf(
                        "kind" to kind,
                        "bytes" to bytes.size.toString(),
                        "sha256" to sha256,
                        "path" to "actual-image-bytes/$name",
                    )
                }
                File(dir, "actual-image-bytes.json").writeText(Json.encodeToString(manifest))
            }.onFailure { notes += "actual image bytes failed: ${it.message}" }

            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
                val tree = StringBuilder("rootCount=${roots.size}\n")
                var visited = 0
                fun append(node: SemanticsNode, depth: Int) {
                    if (visited++ >= 4_000 || tree.length >= 262_144) return
                    tree.append("  ".repeat(depth))
                        .append("node=${node.id} bounds=${node.boundsInRoot} tag=")
                        .append(node.config)
                        .append('\n')
                    node.children.forEach { append(it, depth + 1) }
                }
                roots.forEachIndexed { index, node ->
                    tree.append("root[$index]\n")
                    append(node, 1)
                }
                File(dir, "compose-tree.txt").writeText(tree.toString())
            }.onFailure { notes += "compose tree failed: ${it.message}" }

            runCatching {
                File(dir, "provider-summary.json").writeText(qa("provider/summary"))
            }.onFailure { notes += "provider summary failed: ${it.message}" }

            runCatching {
                humanSaveProbe?.let { probe ->
                    File(dir, "human-save-probe.json").writeText(Json.encodeToString(probe))
                }
            }.onFailure { notes += "human save probe failed: ${it.message}" }

            runCatching {
                val tasks = NoteArtifactTaskStore.at(context.noBackupFilesDir).tasks()
                File(dir, "local-derived-tasks.json").writeText(
                    NoteJson.encodeToString(tasks),
                )
                val binding = currentBinding()
                if (binding != null) {
                    val drafts = NoteArtifactDraftStore.at(context.noBackupFilesDir).drafts(binding)
                    File(dir, "local-derived-drafts.json").writeText(
                        NoteJson.encodeToString(drafts),
                    )
                    val conflicts = NoteArtifactDraftStore.at(context.noBackupFilesDir).conflicts(binding)
                    File(dir, "local-derived-conflicts.json").writeText(
                        NoteJson.encodeToString(conflicts),
                    )
                }
            }.onFailure { notes += "local evidence failed: ${it.message}" }

            runCatching {
                val noteId = editor?.model?.selectedId?.value
                if (noteId != null) {
                    File(dir, "derived-tasks.json").writeText(
                        Json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(NoteDerivedTaskDto.serializer()),
                            derivedTasks(noteId),
                        ),
                    )
                    File(dir, "derived-artifacts.json").writeText(
                        Json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(NoteDerivedArtifactDto.serializer()),
                            mustArtifacts(noteId),
                        ),
                    )
                    derivedTasks(noteId).forEach { task ->
                        val asrTaskId = task.asrTaskId ?: activeFixture?.asrTask?.taskId
                        val receipts = if (asrTaskId == null) {
                            qa("ai/receipts?derivedTaskId=${task.taskId}")
                        } else {
                            qa("ai/receipts?derivedTaskId=${task.taskId}&asrTaskId=$asrTaskId")
                        }
                        File(dir, "actual-sdk-receipts-${task.taskId}.json").writeText(receipts)
                    }
                }
            }.onFailure { notes += "server evidence failed: ${it.message}" }

            File(dir, "case-result.txt").writeText(
                buildString {
                    append("case=$caseName\n")
                    append("status=${if (failure == null) "PASS" else "FAIL"}\n")
                    append("profile=$profile\n")
                    append("capturedAt=${java.time.OffsetDateTime.now()}\n")
                    append("apiBase=$base\n")
                    append("failure=${failure?.let { "${it::class.qualifiedName}: ${it.message}" } ?: "none"}\n")
                    notes.forEach { append("captureNote=$it\n") }
                    failure?.let { append("\n").append(it.stackTraceToString()) }
                },
            )
        } catch (_: Throwable) {
            // Evidence capture must not hide the product/test failure.
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val profile = InstrumentationRegistry.getArguments()
            .getString("nativeNoteDerivedQaProfile", "phone")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val folder = File(context.getExternalFilesDir(null), "native-note-derived-qa/screens")
            .apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$profile-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun NoteArtifactKind.toWire(): NoteDerivedArtifactKind = when (this) {
        NoteArtifactKind.FullRecord -> NoteDerivedArtifactKind.COMPLETERECORD
        NoteArtifactKind.Analysis -> NoteDerivedArtifactKind.INDEPENDENTANALYSIS
    }

    private fun NoteDerivedArtifactKind.toLocal(): NoteArtifactKind = when (this) {
        NoteDerivedArtifactKind.COMPLETERECORD -> NoteArtifactKind.FullRecord
        NoteDerivedArtifactKind.INDEPENDENTANALYSIS -> NoteArtifactKind.Analysis
    }
}

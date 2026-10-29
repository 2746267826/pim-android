package com.pim.app.notes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.pim.app.MainActivity
import com.pim.app.recording.*
import com.pim.core.auth.*
import com.pim.core.network.*
import com.pim.core.settings.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.ByteArrayOutputStream
import java.io.File

/** Run only on the dedicated QA emulator with the isolated loopback Test API harness. */
class NativeNoteUploadTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { isolatedServerOnly() }
    }).around(compose)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = compose.activity.applicationContext
    private lateinit var base: String
    private var editor: NativeNotesActivity? = null
    private val jobs get() = NoteUploadStore.at(context.noBackupFilesDir)

    private fun isolatedServerOnly() {
        val supplied = InstrumentationRegistry.getArguments().getString("nativeNotesQaApi")
        assumeTrue("No isolated upload QA server supplied", supplied != null)
        base = checkNotNull(supplied)
        val target = PimServerEndpoints.from(base)
        require(target.webOrigin.host == "127.0.0.1" && target.webOrigin.port != 5858) { "QA must use its own loopback port" }
    }
    @After fun closeOwnEditor() { editor?.let { activity -> instrumentation.runOnMainSync { activity.finish() } } }

    @Test fun ordinaryAccountUploadsAllMixedMaterialBeyondFourMiBWhileEditingNewDraft() {
        login("notes-qa-alice")
        val (model, versionId) = prepare(largeAudio = true)
        val source = NoteMaterialStore.at(context.noBackupFilesDir).uploadSnapshot(versionId)
        assertTrue(source.sizeBytes > 4 * 1024 * 1024)
        compose.runOnUiThread { model.notes.update(source.noteId) { it.withTitle("仍在编辑的新稿B") } }
        submit(versionId)
        val job = awaitPhase(versionId, NoteUploadPhase.Uploaded)
        assertEquals(source.sizeBytes, job.uploadedBytes)
        assertEquals("仍在编辑的新稿B", model.notes.state.value.documents.getValue(source.noteId).title)
        val remote = runBlocking { reader().getNoteMaterialVersion(versionId).data!! }
        assertEquals("Uploaded", remote.upload.phase)
        assertEquals(source.inputJson, remote.inputJson); assertEquals(source.manifestJson, remote.manifestJson)
        assertEquals(source.files.map { it.sha256 }, remote.upload.files.map { it.sha256 })
        assertEquals(source.files.sumOf { it.sizeBytes }, remote.upload.files.sumOf { it.sizeBytes })
        assertEquals("上传验收固定稿A", Json.parseToJsonElement(remote.inputJson).jsonObject.getValue("note").jsonObject.string("title"))
        showLibrary()
        compose.onNodeWithTag("note-upload-${job.id}").performScrollTo().assertIsDisplayed(); shot("uploaded-library")
    }

    @Test fun lostCompletionAcknowledgementReconcilesAfterLeavingEditorWithoutDuplicateCompletion() {
        login("notes-qa-alice")
        val (_, versionId) = prepare(false)
        val before = qa("summary")
        qa("fault?kind=complete"); submit(versionId)
        awaitPhase(versionId, NoteUploadPhase.WaitingForNetwork)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        val job = awaitPhase(versionId, NoteUploadPhase.Uploaded)
        assertEquals(job.totalBytes, job.uploadedBytes)
        val after = qa("summary")
        val oldCount = Json.parseToJsonElement(before).jsonObject.getValue("completedCalls").toString().toInt()
        val count = Json.parseToJsonElement(after).jsonObject.getValue("completedCalls").toString().toInt()
        assertEquals(oldCount + 1, count)
        showLibrary(); compose.onNodeWithTag("note-upload-${job.id}").performScrollTo().assertIsDisplayed(); shot("background-reconciled")
    }

    @Test fun accountSwitchPausesOldIntentThenOriginalOwnerCanExplicitlyResumeSameVersion() {
        login("notes-qa-alice")
        val (_, versionId) = prepare(false)
        qa("fault?kind=part"); submit(versionId)
        val captured = awaitPhase(versionId, NoteUploadPhase.WaitingForNetwork)
        login("notes-qa-bob")
        val paused = awaitPhase(versionId, NoteUploadPhase.NeedsLogin)
        assertEquals(captured.ownerId, paused.ownerId); assertEquals(captured.serverIdentity, paused.serverIdentity)
        val bob = freshTokens().snapshot(); assertNotEquals(captured.ownerId, bob.ownerId)
        try { runBlocking { reader().getNoteMaterialVersion(versionId) }; fail("Another account must not read the version") }
        catch (error: retrofit2.HttpException) { assertEquals(404, error.code()) }
        showLibrary(); compose.onNodeWithTag("note-upload-${captured.id}").performScrollTo(); shot("owner-paused")
        login("notes-qa-alice"); showLibrary()
        compose.onNodeWithTag("note-upload-resume-${captured.id}").performScrollTo().performClick()
        val complete = awaitPhase(versionId, NoteUploadPhase.Uploaded)
        assertEquals(captured.id, complete.id); assertEquals(captured.ownerId, complete.ownerId)
        assertEquals("Uploaded", runBlocking { reader().getNoteMaterialVersion(versionId).data!!.upload.phase })
    }

    @Test fun recreatedConsentRequiresNewApprovalAndDoesNotEnqueueByItself() {
        login("notes-qa-alice")
        val (_, versionId) = prepare(false)
        compose.onNodeWithContentDescription("更多文档操作").performClick()
        compose.onNodeWithTag("note-material-versions").performClick()
        compose.onNodeWithTag("material-upload-$versionId").performScrollTo().performClick()
        compose.onNodeWithTag("note-upload-consent-check").performScrollTo().performClick()
        compose.onNodeWithTag("note-upload-submit").assertIsEnabled()
        val old = checkNotNull(editor)
        instrumentation.runOnMainSync { old.recreate() }
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NativeNotesActivity>().firstOrNull()
            }
            editor != null && editor !== old
        }
        compose.onNodeWithTag("note-upload-consent-check").assertIsOff()
        compose.onNodeWithTag("note-upload-submit").assertIsNotEnabled()
        assertTrue(jobs.jobs().none { it.versionId == versionId })
    }

    private fun login(username: String) {
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
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("登录") and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("登录", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(20_000) { freshTokens().snapshot().ownerName == username }
    }

    private fun prepare(largeAudio: Boolean): Pair<NotesViewModel, String> {
        compose.onNodeWithTag("pim-nav-notes").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("notes-create") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("notes-create").performClick()
        compose.waitUntil(10_000) {
            instrumentation.runOnMainSync {
                editor = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<NativeNotesActivity>().firstOrNull()
            }
            editor != null
        }
        val activity = checkNotNull(editor); val model = activity.model
        compose.waitUntil(10_000) { model.selectedId.value != null }
        val noteId = model.selectedId.value!!
        val files = NoteFileStore(File(context.noBackupFilesDir, "notes"))
        val png = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
        }.toByteArray()
        val photo = files.importPhoto(noteId, png.inputStream())
        compose.runOnUiThread {
            model.notes.update(noteId) { it.withTitle("上传验收固定稿A").withBlocks(listOf(markdownBlock("# 合成课堂\n\n保留全部原文和材料。"), photo,
                withInk(inkBlock(320f), InkData(strokes = listOf(InkStroke(listOf(InkPoint(10f, 20f), InkPoint(80f, 90f)))))))) }
        }
        compose.waitUntil(10_000) { val note = model.notes.state.value.documents[noteId]; note != null && model.notes.state.value.savedRevisions[noteId] == note.revision }
        val sessions = if (largeAudio) {
            val recording = RecordingEngine(RecordingFileStore(File(context.noBackupFilesDir, "recordings")), RecordingPolicy(), noteId = noteId)
            val pcm = ShortArray(16_000 * 150) { (it % 1000).toShort() }; recording.append(pcm, pcm.size); recording.finish(); listOf(recording.session)
        } else emptyList()
        val versionId = runBlocking { model.materials.freeze(model.notes.state.value.documents.getValue(noteId), sessions) }
        compose.waitUntil(30_000) { model.materials.state.value.entries.any { it.id == versionId && it.phase == NoteMaterialPhase.Ready } }
        return model to versionId
    }

    private fun submit(versionId: String) {
        compose.onNodeWithContentDescription("更多文档操作").performClick()
        compose.onNodeWithTag("note-material-versions").performClick()
        compose.onNodeWithTag("material-upload-$versionId").performScrollTo().performClick()
        compose.onNodeWithTag("note-upload-submit").assertIsNotEnabled()
        shot("consent")
        compose.onNodeWithTag("note-upload-consent-check").performScrollTo().performClick()
        compose.onNodeWithTag("note-upload-submit").performScrollTo().performClick()
    }
    private fun awaitPhase(version: String, phase: NoteUploadPhase): NoteUploadJob {
        compose.waitUntil(120_000) { jobs.jobs().any { it.versionId == version && it.phase == phase } }
        return jobs.jobs().first { it.versionId == version && it.phase == phase }
    }
    private fun showMain() {
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        compose.waitForIdle()
    }
    private fun showLibrary() { showMain(); compose.onNodeWithTag("pim-nav-notes").performClick() }
    private fun freshTokens() = TokenManager(context)
    private fun reader(): ApiService {
        val tokens = freshTokens(); val settings = ServerSettingsStore(context, tokens)
        val identity = tokens.snapshot(); val binding = BoundUploadSession(checkNotNull(identity.serverIdentity), checkNotNull(identity.ownerId))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens, AuthRefreshCoordinator(tokens, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }), settings)).build()
        return ApiClientProvider(client, Json { ignoreUnknownKeys = true }, settings).noteUploadApiService(binding)
    }
    private fun qa(path: String): String {
        val origin = PimServerEndpoints.from(base).trustedOrigin
        return OkHttpClient().newCall(Request.Builder().url("$origin/__qa/$path").build()).execute().use { response ->
            check(response.isSuccessful); response.body!!.string()
        }
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val profile = InstrumentationRegistry.getArguments().getString("nativeNotesQaProfile", "phone").replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val folder = File(context.getExternalFilesDir(null), "native-note-upload-qa").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(folder, "$profile-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

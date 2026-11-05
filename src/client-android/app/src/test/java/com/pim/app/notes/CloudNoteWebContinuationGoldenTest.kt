package com.pim.app.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.core.auth.SecurePreferencesFactory
import com.pim.core.auth.TokenManager
import com.pim.core.contract.NativeNotePageDto
import com.pim.core.contract.NativeNotePageDtoApiResponse
import com.pim.core.contract.NativeNoteSummaryDto
import com.pim.core.contract.NoteMaterialUploadDto
import com.pim.core.contract.NoteMaterialUploadFileDto
import com.pim.core.contract.NoteMaterialUploadLimitsDto
import com.pim.core.contract.NoteMaterialVersionDto
import com.pim.core.contract.NoteMaterialVersionDtoApiResponse
import com.pim.core.network.ApiClientProvider
import com.pim.core.settings.ServerSettingsStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real cloud restore path against the checked-in Web golden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class CloudNoteWebContinuationGoldenTest {
    private val server = MockWebServer()
    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun realCloudListDownloadAndInstallPreservesWebContinuation() = runBlocking {
        val golden = loadGoldenOrSkip()
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.noBackupFilesDir, "notes/${golden.noteId}").deleteRecursively()
        File(context.noBackupFilesDir, "cloud-recording-archives/${golden.noteId}").deleteRecursively()
        val auth = TokenManager(object : SecurePreferencesFactory {
            override fun open() = context.getSharedPreferences("cloud-golden-${System.nanoTime()}", Context.MODE_PRIVATE)
        }, nowMillis = { 1_000L })
        val settings = ServerSettingsStore(context, auth)
        settings.setBaseUrl(server.url("/api/v1/").toString())
        val origin = com.pim.core.settings.PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin
        assertTrue(auth.saveOwned("golden-access", "golden-refresh", Long.MAX_VALUE, origin, golden.noteId, "golden"))

        server.dispatcher = golden.dispatcher
        val provider = ApiClientProvider(OkHttpClient(), Json { ignoreUnknownKeys = true }, settings)
        val repository = CloudNoteRepository(context, provider, auth, settings)

        repository.refresh()
        assertEquals(golden.noteId, repository.state.value.entries.single().summary.noteId)
        val restored = repository.restore(golden.noteId)
        assertEquals(golden.title, restored.title)
        assertEquals(golden.blockIds, restored.blocks.map { it.string("id") })
        assertEquals(golden.blockKinds, restored.blocks.map { it.string("kind") })
        assertTrue(restored.blocks.single { it.string("id") == "33333333-3333-4333-8333-333333333333" }
            .string("text").contains("Web continuation"))
        assertEquals("tail\n", restored.blocks.single { it.string("id") == "88888888-8888-4888-8888-888888888888" }
            .string("text"))
        golden.materials.filterKeys { it.startsWith("assets/") }.forEach { (path, bytes) ->
            assertArrayEquals(bytes, File(context.noBackupFilesDir, "notes/${golden.noteId}/$path").readBytes())
        }
        golden.materials.filterKeys { it.startsWith("recordings/") }.forEach { (path, bytes) ->
            val archived = File(context.noBackupFilesDir, "cloud-recording-archives/${golden.noteId}/$path")
            assertArrayEquals(bytes, archived.readBytes())
            assertEquals(sha256(bytes), sha256(archived.readBytes()))
        }
        assertTrue(repository.hasReadOnlyRecordingArchive(golden.noteId))

        val second = CloudNoteRepository(context, provider, auth, settings)
        second.refresh()
        try {
            second.restore(golden.noteId)
            throw AssertionError("existing local note must not be replaced")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("不会覆盖本机原件"))
        }
    }

    private fun loadGoldenOrSkip(): Golden {
        val base = javaClass.classLoader!!.getResource("notes/web-continuation-v1")
        val root = File(requireNotNull(base) { "real Web golden is not installed" }.toURI())
        val required = listOf("input.json", "manifest.json")
        check(required.all { root.resolve(it).isFile }) { "golden is incomplete" }
        return Golden.load(root)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Golden(
        val noteId: String,
        val title: String,
        val blockIds: List<String>,
        val blockKinds: List<String>,
        val materials: Map<String, ByteArray>,
        val dispatcher: Dispatcher,
    ) {
        companion object {
            fun load(root: File): Golden {
                val json = Json { ignoreUnknownKeys = true }
                val input = json.decodeFromString<NoteMaterialInput>(root.resolve("input.json").readText())
                val manifest = json.decodeFromString<NoteMaterialManifest>(root.resolve("manifest.json").readText())
                val limits = NoteMaterialUploadLimitsDto(2048, 1_000_000, 10, Long.MAX_VALUE, 64 * 1024 * 1024, 10, 5 * 1024 * 1024, 24)
                val uploadFiles = manifest.files.mapIndexed { index, file ->
                    val partCount = ((file.sizeBytes + limits.partSizeBytes - 1) / limits.partSizeBytes).toInt()
                    NoteMaterialUploadFileDto(index, file.kind.name, partCount, file.path, file.sha256, file.sizeBytes, (0 until partCount).toList())
                }
                val upload = NoteMaterialUploadDto(
                    baseVersionId = "22222222-2222-4222-8222-222222222222",
                    createdAt = "2026-11-05T00:00:00Z", currentVersionId = manifest.versionId,
                    expiresAt = "2099-01-01T00:00:00Z", files = uploadFiles, hasConflict = false,
                    isCurrent = true,
                    limits = limits,
                    noteId = manifest.noteId, phase = "Uploaded", versionId = manifest.versionId,
                )
                val version = NoteMaterialVersionDto(
                    root.resolve("input.json").readText(), root.resolve("manifest.json").readText(), upload,
                )
                val timestamp = "2026-11-05T00:00:00Z"
                val page = NativeNotePageDto(
                    listOf(NativeNoteSummaryDto(manifest.versionId, false, manifest.noteId, input.document.title)), 0, 1,
                )
                val listResponse = json.encodeToString(NativeNotePageDtoApiResponse(0, page, "ok", timestamp))
                val versionResponse = json.encodeToString(NoteMaterialVersionDtoApiResponse(0, version, "ok", timestamp))
                val files = root.walkTopDown().filter { it.isFile && it.relativeTo(root).invariantSeparatorsPath !in setOf("input.json", "manifest.json", "request.json") }
                    .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
                val bodyType = "application/json; charset=utf-8".toMediaType()
                val dispatcher = object : Dispatcher() {
                    override fun dispatch(requestLine: RecordedRequest): MockResponse {
                        return when {
                            requestLine.path!!.startsWith("/api/v1/native-notes?") -> MockResponse().setBody(listResponse).setHeader("Content-Type", bodyType)
                            requestLine.path!!.endsWith("/current") -> MockResponse().setBody(versionResponse).setHeader("Content-Type", bodyType)
                            "/files/" in requestLine.path!! -> {
                                val index = requestLine.path!!.substringAfterLast('/').toInt()
                                val path = manifest.files[index].path
                                MockResponse().setBody(okio.Buffer().write(files.getValue(path)))
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                return Golden(input.document.id, input.document.title,
                    input.document.blocks.map { it.string("id") }, input.document.blocks.map { it.string("kind") }, files, dispatcher)
            }
        }
    }

}

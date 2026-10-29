package com.pim.app.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.core.auth.AuthRefreshOperation
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthTokens
import com.pim.core.auth.SecurePreferencesFactory
import com.pim.core.auth.TokenManager
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.network.AuthInterceptor
import com.pim.core.network.AuthRefreshCoordinator
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteAsrBindingTest {
    private val server = MockWebServer()
    private lateinit var tokens: TokenManager
    private lateinit var settings: ServerSettingsStore
    private lateinit var binding: BoundUploadSession
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val ok = """{"code":0,"message":"ok","data":[],"timestamp":"2026-10-29T00:00:00Z"}"""

    @Before fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        tokens = TokenManager(object : SecurePreferencesFactory {
            override fun open() = context.getSharedPreferences("asr-auth-${newNoteId()}", Context.MODE_PRIVATE)
        }, nowMillis = { 1_000L })
        settings = ServerSettingsStore(context, tokens)
        settings.setBaseUrl(server.url("/api/v1/").toString())
        binding = BoundUploadSession(PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin, a)
        owner(a, "access-a")
    }

    @After fun close() { server.shutdown() }

    private fun owner(id: String, access: String) {
        assertTrue(tokens.saveOwned(
            access, "refresh-$access", Long.MAX_VALUE, binding.serverIdentity, id, id.take(4),
        ))
    }

    private fun api(
        refresh: AuthRefreshOperation = AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected },
        beforeAuth: (() -> Unit)? = null,
    ): ApiService {
        val coordinator = AuthRefreshCoordinator(tokens, refresh, nowMillis = { 1_000L })
        val client = OkHttpClient.Builder().apply {
            if (beforeAuth != null) addInterceptor { chain -> beforeAuth(); chain.proceed(chain.request()) }
        }.addInterceptor(AuthInterceptor(tokens, coordinator, settings)).build()
        return ApiClientProvider(client, Json { ignoreUnknownKeys = true }, settings).noteUploadApiService(binding)
    }

    @Test fun asrTaskReadUsesTheCapturedOwnerOnTheNativeNotesPath() = runBlocking {
        server.enqueue(MockResponse().setBody(ok))
        assertEquals(0, api().getNoteAsrTasks().code)
        val request = server.takeRequest()
        assertEquals("/api/v1/native-notes/asr-tasks", request.path)
        assertEquals("Bearer access-a", request.getHeader("Authorization"))
    }

    @Test fun ownerSwitchBeforeAsrHeaderAttachmentBlocksWithoutSending() = runBlocking {
        var changed = false
        val api = api(beforeAuth = {
            if (!changed) {
                changed = true
                owner(b, "access-b")
            }
        })
        try { api.getNoteAsrTasks(); fail("Must block changed owner") }
        catch (_: UploadSessionChangedException) { }
        assertEquals(0, server.requestCount)
        assertEquals(b, tokens.snapshot().ownerId)
    }

    @Test fun accountSwitchDuring401NeverRetriesTheAsrRequestWithTheNewOwner() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val api = api(AuthRefreshOperation { _, _ ->
            owner(b, "access-b")
            AuthRefreshResult.Rejected
        })
        try { api.getNoteAsrTasks(); fail("Must not retry with another owner") }
        catch (_: UploadSessionChangedException) { }
        assertEquals(1, server.requestCount)
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
        assertEquals("access-b", tokens.accessToken())
    }

    @Test fun normalRefreshKeepsTheSameAsrOwnerForOneRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody(ok))
        api(AuthRefreshOperation { _, _ ->
            AuthRefreshResult.Success(
                AuthTokens("new-a", "new-refresh", Long.MAX_VALUE),
                a,
                "alice",
            )
        }).getNoteAsrTasks()
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-a", server.takeRequest().getHeader("Authorization"))
        assertEquals(a, tokens.snapshot().ownerId)
    }

    @Test fun asrTaskBindingCannotBeUsedForAnotherServer() = runBlocking {
        settings.setBaseUrl("https://another.invalid/api/v1/")
        try { api().getNoteAsrTasks(); fail("Must block another server") }
        catch (_: UploadSessionChangedException) { }
        assertEquals(0, server.requestCount)
    }
}

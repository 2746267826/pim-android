package com.pim.app.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.core.auth.*
import com.pim.core.network.*
import com.pim.core.settings.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteUploadBindingTest {
    private val server = MockWebServer()
    private lateinit var tokens: TokenManager
    private lateinit var settings: ServerSettingsStore
    private lateinit var binding: BoundUploadSession
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val ok = """{"code":0,"message":"ok","data":"stored","timestamp":"2026-10-28T00:00:00Z"}"""

    @Before fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        tokens = TokenManager(object : SecurePreferencesFactory {
            override fun open() = context.getSharedPreferences("upload-auth-${newNoteId()}", Context.MODE_PRIVATE)
        }, nowMillis = { 1_000L })
        settings = ServerSettingsStore(context, tokens)
        settings.setBaseUrl(server.url("/api/v1/").toString())
        binding = BoundUploadSession(PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin, a)
        owner(a, "access-a")
    }
    @After fun close() { server.shutdown() }
    private fun owner(id: String, access: String) {
        assertTrue(tokens.saveOwned(access, "refresh-$access", Long.MAX_VALUE, binding.serverIdentity, id, id.take(4)))
    }
    private fun api(refresh: AuthRefreshOperation = AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }, beforeAuth: (() -> Unit)? = null): ApiService {
        val coordinator = AuthRefreshCoordinator(tokens, refresh, nowMillis = { 1_000L })
        val client = OkHttpClient.Builder().apply {
            if (beforeAuth != null) addInterceptor { chain -> beforeAuth(); chain.proceed(chain.request()) }
        }.addInterceptor(AuthInterceptor(tokens, coordinator, settings)).build()
        return ApiClientProvider(client, Json { ignoreUnknownKeys = true }, settings).noteUploadApiService(binding)
    }
    private suspend fun send(api: ApiService) = api.uploadNoteMaterialPart(newNoteId(), 0, 0, "a".repeat(64), byteArrayOf(1, 2).toRequestBody())

    @Test fun headerUsesTheSameSnapshotAsTheCapturedOwner() = runBlocking {
        server.enqueue(MockResponse().setBody(ok)); send(api())
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun ownerSwitchBeforeActualHeaderAttachmentBlocksWithoutSendingOldMaterial() = runBlocking {
        var changed = false
        val api = api(beforeAuth = { if (!changed) { changed = true; owner(b, "access-b") } })
        try { send(api); fail("Must block") } catch (_: UploadSessionChangedException) { }
        assertEquals(0, server.requestCount); assertEquals(b, tokens.snapshot().ownerId)
    }
    @Test fun accountSwitchDuring401RefreshNeverRetriesOldMaterialWithNewUserToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val api = api(AuthRefreshOperation { _, _ -> owner(b, "access-b"); AuthRefreshResult.Rejected })
        try { send(api); fail("Must block retry") } catch (_: UploadSessionChangedException) { }
        assertEquals(1, server.requestCount)
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
        assertEquals("access-b", tokens.accessToken())
    }
    @Test fun normalRefreshKeepsOwnerAndRotatesTheBearerForOneRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody(ok))
        send(api(AuthRefreshOperation { _, _ -> AuthRefreshResult.Success(AuthTokens("new-a", "new-refresh", Long.MAX_VALUE), a, "alice") }))
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-a", server.takeRequest().getHeader("Authorization"))
        assertEquals(a, tokens.snapshot().ownerId)
    }
    @Test fun anotherServerAndLogoutBothBlockTheBoundClient() = runBlocking {
        val api = api(); settings.setBaseUrl("https://another.invalid/api/v1/")
        try { send(api); fail("Must block another target") } catch (_: UploadSessionChangedException) { }
        settings.setBaseUrl(server.url("/api/v1/").toString()); tokens.clear()
        try { send(api); fail("Must block logout") } catch (_: UploadSessionChangedException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun materialClientNeverFollowsProviderRedirectsWithCredentials() = runBlocking {
        val other = MockWebServer(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/steal")))
            try { send(api()); fail("Must expose redirect") } catch (e: retrofit2.HttpException) { assertEquals(302, e.code()) }
            assertEquals(0, other.requestCount); assertEquals(1, server.requestCount)
        } finally { other.shutdown() }
    }
    @Test fun boundClientCannotBeUsedAsAGenericAuthenticatedClient() = runBlocking {
        try { api().login(com.pim.core.contract.LoginRequest("unused", "unused")); fail("Must restrict routes") }
        catch (_: UploadSessionChangedException) { }
        assertEquals(0, server.requestCount)
    }
}

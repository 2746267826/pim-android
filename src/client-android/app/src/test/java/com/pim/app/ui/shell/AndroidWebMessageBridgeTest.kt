package com.pim.app.ui.shell

import android.content.Context
import android.net.Uri
import java.io.File
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.pim.core.auth.AuthRefreshOperation
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthSessionSnapshot
import com.pim.core.auth.AuthSessionStore
import com.pim.core.auth.AuthTokens
import com.pim.core.network.AuthRefreshCoordinator
import com.pim.core.settings.ServerSettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * PW-07a §12.4：桥上的旧行为 RED。
 *
 * 本文件仍然调用**今天的** `AndroidWebMessageBridge` 构造函数（不改构造、不改 handler）。
 * 旧断言里「token.request/token.refresh 返回 accessToken」「触发刷新」「返回 login_expired」
 * 「刷新抛 CancellationException 时向外传播」已按 B1–B4 改成固定 `auth_unavailable` +
 * 刷新操作 0 次的行为断言；它们在今天的产品代码上编译通过并**失败**（回复里仍带样例令牌 /
 * 刷新操作仍被调用）。「构造函数不再接收会话与刷新参数」是实施阶段的编译结果，不是 RED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidWebMessageBridgeTest {

    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pim_server_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `B1 token request returns auth_unavailable and no token bytes`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req1","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("req1", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(
            "旧行为把 access token 交给页面；PW-07a 后必须 ok:false，实际 $response",
            obj["ok"]?.jsonPrimitive?.boolean ?: true
        )
        assertEquals("auth_unavailable", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["accessToken"])
        assertFalse("回复正文不得含 access token 字节", response.contains(ACCESS_TOKEN))
        assertFalse("回复正文不得含 refresh token 字节", response.contains(REFRESH_TOKEN))
    }

    @Test
    fun `B1b token request without session returns auth_unavailable and no token bytes`() = runBlocking {
        val authStore = TestAuthSessionStore()
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req2","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("req2", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals(
            "无会话也必须回 auth_unavailable，不再是 login_expired",
            "auth_unavailable",
            obj["errorCode"]?.jsonPrimitive?.contentOrNull
        )
        assertNull(obj["accessToken"])
        assertFalse(response.contains(ACCESS_TOKEN))
    }

    @Test
    fun `B3 token request never calls the refresh operation and never rewrites the session`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, 100L, TRUSTED_ORIGIN)
        val now = { 200L }
        var refreshCount = 0
        val operation = AuthRefreshOperation { _, _ ->
            refreshCount++
            AuthRefreshResult.Success(AuthTokens("refreshed-access", "refreshed-refresh", FAR_FUTURE))
        }
        val coordinator = AuthRefreshCoordinator(authStore, operation, nowMillis = now)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, refreshCoordinator = coordinator, settings = settings)

        val before = authStore.snapshot()
        val response = bridge.handleMessage(
            """{"version":1,"id":"req3","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )
        val after = authStore.snapshot()

        assertEquals("刷新操作调用次数必须为 0，实际 $refreshCount", 0, refreshCount)
        assertEquals("逻辑会话快照前后必须相等", before, after)
        val obj = parse(response)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("auth_unavailable", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
        assertFalse(response.contains("refreshed-access"))
    }

    @Test
    fun `B2 token refresh returns auth_unavailable and no token bytes`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val operation = AuthRefreshOperation { _, _ ->
            AuthRefreshResult.Success(AuthTokens("refreshed-access", "refreshed-refresh", FAR_FUTURE))
        }
        val coordinator = AuthRefreshCoordinator(authStore, operation, nowMillis = { 0L })
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, refreshCoordinator = coordinator, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req4","type":"token.refresh","failedAccessToken":"$ACCESS_TOKEN"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("req4", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(
            "旧行为把刷新后的 access token 交给页面；PW-07a 后必须 ok:false，实际 $response",
            obj["ok"]?.jsonPrimitive?.boolean ?: true
        )
        assertEquals("auth_unavailable", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["accessToken"])
        assertFalse(response.contains("refreshed-access"))
        assertFalse(response.contains(ACCESS_TOKEN))
        assertFalse(response.contains(REFRESH_TOKEN))
    }

    @Test
    fun `B2b token refresh when rejected returns auth_unavailable instead of login_expired`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val operation = AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }
        val coordinator = AuthRefreshCoordinator(authStore, operation, nowMillis = { 0L })
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, refreshCoordinator = coordinator, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req5","type":"token.refresh","failedAccessToken":"$ACCESS_TOKEN"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("req5", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals(
            "刷新被拒也必须回 auth_unavailable，不再是 login_expired",
            "auth_unavailable",
            obj["errorCode"]?.jsonPrimitive?.contentOrNull
        )
        assertNull(obj["accessToken"])
        assertFalse(response.contains(ACCESS_TOKEN))
    }

    @Test
    fun `request from wrong origin returns server_mismatch`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req6","type":"token.request"}""",
            "https://evil.example",
            true
        )

        val obj = parse(response)
        assertEquals("req6", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("server_mismatch", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["accessToken"])
    }

    @Test
    fun `request without configured server returns server_mismatch`() = runBlocking {
        val authStore = TestAuthSessionStore()
        val emptySettings = ServerSettingsStore(context, authStore)
        val bridge = createBridge(authStore = authStore, settings = emptySettings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req7","type":"token.request"}""",
            "https://any.example",
            true
        )

        val obj = parse(response)
        assertEquals("req7", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("server_mismatch", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `malformed json returns invalid_request`() = runBlocking {
        val authStore = TestAuthSessionStore()
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            "not json at all",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `missing version field returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"id":"req","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unsupported protocol version returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"version":2,"id":"req","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `missing id field returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"version":1,"type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `missing type field returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"version":1,"id":"req"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `blank id returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"version":1,"id":"   ","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `blank type returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":""}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `unknown type returns invalid_request`() = runBlocking {
        val bridge = createBridge()

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"unknown.type"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `non main frame returns invalid_request`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"token.request"}""",
            TRUSTED_ORIGIN,
            false
        )

        val obj = parse(response)
        assertEquals("invalid_request", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `native state request returns lightweight snapshot`() = runBlocking {
        val bridge = createBridge(
            nativeStateProvider = {
                mapOf(
                    "collectionMode" to "foreground",
                    "uploading" to true,
                    "pending" to 5,
                    "batteryOptimized" to true,
                    "secret" to "must-not-cross"
                )
            }
        )

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"native.state.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertTrue(obj["ok"]?.jsonPrimitive?.boolean ?: false)
        val state = obj["nativeState"]?.jsonObject
        assertNotNull(state)
        assertEquals("foreground", state?.get("collectionMode")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, state?.get("uploading")?.jsonPrimitive?.boolean)
        assertEquals(5, state?.get("pending")?.jsonPrimitive?.int)
        assertEquals(true, state?.get("batteryOptimized")?.jsonPrimitive?.boolean)
        assertNull(state?.get("secret"))
    }

    @Test
    fun `native state provider failure returns a visible error`() = runBlocking {
        val bridge = createBridge(
            nativeStateProvider = { error("state unavailable") }
        )

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"native.state.request"}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("native_state_unavailable", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `page report accepts only whitelist fields`() = runBlocking {
        var capturedReport: Map<String, String?>? = null
        val bridge = createBridge(
            pageReportSink = { capturedReport = it }
        )

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"page.report","report":{"hasServerData":true,"generatedAt":"2025-01-01T00:00:00Z","error":null,"arbitraryField":"should be ignored"}}""",
            TRUSTED_ORIGIN,
            true
        )

        val obj = parse(response)
        assertTrue(obj["ok"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(capturedReport)
        assertEquals("true", capturedReport?.get("hasServerData"))
        assertEquals("2025-01-01T00:00:00Z", capturedReport?.get("generatedAt"))
        assertNull(capturedReport?.get("error"))
        assertFalse(capturedReport?.containsKey("arbitraryField") ?: true)
    }

    @Test
    fun `B1c no token message type returns any token byte to the page`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"token.request"}""",
            TRUSTED_ORIGIN,
            true
        )
        assertNull(parse(response)["refreshToken"])
        assertNull(parse(response)["accessToken"])
        assertFalse(response.contains(ACCESS_TOKEN))
        assertFalse(response.contains(REFRESH_TOKEN))

        val operation = AuthRefreshOperation { _, _ ->
            AuthRefreshResult.Success(AuthTokens("new-access", "new-refresh", FAR_FUTURE))
        }
        val coordinator = AuthRefreshCoordinator(authStore, operation, nowMillis = { 0L })
        val bridge2 = createBridge(authStore = authStore, refreshCoordinator = coordinator, settings = settings)

        val response2 = bridge2.handleMessage(
            """{"version":1,"id":"req","type":"token.refresh","failedAccessToken":"$ACCESS_TOKEN"}""",
            TRUSTED_ORIGIN,
            true
        )
        assertNull(parse(response2)["refreshToken"])
        assertNull(parse(response2)["accessToken"])
        assertFalse(response2.contains(ACCESS_TOKEN))
        assertFalse(response2.contains("new-access"))
    }

    @Test
    fun `install returns false when web message listener is unsupported`() {
        val bridge = createBridge()
        val webView = WebView(context)

        val installed = bridge.install(webView, isFeatureSupported = { false })

        assertFalse(installed)
    }

    @Test
    fun `install returns true when web message listener is supported`() {
        val bridge = createBridge()
        val webView = WebView(context)

        val installed = bridge.install(
            webView,
            isFeatureSupported = { true },
            addListener = { _, _, _, _ -> }
        )

        assertTrue(installed)
    }

    @Test
    fun `install returns false when listener installation fails`() {
        val bridge = createBridge()
        val webView = WebView(context)

        val installed = bridge.install(
            webView,
            isFeatureSupported = { true },
            addListener = { _, _, _, _ -> throw IllegalStateException("listener unavailable") }
        )

        assertFalse(installed)
    }

    @Test
    fun `token request from non-matching origin after server switch returns server_mismatch`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, settings = settings)

        val response = bridge.handleMessage(
            """{"version":1,"id":"req","type":"token.request"}""",
            "https://other-server.example",
            true
        )

        val obj = parse(response)
        assertEquals("server_mismatch", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `reply failure after web view destruction is contained`() {
        var captured: Throwable? = null
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, throwable -> captured = throwable }
        )

        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, FAR_FUTURE, TRUSTED_ORIGIN)
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(scope = scope, authStore = authStore, settings = settings)

        val webView = WebView(context)
        var listener: WebViewCompat.WebMessageListener? = null
        bridge.install(
            webView,
            isFeatureSupported = { true },
            addListener = { _, _, _, l -> listener = l }
        )

        val message = WebMessageCompat(
            """{"version":1,"id":"req","type":"token.request"}"""
        )
        val replyProxy = object : JavaScriptReplyProxy() {
            override fun postMessage(message: String) {
                throw IllegalStateException("webview destroyed")
            }

            override fun postMessage(message: ByteArray) {
                throw IllegalStateException("webview destroyed")
            }
        }

        listener?.onPostMessage(webView, message, Uri.parse(TRUSTED_ORIGIN), true, replyProxy)

        scope.cancel()

        assertNull(captured)
    }

    @Test
    fun `B4 a throwing refresh operation is not called and the reply is still auth_unavailable`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, 100L, TRUSTED_ORIGIN)
        var refreshCalls = 0
        val operation = AuthRefreshOperation { _, _ ->
            refreshCalls++
            throw IllegalStateException("refresh must not be reached for token.* messages")
        }
        val coordinator = AuthRefreshCoordinator(authStore, operation, nowMillis = { 200L })
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, refreshCoordinator = coordinator, settings = settings)

        val outcome = runCatching {
            bridge.handleMessage(
                """{"version":1,"id":"req-auth-unavailable","type":"token.request"}""",
                TRUSTED_ORIGIN,
                true
            )
        }
        assertTrue("令牌回复必须是固定失败，不得抛异常：$outcome", outcome.isSuccess)
        val raw = outcome.getOrThrow()
        val obj = parse(raw)
        assertEquals("req-auth-unavailable", obj["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("刷新操作调用次数必须为 0，实际 $refreshCalls", 0, refreshCalls)
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("auth_unavailable", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["accessToken"])
        assertNull(obj["refreshToken"])
        assertFalse(raw.contains(ACCESS_TOKEN))
    }

    @Test
    fun `B4b cancellation from the refresh operation is unreachable because refresh is never called`() = runBlocking {
        val authStore = TestAuthSessionStore()
        authStore.save(ACCESS_TOKEN, REFRESH_TOKEN, 100L, TRUSTED_ORIGIN)
        var refreshCalls = 0
        val operation = AuthRefreshOperation { _, _ ->
            refreshCalls++
            throw CancellationException("cancelled")
        }
        val coordinator = AuthRefreshCoordinator(authStore, operation, nowMillis = { 200L })
        val settings = ServerSettingsStore(context, authStore)
        settings.setBaseUrl(SERVER_URL)
        val bridge = createBridge(authStore = authStore, refreshCoordinator = coordinator, settings = settings)

        val outcome = runCatching {
            bridge.handleMessage(
                """{"version":1,"id":"req-cancel","type":"token.request"}""",
                TRUSTED_ORIGIN,
                true
            )
        }
        assertTrue(
            "PW-07a 后 token.request 不再触发刷新，因此不得再抛出 CancellationException：$outcome",
            outcome.isSuccess
        )
        assertEquals("刷新操作调用次数必须为 0，实际 $refreshCalls", 0, refreshCalls)
        val obj = parse(outcome.getOrThrow())
        assertFalse(obj["ok"]?.jsonPrimitive?.boolean ?: true)
        assertEquals("auth_unavailable", obj["errorCode"]?.jsonPrimitive?.contentOrNull)
    }

    // --- helpers ---

    private fun parse(raw: String): JsonObject {
        return json.parseToJsonElement(raw).jsonObject
    }

    /*
     * 规格 §7 的实施阶段编译结果：`AndroidWebMessageBridge` 构造已删除
     * `authSessionStore` 与 `refreshCoordinator`（令牌回复固定 auth_unavailable，不再读会话、
     * 不再刷新）。B3/B4/B4b 传进来的 `refreshCoordinator` 因此只剩「记录型夹具」作用：
     * 它证明「一旦被调用就会计数」，而桥已不再持有它，所以计数必须保持 0。
     * 这一步只改调用方式，不替代任何安全断言。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun createBridge(
        authStore: AuthSessionStore = TestAuthSessionStore(),
        refreshCoordinator: AuthRefreshCoordinator? = null,
        settings: ServerSettingsStore? = null,
        nativeStateProvider: (() -> Map<String, Any?>)? = null,
        pageReportSink: ((Map<String, String?>) -> Unit)? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    ): AndroidWebMessageBridge {
        val resolvedSettings = settings ?: ServerSettingsStore(context, authStore).also {
            it.setBaseUrl(SERVER_URL)
        }
        return AndroidWebMessageBridge(
            serverSettingsStore = resolvedSettings,
            scope = scope,
            nativeStateProvider = nativeStateProvider,
            pageReportSink = pageReportSink
        )
    }

    // --- source contract: remove(webView) must not be gated by a shared marker ---

    @Test
    fun `AndroidWebMessageBridge does not use installedObjectName field for cleanup`() {
        val bridgeFile = repoFile(
            "src", "main", "java", "com", "pim", "app", "ui", "shell", "AndroidWebMessageBridge.kt"
        )
        val source = bridgeFile.readText()
        assertFalse(
            "installedObjectName is a shared mutable marker; remove(webView) must " +
                "always best-effort remove JS_OBJECT_NAME from the supplied WebView.",
            source.contains("installedObjectName")
        )
    }

    private fun repoFile(vararg parts: String): File {
        var current: File? = File("").canonicalFile
        while (current != null) {
            val candidate = parts.fold(current) { dir, part -> dir.resolve(part) }
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        error("Could not find ${parts.joinToString(File.separator)}")
    }

    private class TestAuthSessionStore : AuthSessionStore {
        private var snapshot: AuthSessionSnapshot = AuthSessionSnapshot(null)

        override fun snapshot(): AuthSessionSnapshot = snapshot

        override fun save(
            accessToken: String,
            refreshToken: String,
            expiresAtUtcMillis: Long,
            serverIdentity: String
        ): Boolean {
            snapshot = AuthSessionSnapshot(
                tokens = AuthTokens(accessToken, refreshToken, expiresAtUtcMillis),
                serverIdentity = serverIdentity
            )
            return true
        }

        override fun clear(): Boolean {
            snapshot = AuthSessionSnapshot(null)
            return true
        }
    }

    companion object {
        const val TRUSTED_ORIGIN = "https://pim.example"
        const val SERVER_URL = "https://pim.example/api/v1/"
        const val ACCESS_TOKEN = "access-token-valid"
        const val REFRESH_TOKEN = "refresh-token-valid"
        const val FAR_FUTURE = Long.MAX_VALUE
    }
}

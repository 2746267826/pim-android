package com.pim.app.ui.shell

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.core.auth.AuthRefreshOperation
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthSessionSnapshot
import com.pim.core.auth.AuthSessionStore
import com.pim.core.auth.AuthTokens
import com.pim.core.network.AuthInterceptor
import com.pim.core.network.AuthRefreshCoordinator
import com.pim.core.settings.ServerSettingsStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okio.Buffer
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * PW-07a 设备侧「真实系统 WebView」探针（自造数据，只用 127.0.0.1 随机端口）。
 *
 * 证据等级：**模拟器上的系统 WebView**（AVD pim361r2 / Android 16 / WebView 134），
 * 不是 Robolectric、也不是 MockWebServer 的 JVM 判据。它补的是规格 §11 的 NOT-VERIFIED：
 * 1) 嵌入页自己的 `fetch` 是否真的进入 `shouldInterceptRequest`；
 * 2) 上游 302 时，空 502 在系统 WebView 里的投递；
 * 3) `pimAndroid.onmessage` 的真实投递；
 * 4) 扫描流中途抛 `IOException` 时，页面拿不到令牌字节、也拿不到「完整成功」的正文。
 * 它**不**证明产品 APK 里 TodayScreen / TracksScreen / PimShell 的入口
 * （那需要真实登录与真实后端，仍是 NOT-VERIFIED）。
 */
@RunWith(AndroidJUnit4::class)
class NativeEmbedSystemWebViewProbeTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var session: ProbeSessionStore
    private lateinit var settings: ServerSettingsStore
    private lateinit var proxy: NativeEmbedRequestProxy
    private lateinit var bridge: AndroidWebMessageBridge
    private var webView: WebView? = null
    private var mode: String = MODE_ALLOWED

    @Before
    fun setUp() {
        context = instrumentation.targetContext
        context.getSharedPreferences("pim_server_settings", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        session = ProbeSessionStore()
        settings = ServerSettingsStore(context, session)
        settings.setBaseUrl("${serverOrigin()}/api/v1/")
        session.save(ACCESS, REFRESH, Long.MAX_VALUE - 1, serverOrigin())
        val refresh = AuthRefreshOperation { _, _ ->
            AuthRefreshResult.Success(AuthTokens("probe-refreshed", "probe-refreshed-refresh", Long.MAX_VALUE - 1))
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session, AuthRefreshCoordinator(session, refresh, nowMillis = { 0L })))
            .build()
        proxy = NativeEmbedRequestProxy(client, session, settings)
        bridge = AndroidWebMessageBridge(
            serverSettingsStore = settings,
            scope = CoroutineScope(Dispatchers.Main),
            nativeStateProvider = {
                mapOf("collectionMode" to true, "pending" to 2, "triggerReason" to "schedule")
            }
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith(TODAY_DOCUMENT_PATH) || path.startsWith("/embed/android") ->
                        MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .setBody(PAGE_HTML)
                    path.startsWith(OVERVIEW_PATH) -> apiResponse(request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After
    fun tearDown() {
        closeWebView()
        runCatching { server.shutdown() }
    }

    @Test
    fun probe1_allowedEmbedFetchIsInterceptedAndOnmessageIsDelivered() {
        mode = MODE_ALLOWED
        val probe = loadPageAndWait()

        val fetch = probe.optJSONObject("fetch")
        assertNotNull("嵌入页 fetch 必须被 shouldInterceptRequest 接住（${diagnostics(probe)}）", fetch)
        assertEquals(
            "被代发的 GET 必须由原生带 Bearer 取回 proxied 正文（页面自己发只会拿到 direct）：${diagnostics(probe)}",
            "proxied",
            JSONObject(fetch!!.optString("body")).optJSONObject("data")?.optString("marker")
        )
        assertEquals("代发成功必须保留 200：${diagnostics(probe)}", 200, fetch.optInt("status"))
        Log.i(PROBE_TAG, "probe1 status=${fetch.optInt("status")} body=${fetch.optString("body")} reply=${probe.optString("reply", "")}")

        assertFalse(
            "系统 WebView 必须在页面脚本执行前注入 pimAndroid（${diagnostics(probe)}）",
            probe.has("postError")
        )
        val reply = probe.optString("reply", "")
        assertTrue("pimAndroid.onmessage 必须收到原生 v1 回复（${diagnostics(probe)}）", reply.startsWith("{"))
        assertTrue("回复必须是 ok:true 的 v1 信封，实际 $reply", reply.contains("\"ok\":true"))
        assertTrue("回复必须带 nativeState，实际 $reply", reply.contains("nativeState"))
        assertTrue("回复必须按 id 配对，实际 $reply", reply.contains("\"id\":\"probe-1\""))
    }

    @Test
    fun probe2_upstream302IsDeliveredAsEmpty502() {
        mode = MODE_REDIRECT
        val probe = loadPageAndWait()

        val fetch = probe.optJSONObject("fetch")
        assertNotNull("302 必须被代发链路处理（${diagnostics(probe)}）", fetch)
        val status = fetch!!.optInt("status")
        val body = fetch.optString("body", "")
        Log.i(
            PROBE_TAG,
            "probe2 status=$status bodyLength=${body.length} error=${fetch.optString("error", "")}"
        )
        /*
         * 设备实测（3 次重跑）：上游 302 时代发器一律返回空 502（JVM 侧由 T4/P6 逐字锁定），
         * 但系统 WebView 把它交给页面 `fetch` 时有两种可观察形态 —— 读到空 502，或直接
         * `TypeError: Failed to fetch`。两种都不是 3xx、都不含 Location/上游正文。
         * 因此这里锁的性质是「不是 3xx、不泄漏」，而不是某一种投递形态。
         */
        assertTrue(
            "302 不得作为 3xx 交给页面（只允许空 502 或 fetch 失败），实际 status=$status probe=$probe",
            status == 502 || status == -1
        )
        if (status == 502) {
            assertEquals("空 502 必须是空正文，实际 $probe", "", body)
        }
        assertFalse("不得交出 Location/上游正文，实际 $probe", probe.toString().contains("steal"))
    }

    @Test
    fun probe3_streamFailureNeverHandsTheTokenToThePage() {
        mode = MODE_STREAM_FAILURE
        val probe = loadPageAndWait(timeoutMs = 40_000)

        val fetch = probe.optJSONObject("fetch")
        assertNotNull("扫描失败的响应必须回到页面（probe=${probe.toString().take(400)}）", fetch)
        assertFalse(
            "会话令牌的字节绝不能出现在页面拿到的正文里（probe=${probe.toString().take(400)}）",
            probe.toString().contains(ACCESS)
        )
        val status = fetch!!.optInt("status")
        val body = fetch.optString("body", "")
        Log.i(
            PROBE_TAG,
            "probe3 status=$status bodyLength=${body.length} error=${fetch.optString("error", "")} " +
                "bodyHasToken=${body.contains(ACCESS)} requests=[${recordedRequests()}]"
        )
        assertTrue(
            "页面对截断/失败响应不得是「完整成功」：status=$status bodyLength=${body.length}",
            status == -1 || body.length < BIG_BODY_LENGTH
        )
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun serverOrigin(): String = "http://127.0.0.1:${server.port}"

    private fun apiResponse(request: RecordedRequest): MockResponse {
        return when (mode) {
            MODE_REDIRECT -> MockResponse().setResponseCode(302)
                .addHeader("Location", "http://127.0.0.1:1/steal")
            MODE_STREAM_FAILURE -> MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(Buffer().write(bigBodyWithLateToken()))
            else -> {
                val authorized = request.getHeader("Authorization") == "Bearer $ACCESS"
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(if (authorized) JSON_PROXIED else JSON_DIRECT)
            }
        }
    }

    private fun bigBodyWithLateToken(): ByteArray {
        val head = ByteArray(70_000) { 'a'.code.toByte() }
        return head + ACCESS.toByteArray() + "tail".toByteArray()
    }

    private fun loadPageAndWait(timeoutMs: Long = 20_000): JSONObject {
        instrumentation.runOnMainSync {
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = createPimWebViewClient(
                trustedOrigin = serverOrigin(),
                openExternal = { null },
                onLoading = { },
                onContent = { },
                onError = { },
                onHistoryUrlChanged = null,
                embedProxy = proxy
            )
            assertTrue(
                "真实 AndroidWebMessageBridge 必须能在系统 WebView 上装上 WEB_MESSAGE_LISTENER",
                bridge.install(view)
            )
            webView = view
            view.loadUrl("${serverOrigin()}$TODAY_DOCUMENT_PATH")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var snapshot: JSONObject = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            snapshot = evaluateProbe()
            val hasFetch = snapshot.optJSONObject("fetch") != null
            if (hasFetch && (mode != MODE_ALLOWED || snapshot.optString("reply", "").startsWith("{"))) {
                return snapshot
            }
            Thread.sleep(250)
        }
        return snapshot
    }

    private fun evaluateProbe(): JSONObject {
        val raw = evaluate(
            "JSON.stringify({reply: window.__probe ? window.__probe.reply : null," +
                " fetch: window.__probe ? window.__probe.fetch : null," +
                " postError: window.__probe ? window.__probe.postError : null," +
                " scriptError: window.__probe ? window.__probe.scriptError : null," +
                " pimAndroidType: window.__probe ? window.__probe.pimAndroidType : null," +
                " ready: document.readyState, href: location.href})"
        )
        return runCatching { JSONTokener(raw).nextValue() as? JSONObject }.getOrNull() ?: JSONObject()
    }

    private fun evaluate(expression: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            val view = webView
            if (view == null) {
                latch.countDown()
            } else {
                view.evaluateJavascript(expression) { value ->
                    result = value ?: "null"
                    latch.countDown()
                }
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return runCatching { JSONTokener(result).nextValue() as? String ?: result }.getOrDefault(result)
    }

    /** 上游收到的请求（诊断用；会消费 MockWebServer 的记录队列）。 */
    private fun recordedRequests(): String = buildList {
        while (true) {
            val recorded = server.takeRequest(50, TimeUnit.MILLISECONDS) ?: break
            add("${recorded.method} ${recorded.path} auth=${recorded.getHeader("Authorization")} range=${recorded.getHeader("Range")}")
        }
    }.joinToString(" | ")

    /** 把设备侧可观察量拼进断言文本：页面脚本错误、pimAndroid 类型、就绪状态、上游收到的请求。 */
    private fun diagnostics(probe: JSONObject): String {
        val paths = buildList {
            while (true) {
                val recorded = server.takeRequest(50, TimeUnit.MILLISECONDS) ?: break
                add("${recorded.method} ${recorded.path} auth=${recorded.getHeader("Authorization")}")
            }
        }
        return "probe=$probe requests=$paths requestCount=${server.requestCount}"
    }

    private fun closeWebView() {
        val view = webView ?: return
        instrumentation.runOnMainSync {
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
        webView = null
    }

    private class ProbeSessionStore : AuthSessionStore {
        @Volatile
        private var current: AuthSessionSnapshot = AuthSessionSnapshot(null)

        override fun snapshot(): AuthSessionSnapshot = current

        override fun save(
            accessToken: String,
            refreshToken: String,
            expiresAtUtcMillis: Long,
            serverIdentity: String
        ): Boolean {
            current = AuthSessionSnapshot(AuthTokens(accessToken, refreshToken, expiresAtUtcMillis), serverIdentity)
            return true
        }

        override fun clear(): Boolean {
            current = AuthSessionSnapshot(null)
            return true
        }
    }

    private companion object {
        const val TODAY_DOCUMENT_PATH = "/embed/android/today"
        const val OVERVIEW_PATH = "/api/v1/mobile/location/analytics/overview"
        const val ACCESS = "probe-native-access-1"
        const val REFRESH = "probe-native-refresh-1"
        const val MODE_ALLOWED = "allowed"
        const val MODE_REDIRECT = "redirect"
        const val MODE_STREAM_FAILURE = "stream-failure"
        const val JSON_PROXIED = """{"code":0,"data":{"marker":"proxied"}}"""
        const val JSON_DIRECT = """{"code":0,"data":{"marker":"direct"}}"""
        const val BIG_BODY_LENGTH = 70_019
        const val PROBE_TAG = "EmbedProbe"

        val PAGE_HTML = """
            <!doctype html><html><head><meta charset="utf-8"></head><body>
            <script>
            window.__probe = { reply: null, fetch: null, scriptError: null,
              pimAndroidType: typeof window.pimAndroid, ready: document.readyState };
            window.onerror = function (m) { window.__probe.scriptError = String(m); };
            try {
            window.pimAndroid.onmessage = function (e) { window.__probe.reply = String(e.data); };
            try {
              window.pimAndroid.postMessage(JSON.stringify({ version: 1, id: 'probe-1', type: 'native.state.request' }));
            } catch (err) {
              window.__probe.postError = String(err);
            }
            fetch('/api/v1/mobile/location/analytics/overview')
              .then(function (r) { return r.text().then(function (t) { window.__probe.fetch = { status: r.status, body: t }; }); })
              .catch(function (e) { window.__probe.fetch = { status: -1, error: String(e) }; });
            } catch (err) { window.__probe.scriptError = String(err); }
            </script>
            </body></html>
        """.trimIndent()
    }
}

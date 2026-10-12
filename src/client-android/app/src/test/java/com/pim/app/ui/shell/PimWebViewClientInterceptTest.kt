package com.pim.app.ui.shell

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthTokens
import com.pim.core.network.AuthInterceptor
import com.pim.core.network.AuthRefreshCoordinator
import com.pim.core.settings.ServerSettingsStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/*
 * PW-07a §12.2 Robolectric：真实 `createPimWebViewClient` 的 shouldInterceptRequest 接线。
 *
 * RED-0（§12.1）下客户端没有 override shouldInterceptRequest、也没有把 `embedProxy`
 * 接上，因此允许请求得到 `null`，失败文本是「期待有 WebResourceResponse，实际 null」。
 * 本用例**不证明**系统 WebView 会调用这个方法（规格 §11 NOT-VERIFIED）。
 *
 * T2/T3/T5/T6/T7 是 guard：恒 null 的今天先通过，实施后必须继续通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PimWebViewClientInterceptTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var session: RecordingAuthSessionStore
    private lateinit var settings: ServerSettingsStore
    private lateinit var proxy: NativeEmbedRequestProxy
    private lateinit var client: WebViewClient
    private lateinit var webView: WebView

    @Before
    fun setUp() {
        ShadowLog.clear()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pim_server_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        server = MockWebServer()
        server.start()
        session = RecordingAuthSessionStore()
        settings = ServerSettingsStore(context, session)
        settings.setBaseUrl("${serverOrigin()}/api/v1/")
        session.save(ACCESS, REFRESH, Long.MAX_VALUE, serverOrigin())
        val refresh = RecordingRefreshOperation { _, _ ->
            AuthRefreshResult.Success(AuthTokens("refreshed", "refreshed-refresh", Long.MAX_VALUE))
        }
        val parentClient = OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(session, AuthRefreshCoordinator(session, refresh.operation, nowMillis = { 0L }))
            )
            .build()
        proxy = NativeEmbedRequestProxy(parentClient, session, settings)
        client = createPimWebViewClient(
            trustedOrigin = serverOrigin(),
            openExternal = { null },
            onLoading = { },
            onContent = { },
            onError = { },
            onHistoryUrlChanged = null,
            embedProxy = proxy
        )
        webView = WebView(context)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `T1 allowed embedded GET is answered by the native proxy`() {
        server.enqueue(json200(BODY_JSON))
        client.onPageStarted(webView, "${serverOrigin()}$TODAY_PATH", null)

        val response = client.shouldInterceptRequest(webView, request(overviewUrl()))

        assertNotNull("期待有 WebResourceResponse，实际 $response", response)
        assertEquals(200, response!!.statusCode)
        assertEquals("application/json", response.mimeType)
        assertEquals("UTF-8", response.encoding)
        assertEquals(BODY_JSON, response.data.readBytes().decodeToString())
        assertEquals(1, server.requestCount)
        assertEquals("Bearer $ACCESS", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `T2 main frame navigation is not proxied`() {
        server.enqueue(json200(BODY_JSON))
        client.onPageStarted(webView, "${serverOrigin()}$TODAY_PATH", null)

        val response = client.shouldInterceptRequest(webView, request(overviewUrl(), isForMainFrame = true))

        assertNull("主框架不得代发", response)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `T3 non allowed operation is not proxied`() {
        server.enqueue(json200(BODY_JSON))
        client.onPageStarted(webView, "${serverOrigin()}$TODAY_PATH", null)

        val response = client.shouldInterceptRequest(
            webView,
            request("${serverOrigin()}/api/v1/auth/login")
        )

        assertNull("允许表以外的路径不得代发", response)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `T4 upstream 3xx is handed to the page as an empty 502`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://evil.test/steal"))
        client.onPageStarted(webView, "${serverOrigin()}$TODAY_PATH", null)

        val response = client.shouldInterceptRequest(webView, request(overviewUrl()))

        assertNotNull("期待有 WebResourceResponse，实际 $response", response)
        assertEquals(502, response!!.statusCode)
        assertEquals("Bad Gateway", response.reasonPhrase)
        assertEquals("application/json", response.mimeType)
        assertEquals(0, response.data.readBytes().size)
        assertEquals(0, response.responseHeaders?.size ?: 0)
    }

    @Test
    fun `T5 document that is not an embed page is not proxied`() {
        server.enqueue(json200(BODY_JSON))
        client.onPageStarted(webView, "${serverOrigin()}/today", null)

        val response = client.shouldInterceptRequest(webView, request(overviewUrl()))

        assertNull("主文档快照不是 embed 路径时不得代发", response)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `T6 untrusted referer is not proxied`() {
        server.enqueue(json200(BODY_JSON))
        client.onPageStarted(webView, "${serverOrigin()}$TODAY_PATH", null)

        val response = client.shouldInterceptRequest(
            webView,
            request(overviewUrl(), referer = "https://evil.test$TODAY_PATH")
        )

        assertNull("Referer 不受信任时不得代发", response)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `T7 embed navigation still loads in the web view instead of being intercepted`() {
        client.onPageStarted(webView, "${serverOrigin()}$TODAY_PATH", null)

        val overridden = client.shouldOverrideUrlLoading(
            webView,
            request("${serverOrigin()}$TODAY_PATH", isForMainFrame = true)
        )

        assertEquals("主框架导航仍走现有白名单", false, overridden)
        assertEquals("导航本身不得触发代发", 0, server.requestCount)
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun serverOrigin(): String = "http://127.0.0.1:${server.port}"

    private fun overviewUrl(): String = "${serverOrigin()}$OVERVIEW_PATH"

    private fun request(
        url: String,
        method: String = "GET",
        isForMainFrame: Boolean = false,
        referer: String = "${serverOrigin()}$TODAY_PATH"
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = isForMainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = method
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf("referer" to referer)
    }

    private fun json200(body: String): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val TODAY_PATH = "/embed/android/today"
        const val OVERVIEW_PATH = "/api/v1/mobile/location/analytics/overview"
        const val ACCESS = "native-access-1"
        const val REFRESH = "native-refresh-1"
        const val BODY_JSON = """{"code":0,"data":{"ok":true}}"""
    }
}

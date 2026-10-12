package com.pim.app.ui.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthTokens
import com.pim.core.network.AuthInterceptor
import com.pim.core.network.AuthRefreshCoordinator
import com.pim.core.settings.ServerSettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import retrofit2.HttpException
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

/*
 * PW-07a §12.2 代发 RED（唯一规格 remediation/native-embed/reviewed-spec.md §4、§5、§6）。
 *
 * 证据等级：真实 `NativeEmbedRequestProxy.execute` + 真实 `AuthInterceptor` /
 * `AuthRefreshCoordinator` + 真实 `MockWebServer().start()` 随机端口（断言端口不是 5858，
 * 不碰 127.0.0.1:5858、不读凭据、不碰生产）。
 *
 * 每条 P 用例的第一条业务断言都是「代发链路确实走到了上游/刷新」：RED-0 空接缝下
 * `execute` 恒空 502、不访问网络，所以失败文本是「上游应收到 1 次请求，实际 0」。
 * 旧问题是「这 3 条 GET 根本没有原生代发」；空接缝只是让 RED 可执行，不是旧问题的证据。
 * 实施阶段必须让这些断言全部转绿。
 *
 * C- 前缀的用例是正 control：现在和实现后都应绿（锁住测试设施、派生客户端契约与上游可达）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeEmbedRequestProxyTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var now: Long = NOW

    private lateinit var session: RecordingAuthSessionStore
    private lateinit var refresh: RecordingRefreshOperation
    private lateinit var settings: ServerSettingsStore
    private lateinit var parentClient: OkHttpClient
    private lateinit var proxy: NativeEmbedRequestProxy

    @Before
    fun setUp() {
        ShadowLog.clear()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pim_server_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        server = MockWebServer()
        server.start()
        now = NOW
        session = RecordingAuthSessionStore()
        refresh = RecordingRefreshOperation { _, _ ->
            AuthRefreshResult.Success(AuthTokens(REFRESHED_ACCESS, REFRESHED_REFRESH, FAR_FUTURE))
        }
        settings = ServerSettingsStore(context, session)
        settings.setBaseUrl(baseUrl())
        session.save(ACCESS, REFRESH, FAR_FUTURE, serverOrigin())
        parentClient = OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(session, AuthRefreshCoordinator(session, refresh.operation, nowMillis = { now }))
            )
            .build()
        proxy = NativeEmbedRequestProxy(parentClient, session, settings)
        proxy.onMainDocumentStarted("${serverOrigin()}$TODAY_PATH")
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── 正 control（现在也应绿） ────────────────────────────────

    @Test
    fun `C-P0a upstream mock uses a random loopback port that is not the local api port`() {
        assertTrue("MockWebServer 必须监听随机端口，实际 ${server.port}", server.port in 1024..65535)
        assertFalse("不得使用 127.0.0.1:5858", server.port == 5858)
        assertTrue("上游必须是 loopback", serverOrigin().startsWith("http://127.0.0.1:"))
        assertTrue(serverOrigin().endsWith(":${server.port}"))
    }

    @Test
    fun `C-P0b harness reaches the mock upstream and decodes gzip through the real client chain`() {
        server.enqueue(gzipResponse("""{"code":0,"data":{"ok":true}}"""))

        val response = parentClient.newCall(Request.Builder().url(overviewUrl()).build()).execute()

        assertEquals(200, response.code)
        assertEquals("""{"code":0,"data":{"ok":true}}""", response.body!!.string())
        assertNull("OkHttp 透明解压后不得再暴露 Content-Encoding", response.header("Content-Encoding"))
        assertEquals("Bearer $ACCESS", recorded().getHeader("Authorization"))
    }

    @Test
    fun `C-P21 derived client keeps the parent dispatcher and interceptor chain by reference`() {
        val derived = proxy.derivedClient

        assertSame("派生客户端必须复用父客户端 Dispatcher", parentClient.dispatcher, derived.dispatcher)
        /*
         * OkHttp 4.12 的 `newBuilder()` 会用 `+=` 把父客户端的拦截器**元素**拷进一个新 List，
         * 所以断言的“同一引用”落在 Dispatcher 与每个拦截器实例上（顺序也必须一致）。
         */
        assertEquals(parentClient.interceptors.size, derived.interceptors.size)
        assertTrue(parentClient.interceptors.isNotEmpty())
        parentClient.interceptors.forEachIndexed { index, interceptor ->
            assertSame("拦截器 #$index 必须是同一引用", interceptor, derived.interceptors[index])
        }
        assertEquals(parentClient.networkInterceptors.size, derived.networkInterceptors.size)
        assertFalse("规格 §4：不跟随重定向", derived.followRedirects)
        assertFalse("规格 §4：不跟随 SSL 重定向", derived.followSslRedirects)
        assertEquals(30_000, derived.callTimeoutMillis)
        assertEquals(30L, NativeEmbedRequestProxy.CALL_TIMEOUT_SECONDS)
    }

    @Test
    fun `C-P21b OkHttp newBuilder rebuilds the interceptor List so the spec literal list identity is unreachable`() {
        val derived = proxy.derivedClient

        assertEquals("拦截器实例与顺序必须一致", parentClient.interceptors, derived.interceptors)
        assertSame("Dispatcher 必须仍是同一引用", parentClient.dispatcher, derived.dispatcher)
        assertNotSame(
            "规格 §4 的「拦截器链同一引用」对 List 不可达：OkHttp 4.12 的 newBuilder() 会重建 List " +
                "（独立 review 探针 PROBE-OKHTTP interceptorListSameRef=false）。这是记录在案的偏差，不是产品缺陷；" +
                "契约落在 Dispatcher 同一引用 + 每个拦截器实例同一引用 + 顺序一致。",
            parentClient.interceptors,
            derived.interceptors
        )
    }

    // ── P1 / P2：页面令牌不得进上游 ─────────────────────────────

    @Test
    fun `P1 page Authorization canary never reaches the upstream and the request is natively proxied`() {
        server.enqueue(json200("""{"code":0,"data":{}}"""))

        val response = execute()

        assertUpstreamCount()
        val recorded = recorded()
        assertEquals(200, response.statusCode)
        assertFalse(
            "页面带来的 Authorization 不得出现在上游，实际 ${recorded.getHeader("Authorization")}",
            recorded.getHeader("Authorization").orEmpty().contains(PAGE_TOKEN)
        )
        assertEquals("Bearer $ACCESS", recorded.getHeader("Authorization"))
    }

    @Test
    fun `P2 no page header is copied upstream and only GET is sent`() {
        server.enqueue(json200("""{"code":0,"data":{}}"""))

        execute()

        assertUpstreamCount()
        val recorded = recorded()
        assertNull("page-token 不得出现在上游", recorded.getHeader("page-token"))
        assertNull("规格 §4：不复制页面的任何请求头（含 Referer）", recorded.getHeader("Referer"))
        assertNull(recorded.getHeader("origin"))
        assertEquals("GET", recorded.method)
        assertEquals(OVERVIEW_PATH, recorded.path)
    }

    // ── P3 / P4 / P5 / P7：过期、401 重放与刷新 ─────────────────

    @Test
    fun `P3 expired session refreshes once and the upstream sees the refreshed bearer`() {
        session.save(ACCESS, REFRESH, EXPIRED, serverOrigin())
        server.enqueue(json200("""{"code":0,"data":{}}"""))

        val response = execute()

        assertUpstreamCount()
        val recorded = recorded()
        assertEquals("过期令牌必须经既有协调器刷新一次，实际 ${refresh.calls.size} 次", 1, refresh.calls.size)
        assertEquals("Bearer $REFRESHED_ACCESS", recorded.getHeader("Authorization"))
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `P4 401 replays once with the refreshed token`() {
        server.enqueue(json401())
        server.enqueue(json200("""{"code":0,"data":{}}"""))

        val response = execute()

        assertUpstreamCount(2)
        val first = recorded()
        val second = recorded()
        assertEquals("Bearer $ACCESS", first.getHeader("Authorization"))
        assertEquals("Bearer $REFRESHED_ACCESS", second.getHeader("Authorization"))
        assertEquals(1, refresh.calls.size)
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `P4b 401 without any session returns 401 with an empty body and zero refresh operations`() {
        session.clear()
        server.enqueue(json401())

        val response = execute()

        assertUpstreamCount()
        val recorded = recorded()
        assertNull("无会话时上游不得带 Authorization", recorded.getHeader("Authorization"))
        assertEquals("无令牌时刷新操作次数必须为 0", 0, refresh.calls.size)
        assertEquals(401, response.statusCode)
        assertEquals(0, readAll(response.body).size)
        assertNull("逻辑会话快照必须保持为空", session.snapshot().tokens)
    }

    @Test
    fun `P5 rejected refresh returns 401 with an empty body and clears the session`() {
        refresh.behavior = { _, _ -> AuthRefreshResult.Rejected }
        server.enqueue(json401())

        val response = execute()

        assertUpstreamCount()
        assertEquals("刷新被拒路径必须真的走过刷新操作", 1, refresh.calls.size)
        assertEquals(401, response.statusCode)
        assertEquals(0, readAll(response.body).size)
        assertNull("刷新被拒后会话按既有协调器清除", session.snapshot().tokens)
    }

    @Test
    fun `P7 expired session with rejected refresh sends no token upstream and returns 401 empty`() {
        session.save(ACCESS, REFRESH, EXPIRED, serverOrigin())
        refresh.behavior = { _, _ -> AuthRefreshResult.Rejected }
        server.enqueue(json401())

        val response = execute()

        assertUpstreamCount()
        val recorded = recorded()
        assertNull("刷新失败后不得把旧令牌发给上游", recorded.getHeader("Authorization"))
        assertEquals(1, refresh.calls.size)
        assertEquals(401, response.statusCode)
        assertEquals(0, readAll(response.body).size)
    }

    // ── P6：3xx 不跟随 ─────────────────────────────────────────

    @Test
    fun `P6 302 is never followed and no Location reaches the page`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", "https://evil.test/steal")
                .addHeader("Set-Cookie", "sid=1")
        )

        val response = execute()

        assertUpstreamCount()
        assertTrue("3xx 不得交给页面，实际 ${response.statusCode}", response.statusCode !in 300..399)
        assertEquals("302 必须变成空 502", 502, response.statusCode)
        assertNull("不得交出 Location", response.headerOf("location"))
        assertNull("不得交出 Set-Cookie", response.headerOf("set-cookie"))
        assertEquals(0, readAll(response.body).size)
    }

    // ── P8 / P11：压缩 ────────────────────────────────────────

    @Test
    fun `P8 gzip body is handed over decompressed without Content-Encoding`() {
        server.enqueue(gzipResponse(BODY_JSON))

        val response = execute()

        assertUpstreamCount()
        assertEquals(200, response.statusCode)
        assertNull("解压后的响应不得再带 Content-Encoding", response.headerOf("content-encoding"))
        assertEquals(BODY_JSON, String(readAll(response.body), Charsets.UTF_8))
        assertEquals("application/json", response.mimeType)
    }

    @Test
    fun `P11 residual unsupported Content-Encoding becomes an empty 502`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Content-Encoding", "br")
                .setBody(Buffer().write(byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x01, 0x02)))
        )

        val response = execute()

        assertUpstreamCount()
        assertEquals("仍能读到 Content-Encoding 时整次空 502", 502, response.statusCode)
        assertEquals(0, readAll(response.body).size)
    }

    // ── P9：响应头剥离 ────────────────────────────────────────

    @Test
    fun `P9 case-insensitive denylist headers are dropped and allowlisted headers survive`() {
        server.enqueue(
            json200(BODY_JSON)
                .addHeader("Set-Cookie", "sid=1")
                .addHeader("Set-Cookie2", "sid2=2")
                .addHeader("Access-Control-Allow-Origin", "*")
                .addHeader("Access-Control-Expose-Headers", "x")
                .addHeader("Access-Control-Allow-Credentials", "true")
                .addHeader("access-control-allow-methods", "GET,POST")
                .addHeader("Access-Control-Allow-Headers", "authorization")
                .addHeader("Access-Control-Max-Age", "600")
                .addHeader("Access-Control-Request-Method", "GET")
                .addHeader("Access-Control-Request-Headers", "x-page-token")
                .addHeader("Authorization", "Bearer upstream-secret")
                .addHeader("WWW-Authenticate", "Basic realm=x")
                .addHeader("Proxy-Authenticate", "Basic")
                .addHeader("Proxy-Authorization", "Basic")
                .addHeader("Trailer", "x")
                .addHeader("Upgrade", "h2c")
                .addHeader("Keep-Alive", "timeout=5")
                .addHeader("Proxy-Connection", "keep-alive")
                .addHeader("TE", "trailers")
                .addHeader("Retry-After", "30")
                .addHeader("Cache-Control", "max-age=5")
                .addHeader("ETag", "\"v1\"")
                .addHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .addHeader("Vary", "Accept")
        )

        val response = execute()

        assertUpstreamCount()
        assertEquals(200, response.statusCode)
        for (denied in DENIED_HEADERS) {
            assertNull("$denied 必须被丢弃", response.headerOf(denied))
        }
        assertNotNull(response.headerOf("content-type"))
        assertNotNull(response.headerOf("retry-after"))
        assertNotNull(response.headerOf("cache-control"))
        assertNotNull(response.headerOf("etag"))
        assertNotNull(response.headerOf("last-modified"))
        assertNotNull(response.headerOf("vary"))
        assertFalse(
            "同名多值合成时不得把丢弃的 cookie 拼回来",
            response.headers.values.flatten().any { it.contains("sid=1") || it.contains("sid2=2") }
        )
        assertEquals(BODY_JSON, String(readAll(response.body), Charsets.UTF_8))
    }

    // ── P12：头值含 canary ────────────────────────────────────

    @Test
    fun `P12 response header value containing the session token becomes an empty 502 without logging it`() {
        server.enqueue(json200(BODY_JSON).addHeader("ETag", "\"$ACCESS\""))

        val response = execute()

        assertUpstreamCount()
        assertEquals(502, response.statusCode)
        assertEquals(0, readAll(response.body).size)
        assertFalse(response.headerOf("etag").orEmpty().contains(ACCESS))
        assertNoCanaryInLogs(ACCESS, "P12")
        assertNoCanaryInLogs(REFRESH, "P12")
    }

    // ── P13 / P14：正文扫描与 64KiB 失败形态 ────────────────────

    @Test
    fun `P13 token inside the first 64KiB becomes an empty 502 with zero bytes handed out`() {
        val body = filler(1000, 'a') + ACCESS.toByteArray() + filler(1000, 'b')
        server.enqueue(json200Bytes(body))

        val response = execute()

        assertUpstreamCount()
        assertEquals(502, response.statusCode)
        assertEquals("尚未交出任何正文时命中针：调用方必须 0 字节", 0, readAll(response.body).size)
    }

    @Test
    fun `P14 token starting at offset 65530 and crossing the 64KiB boundary is never handed out`() {
        assertEquals("P14 的针必须是 16 字节", 16, CANARY16.length)
        /*
         * 规格 §5.2：针集只来自会话里的 access/refresh 令牌字节。原用例把 CANARY16 当作针，
         * 但它并不在会话里 —— 于是「交出的字节不含 CANARY16」对任何合规实现都不可能成立
         * （正文 65746 字节 > 65530），期望无法转绿。这里把长度 16 的 canary 放进会话，
         * 让「跨 64KiB 的 16 字节针」真的是一枚针；其余断言与期望一字未改。
         */
        session.save(CANARY16, REFRESH, FAR_FUTURE, serverOrigin())
        val body = filler(65530, 'a') + CANARY16.toByteArray() + filler(200, 'b')
        server.enqueue(json200Bytes(body))

        val response = execute()

        assertUpstreamCount()
        /*
         * 必须用累计式读取：流在交出前缀后才抛 `IOException` 是被允许的失败形态
         * （规格 §4.1）。若用 `runCatching { readAll }.getOrNull() ?: ByteArray(0)`，
         * 已交出的字节会被抹成空数组，被禁的「先交 64KiB 再扫剩余」两段式就能空洞通过。
         */
        val (received, failure) = readAllKeepingBytesOnFailure(response.body)
        if (response.statusCode == 502) {
            assertEquals(0, received.size)
        } else {
            assertFalse(
                "调用方收到的字节不得含跨 64KiB 边界的针",
                containsBytes(received, CANARY16.toByteArray())
            )
            assertTrue(
                "不得把前 65536 字节原样交出（失败=${failure?.javaClass?.simpleName}），" +
                    "实际交出 ${received.size} 字节",
                received.size <= 65530
            )
        }
    }

    // ── P15：无针正文逐字节相等 + 不得整段缓冲 ──────────────────

    @Test
    fun `P15 body without any needle is byte identical`() {
        val body = """{"code":0,"data":{"points":[[1,2],[3,4]],"note":"plain"}}"""
        server.enqueue(json200(body))

        val response = execute()

        assertUpstreamCount()
        assertEquals(body, String(readAll(response.body, readSize = 1), Charsets.UTF_8))
    }

    @Test
    fun `P15b large throttled body hands out correct bytes and close is safe`() {
        val big = filler(100_000, 'a')
        server.enqueue(json200Bytes(big).throttleBody(10_000, 1, TimeUnit.SECONDS))

        val response = execute()

        assertUpstreamCount()
        /*
         * 规格 §12.2 对 P15 只要求「正文逐字节相等，保留字节 ≤ 最长针 − 1」；§5.3 明写
         * 「不要求把 tracks 整段放进内存……临时文件可以做实现优化，但不是本规格的必须路径」。
         * 因此这里**不用** 5 秒墙钟把「必须流式」变成规格外的隐含要求：只检查交出的字节正确、
         * 流能读到 EOF、以及 close() 安全。自然读取耗时不是错误，也不引入 tracks 体积上限。
         * 原安全要求（针字节不得被交出）保留。
         *
         * 独立 review 的实施阶段加严：把节流正文读到 EOF 并逐字节比较（不加回墙钟/体积上限）。
         */
        val out = ByteArrayOutputStream()
        val buf = ByteArray(512)
        repeat(2) { round ->
            val n = response.body.read(buf)
            assertTrue("大正文必须交出正数字节（第 ${round + 1} 次 read 实际 $n）", n > 0)
            out.write(buf, 0, n)
        }
        val handedOut = out.toByteArray()
        assertArrayEquals(
            "无针大正文交出的字节必须与原正文逐字节相同",
            filler(handedOut.size, 'a'),
            handedOut
        )
        assertFalse(
            "交出的字节不得含任何会话针",
            containsBytes(handedOut, ACCESS.toByteArray()) ||
                containsBytes(handedOut, REFRESH.toByteArray())
        )

        // 读到 EOF：整段正文逐字节相等，且一次都不含针。
        val (rest, failure) = readAllKeepingBytesOnFailure(response.body, readSize = 4096)
        assertNull("读到大正文 EOF 不得失败，实际 $failure", failure)
        val whole = ByteArrayOutputStream()
        whole.write(handedOut)
        whole.write(rest)
        assertArrayEquals(
            "节流大正文整段必须逐字节等于原文（实际 ${whole.size()} / ${big.size} 字节）",
            big,
            whole.toByteArray()
        )
        assertFalse(
            "整段交出的字节不得含任何会话针",
            containsBytes(whole.toByteArray(), ACCESS.toByteArray()) ||
                containsBytes(whole.toByteArray(), REFRESH.toByteArray())
        )

        response.body.close()
        assertArrayEquals(
            "close() 之后已交出的字节不得被改写",
            filler(handedOut.size, 'a'),
            handedOut
        )
    }

    // ── P16 / P17：无会话与刷新异常 ────────────────────────────

    @Test
    fun `P16 no session sends no Authorization upstream and calls no refresh operation`() {
        session.clear()
        server.enqueue(json200(BODY_JSON))

        val response = execute()

        assertUpstreamCount()
        val recorded = recorded()
        assertNull(recorded.getHeader("Authorization"))
        assertEquals(0, refresh.calls.size)
        assertNull(session.snapshot().tokens)
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `P17 HttpException 503 from refresh becomes 502 without touching the session or upstream`() {
        session.save(ACCESS, REFRESH, EXPIRED, serverOrigin())
        refresh.behavior = { _, _ ->
            throw HttpException(Response.error<Any>(503, "".toResponseBody(null)))
        }

        val response = execute()

        assertEquals("刷新异常路径必须真的走到刷新操作", 1, refresh.calls.size)
        assertEquals("HttpException 必须被收成空 502", 502, response.statusCode)
        assertEquals(0, readAll(response.body).size)
        assertEquals("不得清会话", ACCESS, session.snapshot().tokens?.accessToken)
        assertEquals("上游不得收到请求", 0, server.requestCount)
    }

    // ── P18：429 ──────────────────────────────────────────────

    @Test
    fun `P18 429 keeps the status and Retry-After and is not retried on Android`() {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .addHeader("Retry-After", "900")
                .addHeader("Content-Type", "application/json")
                .setBody(BODY_JSON)
        )

        val response = execute()

        assertUpstreamCount()
        assertEquals(429, response.statusCode)
        assertEquals("900", response.headerOf("retry-after"))
        assertEquals("Android 不得替页面重试", 1, server.requestCount)
    }

    // ── P19 / P20：取消 ───────────────────────────────────────

    @Test
    fun `P19 cancel stops only this proxy in-flight call while another WebView call is in flight`() {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS).setBody(BODY_JSON)
            )
            // 另一个 WebView 的 call 也必须**在飞**（响应头晚 2 秒到 = `execute()` 还没返回），
            // 否则它无法区分「Call.cancel() 只取消自己」与规格 §4 禁止的 `dispatcher.cancelAll()`：
            // 只延迟正文的话 `execute()` 早已返回 200，cancelAll 抓不到。
            other.enqueue(
                json200("""{"code":0,"data":{"b":true}}""").setHeadersDelay(2, TimeUnit.SECONDS)
            )
            val otherProxy = NativeEmbedRequestProxy(parentClient, session, settings)
            otherProxy.onMainDocumentStarted("http://127.0.0.1:${other.port}$TRACKS_PATH")

            val outcome = AtomicReference<Result<EmbedProxyResponse>?>(null)
            val otherOutcome = AtomicReference<Result<EmbedProxyResponse>?>(null)
            val otherUrl = "http://127.0.0.1:${other.port}$TRACKS_PATH"
            val worker = Thread { outcome.set(runCatching { proxy.execute(proxyUrl(overviewUrl()), emptyMap()) }) }
            val otherWorker = Thread {
                otherOutcome.set(runCatching { otherProxy.execute(proxyUrl(otherUrl), emptyMap()) })
            }
            worker.start()
            awaitUpstreamRequest(server, 1)
            otherWorker.start()
            // 两个 call 都在跑，且共用父客户端（同一个 Dispatcher）：这才是 cancelAll 会误伤的局面
            awaitUpstreamRequest(other, 1)

            proxy.cancel()
            worker.join(5_000)
            otherWorker.join(5_000)

            assertNotNull("cancel 之后 execute 必须返回，不得挂住", outcome.get())
            assertEquals(502, outcome.get()!!.getOrThrow().statusCode)

            val otherResult = otherOutcome.get()
            assertNotNull("另一个 WebView 的 call 必须在 cancel 之前就已在飞并最终返回", otherResult)
            assertTrue(
                "另一个 WebView 的 call 必须成功（用 dispatcher.cancelAll() 会把它一起掐断）：$otherResult",
                otherResult!!.isSuccess
            )
            val otherResponse = otherResult.getOrThrow()
            assertEquals("取消只针对本代理的 call，另一个 WebView 不受影响", 200, otherResponse.statusCode)
            assertEquals(
                """{"code":0,"data":{"b":true}}""",
                String(readAll(otherResponse.body), Charsets.UTF_8)
            )
        } finally {
            runCatching { other.shutdown() }
        }
    }

    @Test
    fun `P19b reload generation change cancels the in-flight call and blocks the late body`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS).setBody(BODY_JSON)
        )

        val outcome = AtomicReference<Result<EmbedProxyResponse>?>(null)
        val worker = Thread { outcome.set(runCatching { proxy.execute(proxyUrl(overviewUrl()), emptyMap()) }) }
        worker.start()

        awaitUpstreamRequest(server, 1)
        proxy.onDocumentReloaded()
        worker.join(5_000)

        assertNotNull("reload 之后 execute 必须返回，不得挂住", outcome.get())
        val stale = outcome.get()!!.getOrThrow()
        assertEquals("代次已变的响应不得交回正文，必须是空 502", 502, stale.statusCode)
        assertEquals(0, readAll(stale.body).size)

        // 代次推进不得永久破坏该 WebView 的代发
        server.enqueue(json200(BODY_JSON))
        val fresh = execute()
        assertUpstreamCount(2)
        assertEquals(200, fresh.statusCode)
        assertEquals(BODY_JSON, String(readAll(fresh.body), Charsets.UTF_8))
    }

    @Test
    fun `P20 late body after cancel is never handed to the caller`() {
        val body = "late-" + ACCESS + "-tail"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(Buffer().writeUtf8(body))
                .setBodyDelay(3, TimeUnit.SECONDS)
        )

        val response = execute()

        assertUpstreamCount()
        proxy.cancel()
        // 取消/命中后流可能抛 IOException，必须用累计式读取保留已经交出的字节
        val (received, _) = readAllKeepingBytesOnFailure(response.body)
        assertFalse(
            "取消后晚到的正文不得交给调用方",
            containsBytes(received, ACCESS.toByteArray())
        )
    }

    // ── P22：设置变更 ─────────────────────────────────────────

    @Test
    fun `P22 requests after a server switch are skipped for the old origin`() {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(json200(BODY_JSON))

            val first = proxy.proxyRequest(overviewUrl(), "GET", false, pageHeadersFor(serverOrigin()))
            assertUpstreamCount()
            assertNotNull("首次请求必须代发", first)
            assertEquals(200, first!!.statusCode)

            settings.setBaseUrl("http://127.0.0.1:${other.port}/api/v1/")

            val second = proxy.proxyRequest(overviewUrl(), "GET", false, pageHeadersFor(serverOrigin()))
            assertNull("设置变更后旧 origin 的新请求必须 Skip", second)
            assertEquals("不得对旧 origin 再发请求", 1, server.requestCount)

            other.enqueue(json200(BODY_JSON))
            otherProxyRequest(other)
            assertEquals(1, other.requestCount)
        } finally {
            runCatching { other.shutdown() }
        }
    }

    // ── P23 / P24：短针 ───────────────────────────────────────

    @Test
    fun `P23a one byte token is scanned and cannot be skipped`() {
        session.save("z", "y", FAR_FUTURE, serverOrigin())
        server.enqueue(json200("""{"v":"z"}"""))

        val response = execute()

        assertUpstreamCount()
        assertEquals("1 字节针必须命中", 502, response.statusCode)
        assertEquals(0, readAll(response.body).size)
    }

    @Test
    fun `P23b seven byte token is scanned and cannot be skipped`() {
        session.save("1234567", "7654321", FAR_FUTURE, serverOrigin())
        server.enqueue(json200("""{"v":"1234567"}"""))

        val response = execute()

        assertUpstreamCount()
        assertEquals("7 字节针必须命中", 502, response.statusCode)
        assertEquals(0, readAll(response.body).size)
    }

    @Test
    fun `P24 short needle inside the long needle hold window is still detected`() {
        session.save("abc", "abcdefghijklmnop", FAR_FUTURE, serverOrigin())
        server.enqueue(json200("""{"v":"XYZabcQQQQQ"}"""))

        val response = execute()

        assertUpstreamCount()
        assertEquals("短针落在长针保留窗内也必须命中", 502, response.statusCode)
        assertEquals(0, readAll(response.body).size)
    }

    // ── P25 / P26：重入与动态针集 ──────────────────────────────

    @Test
    fun `P25 reentrant read throws and never copies needle bytes into the caller buffer`() {
        val holder = AtomicReference<InputStream?>(null)
        val innerBuffer = ByteArray(64)
        val innerOutcome = AtomicReference<Result<Int>?>(null)

        val probe = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val real = response.body ?: return@Interceptor response
            response.newBuilder().body(ReentrantProbeBody(real, holder, innerBuffer, innerOutcome)).build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(probe)
            .addInterceptor(
                AuthInterceptor(session, AuthRefreshCoordinator(session, refresh.operation, nowMillis = { now }))
            )
            .build()
        val probingProxy = NativeEmbedRequestProxy(client, session, settings)

        val body = filler(70_000, 'a') + ACCESS.toByteArray() + "tail".toByteArray()
        server.enqueue(json200Bytes(body))

        val response = probingProxy.execute(proxyUrl(overviewUrl()), emptyMap())

        assertUpstreamCount()
        holder.set(response.body)
        val received = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val outcome = runCatching {
            while (true) {
                val n = response.body.read(buffer)
                if (n < 0) break
                if (n > 0) received.write(buffer, 0, n)
                if (received.size() > body.size) break
            }
        }
        val all = received.toByteArray()

        assertFalse("重入/扫描失败后不得把针交给调用方", containsBytes(all, ACCESS.toByteArray()))
        val inner = innerOutcome.get()
        // 规格 §5.3 不要求把正文整段放进内存，临时文件也不是必须路径。
        // 探针在 execute() 返回之后才装上。execute() 内已经消费上游时，探针不会重入。
        // 探针没走到不是产品失败，也不得因此增加流式或性能架构要求。
        // 重入一旦发生：必须抛异常，且不得把针写进重入缓冲。调用方缓冲始终不得含针（上一行）。
        if (inner != null) {
            assertNotNull("read 不可重入：重入必须抛异常，实际 $inner", inner.exceptionOrNull())
            assertFalse("重入时不得把针写进外层缓冲", containsBytes(innerBuffer, ACCESS.toByteArray()))
        }
        assertTrue(
            "命中后不得把整段正文当正常结束交出，实际 ${all.size} 字节",
            outcome.isFailure || all.size < body.size
        )
    }

    @Test
    fun `P26 token written after execute returned is scanned before any byte is copied out`() {
        val newToken = NEW_TOKEN
        val body = filler(100, 'a') + "\";\"$newToken\";\"".toByteArray() + filler(200, 'b')
        server.enqueue(json200Bytes(body))

        val response = execute()

        assertUpstreamCount()
        session.save(newToken, REFRESH, FAR_FUTURE, serverOrigin())
        // 命中新针后流会抛 IOException，必须用累计式读取保留已经交出的字节
        val (received, _) = readAllKeepingBytesOnFailure(response.body)

        assertFalse(
            "execute 返回后写入会话的新令牌必须在每次拷贝前并入针集",
            containsBytes(received, newToken.toByteArray())
        )
        assertTrue(
            "命中新针后不得把整段正文当成功交出，实际 ${received.size} / ${body.size} 字节",
            received.size < body.size
        )
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun serverOrigin(): String = "http://127.0.0.1:${server.port}"

    private fun baseUrl(): String = "${serverOrigin()}/api/v1/"

    private fun overviewUrl(): String = "${serverOrigin()}$OVERVIEW_PATH"

    private fun proxyUrl(url: String) = EmbedRequestDecision.Proxy(url.toHttpUrl())

    private fun pageHeadersFor(origin: String): Map<String, List<String>> = mapOf(
        "authorization" to listOf("Bearer $PAGE_TOKEN"),
        "page-token" to listOf(PAGE_TOKEN),
        "referer" to listOf("$origin$TODAY_PATH")
    )

    private fun execute(
        url: String = overviewUrl(),
        headers: Map<String, List<String>> = pageHeadersFor(serverOrigin())
    ): EmbedProxyResponse = proxy.execute(proxyUrl(url), headers)

    private fun otherProxyRequest(other: MockWebServer) {
        val origin = "http://127.0.0.1:${other.port}"
        val response = proxy.proxyRequest("$origin$OVERVIEW_PATH", "GET", false, pageHeadersFor(origin))
        assertNotNull("新 origin 的允许请求仍要代发", response)
        assertEquals(200, response!!.statusCode)
    }

    private fun assertUpstreamCount(expected: Int = 1) {
        assertEquals(
            "代发未接线：上游应收到 $expected 次请求，实际 ${server.requestCount}",
            expected,
            server.requestCount
        )
    }

    private fun recorded(): RecordedRequest =
        server.takeRequest(2, TimeUnit.SECONDS) ?: error("上游请求未被记录")

    private fun awaitUpstreamRequest(target: MockWebServer, count: Int) {
        val deadline = System.currentTimeMillis() + 4_000
        while (target.requestCount < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(
            "代发未接线：4 秒内上游没有收到 $count 次请求，实际 ${target.requestCount}",
            count,
            target.requestCount
        )
    }

    private fun json200(body: String): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body)

    private fun json200Bytes(body: ByteArray): MockResponse =
        MockResponse().setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody(Buffer().write(body))

    private fun json401(): MockResponse =
        MockResponse().setResponseCode(401)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"code":401}""")

    private fun gzipResponse(body: String): MockResponse {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return MockResponse().setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .addHeader("Content-Encoding", "gzip")
            .setBody(Buffer().write(out.toByteArray()))
    }

    private fun filler(size: Int, ch: Char): ByteArray = ByteArray(size) { ch.code.toByte() }

    private fun EmbedProxyResponse.headerOf(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** 探针：第一次真正从上游取字节时重入 `read`（规格 §5.3）。 */
    private class ReentrantProbeBody(
        private val delegate: ResponseBody,
        private val holder: AtomicReference<InputStream?>,
        private val innerBuffer: ByteArray,
        private val innerOutcome: AtomicReference<Result<Int>?>
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource {
            val upstream = delegate.source()
            return object : ForwardingSource(upstream) {
                private var triggered = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val scanner = holder.get()
                    if (!triggered && scanner != null) {
                        triggered = true
                        innerOutcome.set(runCatching { scanner.read(innerBuffer) })
                    }
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val FAR_FUTURE = NOW + 3_600_000L
        const val EXPIRED = NOW - 1L
        const val TODAY_PATH = "/embed/android/today"
        const val OVERVIEW_PATH = "/api/v1/mobile/location/analytics/overview"
        const val TRACKS_PATH = "/api/v1/mobile/location/analytics/tracks"

        const val PAGE_TOKEN = "canary-access-1"
        const val ACCESS = "native-access-1"
        const val REFRESH = "native-refresh-1"
        const val REFRESHED_ACCESS = "native-access-2"
        const val REFRESHED_REFRESH = "native-refresh-2"
        const val NEW_TOKEN = "native-access-3-rotated"
        const val CANARY16 = "canary-16-bytes!"
        const val BODY_JSON = """{"code":0,"data":{"ok":true}}"""

        val DENIED_HEADERS = listOf(
            "set-cookie",
            "set-cookie2",
            // 规格 §5.1：任何以 `access-control-` 开头的名字都要丢，不是只丢 allow-origin/expose-headers
            "access-control-allow-origin",
            "access-control-expose-headers",
            "access-control-allow-credentials",
            "Access-Control-Allow-Methods",
            "Access-Control-Allow-Headers",
            "Access-Control-Max-Age",
            "Access-Control-Request-Method",
            "access-control-request-headers",
            "authorization",
            "www-authenticate",
            "proxy-authenticate",
            "proxy-authorization",
            "content-length",
            "content-encoding",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "trailer",
            "upgrade",
            "te"
        )
    }
}

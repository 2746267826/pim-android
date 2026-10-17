package com.pim.app.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import com.pim.core.auth.SecurePreferencesFactory
import com.pim.core.auth.TokenManager
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.settings.ServerSettingsStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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

/**
 * 工单 06 / G2：Android 端点心跳上报。
 *
 * 端点页（`GET /api/v1/endpoints`）此前恒为空，是因为**没有任何客户端在生产路径上调过**
 * `POST /api/v1/endpoints/{deviceId}/heartbeat`。这里断言的是 Android 侧真的会调、调的是契约里那个
 * 路径、请求体只有契约那四个字段，且：
 *  * `deviceId` 与设备注册用**同一个**标识（AC-4）；
 *  * 未登录、未到间隔、失败三种情况各有明确出口，**不把"没上报"伪装成"上报过"**（G2.3）。
 *
 * 走真实 HTTP：Retrofit + OkHttp → [MockWebServer]（不是桩 handler），因此路径、序列化、
 * 状态码分支都是真的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class EndpointHeartbeatReporterTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 取值口径 -------------------------------------------------------------

    @Test
    fun uploadStatusUsesTheServersVocabulary() {
        assertEquals("Healthy", EndpointHeartbeatReporter.resolveUploadStatus(null, true))
        assertEquals("Healthy", EndpointHeartbeatReporter.resolveUploadStatus("  ", true))
        assertEquals("Unknown", EndpointHeartbeatReporter.resolveUploadStatus(null, false))
        assertEquals("Warning", EndpointHeartbeatReporter.resolveUploadStatus("HTTP 500", true))
        assertEquals("Warning", EndpointHeartbeatReporter.resolveUploadStatus("HTTP 500", false))
    }

    /// <summary>
    /// 取消必须照常抛出，且**不能**被记成一次成功/失败（服务停止时取消是正常路径，
    /// 记账里混进一条假失败会让"连续失败"失去意义）。
    /// </summary>
    @Test
    fun cancellationPropagatesWithoutRecordingAnOutcome() = runTest {
        val reporter = reporter(api = cancelingApi())

        var cancelled = false
        try {
            reporter.reportIfDue(1)
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }

        assertTrue("取消必须向外抛", cancelled)
        assertNull(reporter.status.value.lastSuccessAtMillis)
        assertNull(reporter.status.value.lastError)
        assertEquals(0, reporter.status.value.consecutiveFailures)
    }

    /**
     * **生产入口**（非挂起的 `requestIfDue()`）必须真的发出去，而且用的是**现读**的队列条数。
     *
     * review 指出：只测挂起的 `reportIfDue` 时，"删掉异步入口"或"把积压条数写死"都不会被发现
     * —— 而那正是本轮两个 Critical 修复的落点。
     */
    @Test
    fun productionEntrySendsWithTheFreshlyReadPendingCount() = runTest {
        server.enqueue(jsonResponse(OK_ENVELOPE))
        val reporter = reporter(pendingLocationPoints = 4)

        reporter.requestIfDue()

        val request = server.takeRequest(15, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull("生产入口必须真的发出请求", request)
        val body = Json.parseToJsonElement(request!!.body.readUtf8()).jsonObject
        assertEquals("4", body.getValue("collectionCacheCount").toString())

        // reporter 自持作用域（真实 IO 线程）：轮询等它把状态写回来，不用虚拟时间。
        val deadline = System.currentTimeMillis() + 15_000
        while (reporter.status.value.lastSuccessAtMillis == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertNotNull("成功状态必须被记录（否则状态页永远显示尚未上报）", reporter.status.value.lastSuccessAtMillis)
        assertEquals("Healthy", reporter.status.value.lastReportedUploadStatus)
    }

    /**
     * 观测读取失败不能抛到根协程之外：它必须变成一条**可见的失败**（否则 Android 上可能直接终止进程，
     * 而状态里什么都不留）。
     */
    @Test
    fun observationFailureIsRecordedAsVisibleFailure() = runTest {
        val reporter = reporter(uploadOutcomeSource = UploadOutcomeSource { throw IllegalStateException("observation unavailable") })

        assertEquals(EndpointHeartbeatOutcome.FAILED, reporter.reportIfDue(1))

        assertEquals(1, reporter.status.value.consecutiveFailures)
        assertNotNull(reporter.status.value.lastError)
        assertEquals(0, server.requestCount)
    }

    // ---- 真实 HTTP ------------------------------------------------------------

    @Test
    fun reportsTheFourContractFieldsToTheContractPath() = runTest {
        server.enqueue(jsonResponse(OK_ENVELOPE))
        val identity = DeviceIdentityProvider(context)
        val reporter = reporter(now = { CLOCK })

        val outcome = reporter.reportIfDue(collectionCacheCount = 3)

        assertEquals(EndpointHeartbeatOutcome.SENT, outcome)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/endpoints/${identity.current().deviceId}/heartbeat", request.path)

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            setOf("platform", "appVersion", "uploadStatus", "collectionCacheCount"),
            body.keys
        )
        assertEquals("android", body.getValue("platform").toString().trim('"'))
        assertEquals("Healthy", body.getValue("uploadStatus").toString().trim('"'))
        assertEquals("3", body.getValue("collectionCacheCount").toString())
        // 客户端**不发送**任何时间字段：last_heartbeat_at 只能由服务端按收到时刻写。
        assertFalse(body.keys.any { it.contains("time", ignoreCase = true) })
    }

    /**
     * AC-4：端点心跳用的 deviceId 与设备注册/上传链路同源（`android-<hash>`）。
     * 这里把"同一台机器不会显示成两台设备"落成可执行的断言。
     */
    @Test
    fun deviceIdComesFromTheSharedIdentityProvider() = runTest {
        server.enqueue(jsonResponse(OK_ENVELOPE))
        val identity = DeviceIdentityProvider(context).current()

        assertTrue(identity.deviceId.startsWith("android-"))
        assertEquals("android-${identity.deviceId.removePrefix("android-")}", identity.deviceId)

        reporter(now = { CLOCK }).reportIfDue(null)

        assertEquals(
            "/api/v1/endpoints/${identity.deviceId}/heartbeat",
            server.takeRequest().path
        )
    }

    @Test
    fun uploadStatusOnTheWireFollowsTheUploadOutcome() = runTest {
        server.enqueue(jsonResponse(OK_ENVELOPE))
        reporter(uploadOutcome = UploadOutcome(lastError = "HTTP 500", hasCompletedUpload = true))
            .reportIfDue(null)
        assertEquals("Warning", bodyUploadStatus(server.takeRequest()))

        server.enqueue(jsonResponse(OK_ENVELOPE))
        reporter(uploadOutcome = UploadOutcome(lastError = null, hasCompletedUpload = false))
            .reportIfDue(null)
        assertEquals("Unknown", bodyUploadStatus(server.takeRequest()))
    }

    // ---- 节流与"不伪装" -------------------------------------------------------

    @Test
    fun repeatedCallsInsideTheIntervalAreSkipped() = runTest {
        server.enqueue(jsonResponse(OK_ENVELOPE))
        var elapsed = MONOTONIC
        val reporter = reporter(elapsed = { elapsed })

        assertEquals(EndpointHeartbeatOutcome.SENT, reporter.reportIfDue(null))
        elapsed += EndpointHeartbeatReporter.MIN_INTERVAL_MILLIS - 1
        assertEquals(EndpointHeartbeatOutcome.SKIPPED_NOT_DUE, reporter.reportIfDue(null))
        assertEquals(1, server.requestCount)

        elapsed += 1
        server.enqueue(jsonResponse(OK_ENVELOPE))
        assertEquals(EndpointHeartbeatOutcome.SENT, reporter.reportIfDue(null))
        assertEquals(2, server.requestCount)
    }

    /**
     * 节流只认**单调**时钟：墙钟被校时前后跳时，既不能提前发（浪费电与流量），
     * 也不能长期不发（让端点页把"还活着"看成"已经死了"）。
     */
    @Test
    fun throttleIgnoresWallClockJumps() = runTest {
        var now = CLOCK
        var elapsed = MONOTONIC
        val reporter = reporter(now = { now }, elapsed = { elapsed })

        server.enqueue(jsonResponse(OK_ENVELOPE))
        assertEquals(EndpointHeartbeatOutcome.SENT, reporter.reportIfDue(null))

        // 墙钟向前跳 6 分钟、单调时钟没走：仍然不发。
        now += 6 * 60 * 1000L
        assertEquals(EndpointHeartbeatOutcome.SKIPPED_NOT_DUE, reporter.reportIfDue(null))
        assertEquals(1, server.requestCount)

        // 墙钟向后跳回 6 分钟、单调时钟走满间隔：照常发。
        now -= 6 * 60 * 1000L
        elapsed += EndpointHeartbeatReporter.MIN_INTERVAL_MILLIS
        server.enqueue(jsonResponse(OK_ENVELOPE))
        assertEquals(EndpointHeartbeatOutcome.SENT, reporter.reportIfDue(null))
        assertEquals(2, server.requestCount)
    }

    /** 没登录就不发：既不打无谓的 401，也不在服务端留下"刚活着"的假记录。 */
    @Test
    fun withoutATokenNothingIsSent() = runTest {
        val reporter = reporter(now = { CLOCK }, withToken = false)

        val outcome = reporter.reportIfDue(null)

        assertEquals(EndpointHeartbeatOutcome.SKIPPED_UNAUTHENTICATED, outcome)
        assertEquals(0, server.requestCount)
        assertNull(reporter.status.value.lastAttemptAtMillis)
        assertNull(reporter.status.value.lastSuccessAtMillis)
    }

    // ---- 失败可观测性 ---------------------------------------------------------

    @Test
    fun serverFailureIsRecordedAndNeverThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        var elapsed = MONOTONIC
        val reporter = reporter(elapsed = { elapsed })

        assertEquals(EndpointHeartbeatOutcome.FAILED, reporter.reportIfDue(null))
        assertEquals(1, reporter.status.value.consecutiveFailures)
        assertNotNull(reporter.status.value.lastError)
        assertTrue(reporter.status.value.lastError!!.contains("500"))
        assertNull(reporter.status.value.lastSuccessAtMillis)
        assertEquals(CLOCK, reporter.status.value.lastAttemptAtMillis)

        elapsed += EndpointHeartbeatReporter.MIN_INTERVAL_MILLIS
        server.enqueue(MockResponse().setResponseCode(503).setBody("still down"))
        assertEquals(EndpointHeartbeatOutcome.FAILED, reporter.reportIfDue(null))
        assertEquals(2, reporter.status.value.consecutiveFailures)
    }

    @Test
    fun successClearsTheFailureStreak() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        var now = CLOCK
        var elapsed = MONOTONIC
        val reporter = reporter(now = { now }, elapsed = { elapsed })
        reporter.reportIfDue(null)
        assertEquals(1, reporter.status.value.consecutiveFailures)

        now += EndpointHeartbeatReporter.MIN_INTERVAL_MILLIS
        elapsed += EndpointHeartbeatReporter.MIN_INTERVAL_MILLIS
        server.enqueue(jsonResponse(OK_ENVELOPE))
        assertEquals(EndpointHeartbeatOutcome.SENT, reporter.reportIfDue(null))

        assertEquals(0, reporter.status.value.consecutiveFailures)
        assertNull(reporter.status.value.lastError)
        assertEquals(now, reporter.status.value.lastSuccessAtMillis)
        assertEquals("Healthy", reporter.status.value.lastReportedUploadStatus)
    }

    /** 传输层失败（连接被拒）同样要有出口，且不把循环炸掉。 */
    @Test
    fun transportFailureIsRecorded() = runTest {
        server.shutdown()
        val reporter = reporter(now = { CLOCK })

        assertEquals(EndpointHeartbeatOutcome.FAILED, reporter.reportIfDue(null))

        assertEquals(1, reporter.status.value.consecutiveFailures)
        assertNotNull(reporter.status.value.lastError)
    }

    // ---- helpers -------------------------------------------------------------

    /** 队列条数仓库用内存 flow 拼出来（与生产同一个类，只是数据源换成测试桩）。 */
    private fun queueStatusRepository(pendingLocationPoints: Int = 0) =
        com.pim.app.status.QueueStatusRepository(
            locations = kotlinx.coroutines.flow.MutableStateFlow(pendingLocationPoints),
            usageEvents = kotlinx.coroutines.flow.MutableStateFlow(0),
            usageSummaries = kotlinx.coroutines.flow.MutableStateFlow(0),
            appMetadata = kotlinx.coroutines.flow.MutableStateFlow(0),
            deviceProfiles = kotlinx.coroutines.flow.MutableStateFlow(0),
            syncBatches = kotlinx.coroutines.flow.MutableStateFlow(0)
        )

    private fun bodyUploadStatus(request: okhttp3.mockwebserver.RecordedRequest): String =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            .getValue("uploadStatus").toString().trim('"')

    private fun reporter(
        now: () -> Long = { CLOCK },
        elapsed: () -> Long = { MONOTONIC },
        withToken: Boolean = true,
        api: ApiService? = null,
        pendingLocationPoints: Int = 0,
        uploadOutcomeSource: UploadOutcomeSource? = null,
        uploadOutcome: UploadOutcome = UploadOutcome(lastError = null, hasCompletedUpload = true)
    ): EndpointHeartbeatReporter {
        val baseUrl = server.url("/api/v1/").toString()
        val prefs: SharedPreferences = context.getSharedPreferences(
            "endpoint-heartbeat-" + System.nanoTime(),
            Context.MODE_PRIVATE
        ).also { it.edit().clear().commit() }
        val tokenManager = TokenManager(EndpointHeartbeatTestPreferencesFactory(prefs))
        val serverSettings = ServerSettingsStore(context, tokenManager)
        serverSettings.setBaseUrl(baseUrl)
        if (withToken) {
            assertTrue(
                tokenManager.saveTokens(
                    accessToken = "access",
                    refreshToken = "refresh",
                    expiresAt = "2099-01-01T00:00:00Z",
                    serverUrl = baseUrl
                )
            )
        }

        // 走**生产**的 ApiService 装配路径（ApiClientProvider → Retrofit + kotlinx.serialization）。
        val apiClientProvider = ApiClientProvider(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            settingsStore = serverSettings
        )

        val trackingSettings = TrackingSettingsStore(prefs)
        val logs = StructuredLogRepository(context, trackingSettings) { now() }

        return EndpointHeartbeatReporter(
            context = context,
            api = api ?: apiClientProvider.apiService(),
            identityProvider = DeviceIdentityProvider(context),
            uploadOutcomeSource = uploadOutcomeSource ?: UploadOutcomeSource { uploadOutcome },
            tokenManager = tokenManager,
            serverSettingsStore = serverSettings,
            logs = logs,
            queueStatusRepository = queueStatusRepository(pendingLocationPoints),
            nowUtcMillis = now,
            elapsedRealtimeMillis = elapsed
        )
    }

    /** 一调用上游就被取消的 ApiService（只实现被用到的那一个方法，其余显式报错）。 */
    private fun cancelingApi(): ApiService = java.lang.reflect.Proxy.newProxyInstance(
        ApiService::class.java.classLoader,
        arrayOf(ApiService::class.java)
    ) { proxy, method, _ ->
        when (method.name) {
            "sendEndpointHeartbeat" -> throw kotlinx.coroutines.CancellationException("test cancellation")
            "toString" -> "CancelingApiService"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> false
            else -> error("Unexpected API call in test: ${method.name}")
        }
    } as ApiService

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val CLOCK = 1_760_000_000_000L

        /** 单调时钟起点（与墙钟无关）。 */
        const val MONOTONIC = 5_000_000L

        const val OK_ENVELOPE = """
            {"code":0,"data":{"deviceId":"android-1","platform":"android","appVersion":"0.0.0(local)",
            "uploadStatus":"Healthy","collectionCacheCount":0,"onlineOnlyBlockedCount":0,
            "lastHeartbeatAt":"2026-10-14T00:00:00Z"},"message":"","timestamp":"2026-10-14T00:00:00Z"}
        """
    }
}

private class EndpointHeartbeatTestPreferencesFactory(
    private val preferences: SharedPreferences
) : SecurePreferencesFactory {
    override fun open(): SharedPreferences = preferences
}

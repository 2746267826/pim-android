package com.pim.core.contract

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pim.core.models.MobileLocationPointRequest
import com.pim.core.models.MobileLocationPointsUploadRequest
import com.pim.core.network.ApiService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * 真实客户端行为证据：用与 `ApiClientProvider` 相同的 Retrofit + kotlinx.serialization
 * 配置（`Json { ignoreUnknownKeys = true }` + `asConverterFactory`）真正发出请求，
 * 用 MockWebServer 的 recorded request 读出**实际路径、query 与请求体 JSON**，
 * 再与契约逐项对照。
 *
 * 与 `ApiContractConformanceTest`（声明侧，注解反射）互补：这条证明声明侧的路径/字段名
 * 在 wire 上确实长这样，而不是只在注解里成立。
 *
 * 第二轮修正（I-5）：
 * - 抓包对象换成**审计判定为生产活跃**的 POST：`mobile/location/points/batch`
 *   （`api-inventory.json` 的 14 个 `active_in_production=true` 之一，生产调用点
 *   `LocationUploadCoordinator.kt`）。旧用例抓的是 test-only 的单点 `mobile/location/points`。
 * - `takeRequest` 带超时 + `assertNotNull`：请求没发出时是**失败**，不是无限挂起；
 * - 不再用 `runCatching {}` 静默吞掉业务异常：异常只允许作为「响应体与 DTO 形状不符」存在，
 *   并且必须显式记录在断言消息里，同时仍要求请求已被真实捕获。
 */
class RealRetrofitRequestShapeTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService
    private val contract = OpenApiContract.load()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()),
            )
            .build()
            .create(ApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 有超时的抓包：请求没发出时失败，而不是永久阻塞。 */
    private fun takeRequest(): RecordedRequest {
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)
        assertNotNull("10 秒内没有捕获到客户端发出的请求（请求未发出或路径不匹配）", recorded)
        return recorded!!
    }

    @Test
    fun `活跃 GET 的真实请求路径与 query 与契约一致`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0,"message":"ok"}"""))
        val failure = runBlocking { runCatching { api.getMobileSummary(date = "2026-10-10", deviceId = "dev-1") }.exceptionOrNull() }
        assertNull("合法响应不应产生异常：$failure", failure)

        val recorded = takeRequest()
        val path = recorded.path!!
        assertEquals("/api/v1/mobile/summary?date=2026-10-10&deviceId=dev-1", path)

        val operation = contract.operation(path.substringBefore('?'), "GET")
        assertNotNull("真实发出的路径必须在契约里：$path", operation)
        val recordedParams = path.substringAfter('?', "").split('&').filter { it.isNotBlank() }
            .map { it.substringBefore('=') }
        val extra = recordedParams.filter { param ->
            operation!!.queryParams.none { it.equals(param, ignoreCase = true) }
        }
        assertTrue("真实发出的 query 参数在契约中不存在：$extra", extra.isEmpty())
    }

    @Test
    fun `生产活跃 POST 的真实请求体字段与契约组件一致`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":0,"message":"ok"}"""))
        val failure = runBlocking { runCatching { api.uploadMobileLocationsBatch(batch()) }.exceptionOrNull() }

        // 请求必须先真实发出；异常不再被静默吞掉 —— 有异常就断言失败并带上原因
        val recorded = takeRequest()
        assertNull("合法响应不应产生异常（不得静默吞掉）：$failure", failure)
        assertEquals(
            "生产活跃端点必须打到批量上传路径（单点 mobile/location/points 是 test-only，不作为活跃证据）",
            "/api/v1/mobile/location/points/batch",
            recorded.path,
        )
        assertEquals("POST", recorded.method)

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val contractFields = contract.schemaPropertyNames("MobileLocationPointsUploadRequest")
        assertNotNull("契约必须声明 MobileLocationPointsUploadRequest 组件", contractFields)
        val wireNames = body.keys
        assertTrue(
            "真实请求体出现契约外字段：${wireNames - contractFields!!}（wire：$wireNames）",
            (wireNames - contractFields).isEmpty(),
        )
        assertTrue("请求体必须带 points 字段，实际：$wireNames", wireNames.contains("points"))

        // 嵌套点对象的 wire 名同样要与契约组件一致（审计注入 B 的 @SerialName 漂移面）
        val pointFields = contract.schemaPropertyNames("MobileLocationPointRequest")
        assertNotNull("契约必须声明 MobileLocationPointRequest 组件", pointFields)
        val firstPoint = body.getValue("points").let { it as kotlinx.serialization.json.JsonArray }
            .first().jsonObject
        assertTrue(
            "嵌套定位点出现契约外字段：${firstPoint.keys - pointFields!!}（wire：${firstPoint.keys}）",
            (firstPoint.keys - pointFields).isEmpty(),
        )
        assertTrue(
            "嵌套定位点必须带 latitude（若改为 latitudeX 应在这里失败），实际：${firstPoint.keys}",
            firstPoint.keys.contains("latitude"),
        )
    }

    @Test
    fun `审计标记为 test-only 的单点端点不再作为活跃 wire 证据`() {
        // 反例守卫：这条用例证明「抓 test-only 端点」与「抓活跃端点」是两个不同的路径，
        // 若实现把活跃端点误删/改名，上一条会在路径断言处失败。
        val active = contract.operation("/api/v1/mobile/location/points/batch", "POST")
        assertNotNull("批量端点必须是契约里的 POST operation", active)
        assertEquals(
            "MobileLocationPointsUploadRequest",
            active!!.requestSchemaName,
        )
    }

    private fun batch() = MobileLocationPointsUploadRequest(
        points = listOf(
            MobileLocationPointRequest(
                deviceId = "dev-1",
                recordedAtUtc = "2026-10-10T00:00:00Z",
                latitude = 1.0,
                longitude = 2.0,
                horizontalAccuracyMeters = 5.0,
                provider = "gps",
                sourceKind = "live",
                isAutoSubmitted = false,
                rawJson = "{}",
            ),
        ),
    )
}

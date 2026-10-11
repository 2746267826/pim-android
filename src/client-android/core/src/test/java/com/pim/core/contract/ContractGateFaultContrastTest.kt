package com.pim.core.contract

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pim.core.models.DaemonHeartbeatRequest
import com.pim.core.models.DaemonSourceState
import com.pim.core.models.MobileLocationPointRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 故障对照：证明上面那套契约门禁**确实能拦住**审计用过的两类注入，
 * 而不是「因为实现里没写检查所以恰好不报」。
 *
 * - 注入 A 原形：把活跃端点 `mobile/summary` 改成契约外路径。
 *   这里用真实 Retrofit 客户端 + MockWebServer 捕获**真实发出的请求路径**再喂给门禁。
 * - 注入 B 原形：给 `MobileLocationPointRequest.latitude` 加 `@SerialName("latitudeX")`。
 *   这里用同样形状的变异 DTO（真实 kotlinx 序列化器，先证明它真的序列化成 `latitudeX`），
 *   再喂给同一套字段级核对。
 */
class ContractGateFaultContrastTest {

    private val contract = OpenApiContract.load()

    // ---- 注入 A：非法路径 ----

    private interface RenamedMobileSummaryApi {
        @GET("mobile/summaryX")
        suspend fun getMobileSummary(
            @Query("date") date: String? = null,
            @Query("deviceId") deviceId: String? = null,
        ): okhttp3.ResponseBody
    }

    @Test
    fun `真实 Retrofit 改路径后发出的请求会被门禁判为契约外路径`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))
            val api = Retrofit.Builder()
                .baseUrl(server.url("/api/v1/"))
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(RenamedMobileSummaryApi::class.java)

            runBlocking { runCatching { api.getMobileSummary(date = "2026-10-10") } }
            val captured = server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)?.path
            assertNotNull("10 秒内没有捕获到客户端请求", captured)
            assertEquals("/api/v1/mobile/summaryX?date=2026-10-10", captured)

            // 真实捕获到的 path 直接喂给门禁（M-2：不再只用于 assertNull 反证）
            val fromWire = ApiServiceDeclarations.Declared(
                functionName = "getMobileSummary",
                httpMethod = "GET",
                declaredPath = captured!!.substringBefore('?').removePrefix("/api/v1/"),
                path = captured.substringBefore('?'),
                queryParams = listOf("date"),
                pathParams = emptyList(),
                bodyType = null,
            )
            val wireReport = ContractConformance.check(listOf(fromWire), contract)
            assertTrue(
                "真实 wire 路径改名必须被 R-PATH 拦住，实际报告：\n${wireReport.render()}",
                wireReport.violationsOf(ContractConformance.RULE_PATH).any { it.detail.contains("/api/v1/mobile/summaryX") },
            )

            // 注解反射扫描同样要读出被改过的相对路径（声明层证据）
            val declared = ApiServiceDeclarations.scan(RenamedMobileSummaryApi::class.java)
            assertEquals(
                "扫描器必须从注解读出被改过的相对路径",
                listOf("mobile/summaryX"), declared.map { it.declaredPath },
            )
            val report = ContractConformance.check(declared, contract)
            assertTrue(
                "路径改名必须被 R-PATH 拦住，实际报告：\n${report.render()}",
                report.violationsOf(ContractConformance.RULE_PATH).any { it.detail.contains("/api/v1/mobile/summaryX") },
            )

            // 反向证明检测不是恒真：未改名的真实路径在契约里存在
            assertNotNull(
                "对照组失败：/api/v1/mobile/summary 本应存在于契约",
                contract.operation("/api/v1/mobile/summary", "GET"),
            )
            assertNull(contract.operation(captured.substringBefore('?'), "GET"))
        } finally {
            server.shutdown()
        }
    }

    // ---- 注入 B：@SerialName 漂移 ----

    /**
     * 注入 B 的变异 DTO：**必须与生产 `MobileLocationPointRequest` 逐字段一致**，只把
     * `latitude` 的 wire 名改成 `latitudeX`（M-3：手抄副本会静默漂移，所以这里额外断言
     * 两者字段集合只差这一个改名 —— 生产 DTO 新增字段时这条断言会提醒同步变异体）。
     */
    @Serializable
    private data class MutatedLocationPointRequest(
        val deviceId: String,
        val recordedAtUtc: String,
        @SerialName("latitudeX") val latitude: Double,
        val longitude: Double,
        val horizontalAccuracyMeters: Double,
        val provider: String,
        val sourceKind: String,
        val altitudeMeters: Double? = null,
        val verticalAccuracyMeters: Double? = null,
        val speedMetersPerSecond: Double? = null,
        val speedAccuracyMetersPerSecond: Double? = null,
        val bearingDegrees: Double? = null,
        val bearingAccuracyDegrees: Double? = null,
        val isAutoSubmitted: Boolean,
        val rawJson: String,
    )

    @Test
    fun `字段改名（SerialName 漂移）会被门禁判为契约外字段`() {
        // 先证明变异是真实的：真实 kotlinx 序列化器输出的 wire 名是 latitudeX
        val json = Json.encodeToString(MutatedLocationPointRequest.serializer(), mutatedPoint())
        assertTrue("变异 DTO 的 wire 名应为 latitudeX，实际：$json", json.contains("\"latitudeX\""))

        // M-3：变异 DTO 的字段集合必须与生产 DTO 只差 latitude → latitudeX，防止手抄副本静默漂移
        val mutatedFields = SerialNames.of(MutatedLocationPointRequest::class.java)
        val productionFields = SerialNames.of(MobileLocationPointRequest::class.java)
        assertEquals(
            "变异 DTO 与生产 DTO 的 wire 字段集合必须只差这一个改名，实际差异：" +
                "多=${mutatedFields - productionFields}，少=${productionFields - mutatedFields}",
            productionFields - "latitude" + "latitudeX",
            mutatedFields,
        )

        val declared = ApiServiceDeclarations.Declared(
            functionName = "uploadMobileLocation",
            httpMethod = "POST",
            declaredPath = "mobile/location/points",
            path = ApiServiceDeclarations.qualify("mobile/location/points"),
            queryParams = emptyList(),
            pathParams = emptyList(),
            bodyType = MutatedLocationPointRequest::class.java,
        )
        val report = ContractConformance.check(listOf(declared), contract)
        val violations = report.violationsOf(ContractConformance.RULE_BODY_SCHEMA)
        assertTrue(
            "字段改名必须被 R-BODY-SCHEMA 拦住，实际报告：\n${report.render()}",
            violations.any { it.detail.contains("latitudeX") },
        )
    }

    @Test
    fun `真实请求 DTO 的 wire 名与契约组件字段一致`() {
        val json = Json.encodeToString(MobileLocationPointRequest.serializer(), realPoint())
        val names = SerialNames.of(MobileLocationPointRequest::class.java)
        val contractFields = contract.schemaPropertyNames("MobileLocationPointRequest")!!
        assertTrue("真实 DTO 出现契约外字段：${names - contractFields}", (names - contractFields).isEmpty())
        assertTrue("序列化结果必须包含 latitude 而不是 latitudeX", json.contains("\"latitude\""))
    }

    // ---- I-1：type / enum / nullable / required 的故障对照 ----

    @Serializable
    private enum class GoodDaemonSourceState {
        @SerialName("Unknown") UNKNOWN,
        @SerialName("Available") AVAILABLE,
        @SerialName("Unavailable") UNAVAILABLE,
        @SerialName("Paused") PAUSED,
    }

    @Serializable
    private data class EnumStateHeartbeat(
        val deviceId: String,
        val daemonKind: String,
        val version: String,
        val serverUrl: String,
        val collectionPaused: Boolean = false,
        val activityWatchState: GoodDaemonSourceState = GoodDaemonSourceState.UNKNOWN,
        val keyStatsState: GoodDaemonSourceState = GoodDaemonSourceState.UNKNOWN,
    )

    @Serializable
    private enum class BadDaemonSourceState {
        @SerialName("Unknown") UNKNOWN,
        @SerialName("Wat") WAT,
    }

    @Serializable
    private data class BadEnumHeartbeat(
        val activityWatchState: BadDaemonSourceState = BadDaemonSourceState.UNKNOWN,
        val keyStatsState: BadDaemonSourceState = BadDaemonSourceState.UNKNOWN,
    )

    private fun heartbeatDeclared(body: Class<*>) = ApiServiceDeclarations.Declared(
        functionName = "sendHeartbeat",
        httpMethod = "POST",
        declaredPath = "daemon/heartbeat",
        path = ApiServiceDeclarations.qualify("daemon/heartbeat"),
        queryParams = emptyList(),
        pathParams = emptyList(),
        bodyType = body,
    )

    @Serializable
    private data class StringStateHeartbeat(
        val activityWatchState: String = "Unknown",
        val keyStatsState: String = "Unknown",
    )

    @Test
    fun `契约枚举字段必须被客户端枚举约束（String 会被判违规）`() {
        // C-2 极性：反例必须是**合成** DTO，不能拿真实生产 DTO 当「当前必须坏」的前置断言
        // ——否则产品按契约改成 enum 之后这条对照测试自己会反红。
        val stringReport = ContractConformance.check(
            listOf(heartbeatDeclared(StringStateHeartbeat::class.java)),
            contract,
        )
        assertTrue(
            "用 String 表达契约枚举必须被 R-BODY-ENUM 拦住，实际：\n${stringReport.render()}",
            stringReport.violationsOf(ContractConformance.RULE_BODY_ENUM)
                .any { it.detail.contains("activityWatchState") },
        )

        // 正例：客户端用与契约一致的枚举 → 不判违规
        val enumReport = ContractConformance.check(listOf(heartbeatDeclared(EnumStateHeartbeat::class.java)), contract)
        val enumViolations = enumReport.violationsOf(ContractConformance.RULE_BODY_ENUM)
        assertTrue(
            "与契约一致的客户端枚举不应被判违规，实际：${enumViolations.joinToString("\n")}",
            enumViolations.isEmpty(),
        )

        // 故障对照：客户端枚举多一个契约外值 → 必须判违规
        val badReport = ContractConformance.check(listOf(heartbeatDeclared(BadEnumHeartbeat::class.java)), contract)
        assertTrue(
            "客户端枚举含契约外值必须被 R-BODY-ENUM 拦住，实际：\n${badReport.render()}",
            badReport.violationsOf(ContractConformance.RULE_BODY_ENUM).any { it.detail.contains("Wat") },
        )
    }

    @Test
    fun `生产心跳 DTO 的枚举 wire 值与契约枚举逐字一致`() {
        // 产品侧断言（I-1 的修复结果）：真实 DTO 序列化出来的 wire 值必须是契约里的字符串，
        // 不是 Kotlin 常量名（`UNKNOWN` 之类）。用**非默认值**构造，确保真的被编码进请求体。
        val request = DaemonHeartbeatRequest(
            deviceId = "device-1",
            daemonKind = "android",
            version = "1.0",
            serverUrl = "http://contract-probe.invalid",
            activityWatchState = DaemonSourceState.PAUSED,
            keyStatsState = DaemonSourceState.UNAVAILABLE,
        )
        val json = Json.encodeToString(DaemonHeartbeatRequest.serializer(), request)
        assertTrue("activityWatchState 的 wire 值应为契约字符串 Paused，实际：$json", json.contains("\"activityWatchState\":\"Paused\""))
        assertTrue("keyStatsState 的 wire 值应为契约字符串 Unavailable，实际：$json", json.contains("\"keyStatsState\":\"Unavailable\""))

        // 四个契约值都必须可表达，且 wire 值与契约 enum 逐字一致（不多不少）。
        val wireValues = DaemonSourceState.values().map {
            Json.encodeToString(DaemonSourceState.serializer(), it)
        }.toSet()
        assertEquals(
            "DaemonSourceState 的 wire 值集合必须与契约 enum 完全一致",
            setOf("\"Unknown\"", "\"Available\"", "\"Unavailable\"", "\"Paused\""),
            wireValues,
        )

        val fields = SerialNames.of(DaemonHeartbeatRequest::class.java)
        assertTrue(
            "心跳 DTO 必须仍以 activityWatchState/keyStatsState 作为 wire 字段名，实际：$fields",
            fields.containsAll(setOf("activityWatchState", "keyStatsState")),
        )
        val report = ContractConformance.check(
            listOf(heartbeatDeclared(DaemonHeartbeatRequest::class.java)),
            contract,
        )
        assertTrue(
            "生产心跳 DTO 不得再有枚举/类型/可空性违规，实际：\n${report.render()}",
            report.violations.isEmpty(),
        )
    }

    @Test
    fun `契约字段类型不匹配与非法可空性会被判违规`() {
        // 类型不匹配：契约 activityWatchState 是 string（enum），给一个 Int 字段
        val typeReport = ContractConformance.check(
            listOf(heartbeatDeclared(TypeMismatchHeartbeat::class.java)),
            contract,
        )
        assertTrue(
            "字段类型与契约 type 不符必须被 R-BODY-TYPE 拦住，实际：\n${typeReport.render()}",
            typeReport.violationsOf(ContractConformance.RULE_BODY_TYPE).isNotEmpty(),
        )

        // 可空性：契约 collectionPaused 不可空，客户端可空 → 可能发出契约不允许的 null
        val nullableReport = ContractConformance.check(
            listOf(heartbeatDeclared(NullableHeartbeat::class.java)),
            contract,
        )
        assertTrue(
            "契约不可空字段客户端可空必须被 R-BODY-NULLABLE 拦住，实际：\n${nullableReport.render()}",
            nullableReport.violationsOf(ContractConformance.RULE_BODY_NULLABLE).isNotEmpty(),
        )
    }

    @Test
    fun `契约 required 字段在客户端可选时必须被判违规`() {
        // 契约 POST /api/v1/pc/browser-tt/import 的 SiteImportRequest 把 content 标为 required
        val violating = ApiServiceDeclarations.Declared(
            functionName = "importSiteData",
            httpMethod = "POST",
            declaredPath = "pc/browser-tt/import",
            path = "/api/v1/pc/browser-tt/import",
            queryParams = emptyList(),
            pathParams = emptyList(),
            bodyType = OptionalContentImport::class.java,
        )
        val report = ContractConformance.check(listOf(violating), contract)
        assertTrue(
            "契约 required 字段在客户端有默认值时必须被 R-BODY-REQUIRED 拦住，实际：\n${report.render()}",
            report.violationsOf(ContractConformance.RULE_BODY_REQUIRED).any { it.detail.contains("content") },
        )

        val complying = violating.copy(bodyType = RequiredContentImport::class.java)
        val okReport = ContractConformance.check(listOf(complying), contract)
        assertTrue(
            "契约 required 字段在客户端必填时必须通过，实际：\n${okReport.render()}",
            okReport.violationsOf(ContractConformance.RULE_BODY_REQUIRED).isEmpty(),
        )
    }

    @Serializable
    private data class TypeMismatchHeartbeat(
        val activityWatchState: Int = 0,
        val keyStatsState: Int = 0,
    )

    @Serializable
    private data class NullableHeartbeat(
        val collectionPaused: Boolean? = null,
    )

    @Serializable
    private data class OptionalContentImport(
        val content: String = "",
    )

    @Serializable
    private data class RequiredContentImport(
        val content: String,
    )

    // ---- 口径对照：这些**不**应被判违规 ----

    @Test
    fun `query 参数仅大小写不同不判违规`() {
        val declared = ApiServiceDeclarations.Declared(
            functionName = "getMobileLocationOverview",
            httpMethod = "GET",
            declaredPath = "mobile/location/analytics/overview",
            path = ApiServiceDeclarations.qualify("mobile/location/analytics/overview"),
            queryParams = listOf("rangeStartUtc", "rangeEndUtc"),
            pathParams = emptyList(),
            bodyType = null,
        )
        val report = ContractConformance.check(listOf(declared), contract)
        assertTrue("大小写差异不应判违规：\n${report.render()}", report.violations.isEmpty())
        assertTrue("大小写差异应记录为 note", report.notes.isNotEmpty())
    }

    @Test
    fun `少发契约里的可选参数不判违规`() {
        val declared = ApiServiceDeclarations.Declared(
            functionName = "getMobileSummary",
            httpMethod = "GET",
            declaredPath = "mobile/summary",
            path = ApiServiceDeclarations.qualify("mobile/summary"),
            queryParams = listOf("date", "deviceId"),
            pathParams = emptyList(),
            bodyType = null,
        )
        val report = ContractConformance.check(listOf(declared), contract)
        assertTrue("未发送的可选契约参数不应判违规：\n${report.render()}", report.violations.isEmpty())
    }

    private fun mutatedPoint() = MutatedLocationPointRequest(
        deviceId = "device-1",
        recordedAtUtc = "2026-10-10T00:00:00Z",
        latitude = 1.0,
        longitude = 2.0,
        horizontalAccuracyMeters = 5.0,
        provider = "gps",
        sourceKind = "live",
        isAutoSubmitted = false,
        rawJson = "{}",
    )

    private fun realPoint() = MobileLocationPointRequest(
        deviceId = "device-1",
        recordedAtUtc = "2026-10-10T00:00:00Z",
        latitude = 1.0,
        longitude = 2.0,
        horizontalAccuracyMeters = 5.0,
        provider = "gps",
        sourceKind = "live",
        isAutoSubmitted = false,
        rawJson = "{}",
    )
}

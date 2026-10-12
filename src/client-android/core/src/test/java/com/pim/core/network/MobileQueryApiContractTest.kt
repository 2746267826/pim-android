package com.pim.core.network

import com.pim.core.contract.SerialNames
import com.pim.core.models.MobileAnalyticsRangeDto
import com.pim.core.models.MobileAppUsageSummaryDto
import com.pim.core.models.MobileGeoBoundsDto
import com.pim.core.models.MobileLocationAnalyticsOverviewResponse
import com.pim.core.models.MobileLocationHistoryResponse
import com.pim.core.models.MobileLocationPathPointDto
import com.pim.core.models.MobileLocationSegmentDto
import com.pim.core.models.MobileLocationTrackDto
import com.pim.core.models.MobileQualityResponse
import com.pim.core.models.MobileTimelineResponse
import com.pim.core.models.MobileUsageSummaryResponse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移动端查询链路的**形状**检查（F-A-03 的旧 `contains` 测试降级，M-6）。
 *
 * 原实现只对 `ApiService.kt` / `MobileModels.kt` 做源码字符串 `contains` 断言：改字段名、
 * 改路径、甚至删掉被断言的文本都能逃逸（审计故障对照 B 已证）。
 *
 * 现在职责拆分（**不弱化覆盖**）：
 * - 路径 / method / query / path 参数 / 请求体形状、字段名与类型/枚举/可空/必填
 *   → `ApiContractConformanceTest`（真实 Retrofit 注解反射 + 源码独立对账 + 真实契约）+ 故障对照；
 * - 真实请求的 wire 形状 → `RealRetrofitRequestShapeTest`（真实 Retrofit + MockWebServer 抓包）；
 * - 本类只保留**响应侧 DTO**这一层：用真实 kotlinx.serialization 描述符断言模型确实可序列化、
 *   且关键 wire 字段存在（改 `@SerialName`/字段名即红），不再做任何源码文本匹配。
 */
class MobileQueryApiContractTest {

    @Test
    fun mobileTimelineResponseCarriesPaginationAndTruncationMarkers() {
        // #330：单日会话可达数千条，服务端分页返回；客户端必须能识别 hasMore/truncated。
        val fields = SerialNames.of(MobileTimelineResponse::class.java)
        for (name in listOf("hasMore", "truncated", "totalCount", "page", "pageSize", "sessions", "items")) {
            assertTrue("MobileTimelineResponse 必须以真实序列化名暴露 `$name`，实际：$fields", name in fields)
        }
    }

    @Test
    fun mobileQueryResponseDtosAreRealSerializableModels() {
        val dtos = listOf(
            MobileUsageSummaryResponse::class.java,
            MobileAppUsageSummaryDto::class.java,
            MobileTimelineResponse::class.java,
            MobileQualityResponse::class.java,
            MobileLocationHistoryResponse::class.java,
            MobileLocationAnalyticsOverviewResponse::class.java,
            MobileLocationTrackDto::class.java,
            MobileLocationSegmentDto::class.java,
            MobileLocationPathPointDto::class.java,
            MobileGeoBoundsDto::class.java,
        )
        for (dto in dtos) {
            val fields = SerialNames.ofOrNull(dto)
            assertNotNull("${dto.simpleName} 必须存在且标注 @Serializable（真实描述符读取失败）", fields)
            assertTrue("${dto.simpleName} 不应是空模型，实际字段：$fields", fields!!.isNotEmpty())
        }
    }

    @Test
    fun mobileAnalyticsRangeDtoExposesUtcAndLocalWindowFields() {
        val fields = SerialNames.of(MobileAnalyticsRangeDto::class.java)
        for (name in listOf("rangeStartUtc", "rangeEndUtc", "timezone", "localStartDate", "localEndDate")) {
            assertTrue("MobileAnalyticsRangeDto 必须以真实序列化名暴露 `$name`，实际：$fields", name in fields)
        }
    }
}

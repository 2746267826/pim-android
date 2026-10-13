package com.pim.core.network

import com.pim.core.contract.MobileTimelineResponse
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移动端查询接口的边界是「契约生成 + 编译期门禁」，因此这里的断言分两类：
 *  * 源码形状断言读的是 `ApiService.kt`（手写）与 `ContractModels.kt`（契约生成）；
 *  * 分页/截断标记用**真实构造生成模型**验证 —— 字段名或类型一旦偏离契约，测试根本编译不过。
 */
class MobileQueryApiContractTest {
    @Test
    fun apiServiceContainsMobileQueryEndpoints() {
        val api = repoFile("src", "main", "java", "com", "pim", "core", "network", "ApiService.kt").readText()

        assertTrue(api.contains("@GET(\"mobile/summary\")"))
        assertTrue(api.contains("@GET(\"mobile/timeline\")"))
        assertTrue(api.contains("@GET(\"mobile/quality\")"))
        assertTrue(api.contains("@GET(\"mobile/location/history\")"))
        assertTrue(api.contains("@GET(\"mobile/location/analytics/overview\")"))
        assertTrue(api.contains("@GET(\"mobile/location/analytics/tracks\")"))
        assertTrue(api.contains("@GET(\"mobile/location/analytics/segments/{segmentId}/points\")"))
        assertTrue(api.contains("@Query(\"date\") date: String? = null"))
        assertTrue(api.contains("@Query(\"rangeStartUtc\") rangeStartUtc: String? = null"))
        assertTrue(api.contains("@Query(\"rangeEndUtc\") rangeEndUtc: String? = null"))
        assertTrue(api.contains("@Query(\"timezone\") timezone: String? = null"))
    }

    @Test
    fun mobileTimelineSupportsPaginationAndTruncationMarkers() {
        // #330：单日会话可达数千条，服务端分页返回；客户端必须能请求指定页
        // 并识别 hasMore/truncated，否则只能显示一天的前一段。
        val api = repoFile("src", "main", "java", "com", "pim", "core", "network", "ApiService.kt").readText()

        assertTrue(api.contains("@Query(\"page\") page: Int? = null"))
        assertTrue(api.contains("@Query(\"pageSize\") pageSize: Int? = null"))

        // 契约字段名/类型由生成模型在编译期兜住：这里真的构造一次并读回来。
        val response = MobileTimelineResponse(
            date = "2026-07-07",
            deviceId = "device-1",
            generatedAt = "2026-07-07T09:00:00Z",
            items = emptyList(),
            sessions = emptyList(),
            fallbackSummaries = emptyList(),
            page = 2,
            pageSize = 5000,
            totalCount = 42,
            sessionTotalCount = 42,
            fallbackTotalCount = 0,
            hasMore = true,
            truncated = false
        )

        assertEquals(2, response.page)
        assertEquals(5000, response.pageSize)
        assertEquals(42, response.totalCount)
        assertEquals(true, response.hasMore)
        assertEquals(false, response.truncated)
    }

    @Test
    fun mobileModelsContainQueryDtosUsedByAndroidV2() {
        val models = repoFile(
            "src", "main", "java", "com", "pim", "core", "contract", "ContractModels.kt"
        ).readText()

        for (name in listOf(
            "MobileUsageSummaryResponse",
            "MobileAppUsageSummaryDto",
            "MobileTimelineResponse",
            "MobileQualityResponse",
            "MobileLocationHistoryResponse",
            "MobileLocationAnalyticsOverviewResponse",
            "MobileLocationTrackDto",
            "MobileLocationSegmentDto",
            "MobileLocationPathPointDto",
            "MobileGeoBoundsDto"
        )) {
            assertTrue("$name must exist", models.contains("data class $name"))
        }
        assertTrue(models.contains("val rangeStartUtc: String"))
        assertTrue(models.contains("val rangeEndUtc: String"))
        assertTrue(models.contains("val localStartDate: String"))
        assertTrue(models.contains("val localEndDate: String"))
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
}

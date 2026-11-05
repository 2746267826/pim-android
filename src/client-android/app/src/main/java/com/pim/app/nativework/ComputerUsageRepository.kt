package com.pim.app.nativework

import com.pim.core.contract.SiteDailyRowDto
import com.pim.core.contract.SiteSummaryDto
import com.pim.core.contract.SiteTimelineRowDto
import com.pim.core.contract.TypedDetailQueryResponse
import com.pim.core.contract.TypedDetailQueryResponseApiResponse
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.PcDetailRecord
import com.pim.core.network.ApiService
import java.time.LocalDate

data class ComputerUsageSnapshot(
    val records: TypedDetailQueryResponse,
    val summary: SiteSummaryDto,
    val daily: List<SiteDailyRowDto>,
    val dailyRowsTruncated: Boolean,
    val timeline: List<SiteTimelineRowDto>,
    val devices: List<DeviceListDto>,
)

enum class ComputerUsageTypeFilter(val label: String) {
    All("全部"),
    AppWindow("应用窗口"),
    Browser("浏览器"),
}

class ComputerUsageRepository(private val api: ApiService) {
    fun boundTo(boundApi: ApiService): ComputerUsageRepository =
        ComputerUsageRepository(boundApi)

    suspend fun load(
        dateFrom: String,
        dateTo: String,
        deviceId: String?,
        page: Int,
        typeFilter: ComputerUsageTypeFilter = ComputerUsageTypeFilter.All,
    ): ComputerUsageSnapshot {
        require(page in 1..MAX_VISIBLE_PAGES) { "明细最多可浏览到第 $MAX_VISIBLE_PAGES 页" }
        val from = requireNotNull(runCatching { LocalDate.parse(dateFrom.trim()) }.getOrNull()) {
            "日期需使用 YYYY-MM-DD"
        }
        val to = requireNotNull(runCatching { LocalDate.parse(dateTo.trim()) }.getOrNull()) {
            "日期需使用 YYYY-MM-DD"
        }
        require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
        val inclusiveDays = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        require(inclusiveDays <= MAX_DATE_RANGE_DAYS) {
            "单次查询最多 $MAX_DATE_RANGE_DAYS 天（含首尾）；更长历史范围请使用 Web 完整查询"
        }
        val fromText = from.toString()
        val toText = to.toString()
        val device = deviceId?.trim()?.takeIf(String::isNotEmpty)
        val records = when (typeFilter) {
            ComputerUsageTypeFilter.All -> loadSingleType(fromText, toText, device, null, page).limitedPages()
            ComputerUsageTypeFilter.AppWindow -> loadSingleType(fromText, toText, device, "window", page).limitedPages()
            ComputerUsageTypeFilter.Browser -> loadBrowserPage(fromText, toText, device, page)
        }
        val summaryResponse = api.getBrowserUsageSummary(date = null, from = fromText, to = toText)
        val summary = requireData(
            summaryResponse.code,
            summaryResponse.data,
            "浏览器使用摘要为空",
            summaryResponse.message,
        )
        val dailyResponse = api.getBrowserUsageDaily(date = null, from = fromText, to = toText)
        val dailyRows = requireData(
            dailyResponse.code,
            dailyResponse.data,
            "浏览器每日统计为空",
            dailyResponse.message,
        )
        val daily = dailyRows.take(MAX_VISIBLE_DAILY_ROWS)
        val timelineResponse = api.getBrowserUsageTimeline(from.toString())
        val timeline = requireData(
            timelineResponse.code,
            timelineResponse.data,
            "浏览器时间线为空",
            timelineResponse.message,
        )
        val devicesResponse = api.getManagedDevices(sortBy = "active")
        val devices = requireData(
            devicesResponse.code,
            devicesResponse.data,
            "设备列表为空",
            devicesResponse.message,
        )
        return ComputerUsageSnapshot(
            records = records,
            summary = summary,
            daily = daily,
            dailyRowsTruncated = dailyRows.size > MAX_VISIBLE_DAILY_ROWS,
            timeline = timeline,
            devices = devices,
        )
    }

    private suspend fun loadSingleType(
        from: String,
        to: String,
        deviceId: String?,
        eventType: String?,
        page: Int,
    ): TypedDetailQueryResponse = requireDetailResponse(
        api.getPcDetail(
            dateFrom = from,
            dateTo = to,
            deviceId = deviceId,
            eventType = eventType,
            view = "raw",
            page = page,
            pageSize = PAGE_SIZE,
        ),
    )

    private suspend fun loadBrowserPage(
        from: String,
        to: String,
        deviceId: String?,
        page: Int,
    ): TypedDetailQueryResponse {
        val neededThroughPage = page * PAGE_SIZE
        val web = loadTypePrefix(from, to, deviceId, "web", neededThroughPage)
        val webPage = loadTypePrefix(from, to, deviceId, "web-page", neededThroughPage)
        val all = (web.items + webPage.items).sortedWith(
            compareByDescending<PcDetailRecord> { it.start }
                .thenBy { it.recordType }
                .thenBy { it.deviceId }
                .thenBy { it.recordKey.orEmpty() },
        )
        val totalCount = web.totalCount + webPage.totalCount
        val pageItems = all.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return TypedDetailQueryResponse(
            items = pageItems,
            page = page,
            pageSize = PAGE_SIZE,
            totalCount = totalCount,
            totalPages = ((totalCount + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtMost(MAX_VISIBLE_PAGES),
        )
    }

    private suspend fun loadTypePrefix(
        from: String,
        to: String,
        deviceId: String?,
        eventType: String,
        requiredCount: Int,
    ): TypedDetailQueryResponse {
        val items = mutableListOf<PcDetailRecord>()
        var firstResponse: TypedDetailQueryResponse? = null
        var apiPage = 1
        val requiredApiPages = (requiredCount + API_PAGE_SIZE - 1) / API_PAGE_SIZE
        while (apiPage <= requiredApiPages && apiPage <= MAX_API_PAGES_PER_TYPE) {
            val response = requireDetailResponse(
                api.getPcDetail(
                    dateFrom = from,
                    dateTo = to,
                    deviceId = deviceId,
                    eventType = eventType,
                    view = "raw",
                    page = apiPage,
                    pageSize = API_PAGE_SIZE,
                ),
            )
            if (firstResponse == null) firstResponse = response
            items += response.items
            if (apiPage >= response.totalPages || response.items.isEmpty()) break
            apiPage++
        }
        val metadata = firstResponse ?: return TypedDetailQueryResponse(
            items = emptyList(), page = 1, pageSize = API_PAGE_SIZE, totalCount = 0, totalPages = 0,
        )
        return metadata.copy(items = items)
    }

    private fun requireDetailResponse(
        response: TypedDetailQueryResponseApiResponse,
    ): TypedDetailQueryResponse = requireData(
        response.code,
        response.data,
        "电脑活动记录为空",
        response.message,
    )

    private fun TypedDetailQueryResponse.limitedPages() = copy(
        totalPages = totalPages.coerceAtMost(MAX_VISIBLE_PAGES),
    )

    private fun <T> requireData(code: Int, value: T?, emptyMessage: String, responseMessage: String): T {
        if (code != 0) {
            throw NativeWorkbenchRequestException(responseMessage.takeIf(String::isNotBlank) ?: emptyMessage)
        }
        return value ?: throw NativeWorkbenchRequestException(emptyMessage)
    }

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_VISIBLE_PAGES = 10
        const val MAX_DATE_RANGE_DAYS = 31
        const val MAX_VISIBLE_DAILY_ROWS = 500
        private const val API_PAGE_SIZE = 200
        private const val MAX_API_PAGES_PER_TYPE = 3
    }
}

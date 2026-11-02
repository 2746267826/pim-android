package com.pim.app.nativework

import com.pim.core.contract.SiteDailyRowDto
import com.pim.core.contract.SiteSummaryDto
import com.pim.core.contract.SiteTimelineRowDto
import com.pim.core.contract.TypedDetailQueryResponse
import com.pim.core.contract.DeviceListDto
import com.pim.core.network.ApiService
import java.time.LocalDate

data class ComputerUsageSnapshot(
    val records: TypedDetailQueryResponse,
    val summary: SiteSummaryDto,
    val daily: List<SiteDailyRowDto>,
    val timeline: List<SiteTimelineRowDto>,
    val devices: List<DeviceListDto>,
)

class ComputerUsageRepository(private val api: ApiService) {
    fun boundTo(boundApi: ApiService): ComputerUsageRepository =
        ComputerUsageRepository(boundApi)

    suspend fun load(
        dateFrom: String,
        dateTo: String,
        deviceId: String?,
        page: Int,
    ): ComputerUsageSnapshot {
        require(page >= 1) { "页码必须大于零" }
        val from = requireNotNull(runCatching { LocalDate.parse(dateFrom.trim()) }.getOrNull()) {
            "日期需使用 YYYY-MM-DD"
        }
        val to = requireNotNull(runCatching { LocalDate.parse(dateTo.trim()) }.getOrNull()) {
            "日期需使用 YYYY-MM-DD"
        }
        require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
        val fromText = from.toString()
        val toText = to.toString()
        val device = deviceId?.trim()?.takeIf(String::isNotEmpty)
        val recordsResponse = api.getPcDetail(
            dateFrom = fromText,
            dateTo = toText,
            deviceId = device,
            eventType = null,
            view = "raw",
            page = page,
            pageSize = PAGE_SIZE,
        )
        val records = requireData(
            recordsResponse.code,
            recordsResponse.data,
            "电脑活动记录为空",
            recordsResponse.message,
        )
        val summaryResponse = api.getBrowserUsageSummary(date = null, from = fromText, to = toText)
        val summary = requireData(
            summaryResponse.code,
            summaryResponse.data,
            "浏览器使用摘要为空",
            summaryResponse.message,
        )
        val dailyResponse = api.getBrowserUsageDaily(date = null, from = fromText, to = toText)
        val daily = requireData(
            dailyResponse.code,
            dailyResponse.data,
            "浏览器每日统计为空",
            dailyResponse.message,
        )
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
        return ComputerUsageSnapshot(records, summary, daily, timeline, devices)
    }

    private fun <T> requireData(code: Int, value: T?, emptyMessage: String, responseMessage: String): T {
        if (code != 0) {
            throw NativeWorkbenchRequestException(responseMessage.takeIf(String::isNotBlank) ?: emptyMessage)
        }
        return value ?: throw NativeWorkbenchRequestException(emptyMessage)
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}

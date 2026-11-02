package com.pim.app.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pim.app.nativework.NativeWorkbenchSession
import com.pim.app.nativework.NativeWorkbenchTransport
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.MobileTimelineResponse
import com.pim.core.contract.MobileUsageSummaryResponse
import com.pim.core.network.ApiService
import com.pim.core.network.UploadSessionChangedException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val MOBILE_USAGE_HISTORY_MAX_DAYS = 7L
internal const val MOBILE_USAGE_HISTORY_MAX_APPS_PER_DAY = 10
internal const val MOBILE_USAGE_HISTORY_PAGE_SIZE = 50
internal const val MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY = 10

data class MobileUsageHistoryQuery(
    val dates: List<LocalDate>,
    val deviceId: String?,
)

data class MobileUsageHistoryDay(
    val date: LocalDate,
    val summary: MobileUsageSummaryResponse,
    val timeline: MobileTimelineResponse,
    val loadingMore: Boolean = false,
)

data class MobileUsageHistoryUiState(
    val startDate: String = LocalDate.now().toString(),
    val endDate: String = LocalDate.now().toString(),
    val selectedDeviceId: String? = null,
    val devices: List<DeviceListDto> = emptyList(),
    val days: List<MobileUsageHistoryDay> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

internal fun mobileUsageHistoryQuery(
    startDate: String,
    endDate: String,
    deviceId: String?,
): MobileUsageHistoryQuery {
    val start = runCatching { LocalDate.parse(startDate) }.getOrElse {
        throw IllegalArgumentException("开始日期请使用 YYYY-MM-DD 格式。")
    }
    val end = runCatching { LocalDate.parse(endDate) }.getOrElse {
        throw IllegalArgumentException("结束日期请使用 YYYY-MM-DD 格式。")
    }
    if (end.isBefore(start)) throw IllegalArgumentException("结束日期不能早于开始日期。")
    val days = ChronoUnit.DAYS.between(start, end) + 1
    if (days > MOBILE_USAGE_HISTORY_MAX_DAYS) {
        throw IllegalArgumentException("单次手机使用查询最多覆盖 7 天。")
    }
    return MobileUsageHistoryQuery(
        dates = (0 until days).map(start::plusDays),
        deviceId = deviceId,
    )
}

class MobileUsageHistoryRepository(
    private val transport: NativeWorkbenchTransport,
) {
    suspend fun devices(session: NativeWorkbenchSession): List<DeviceListDto> =
        withSession(session) { api ->
            val response = api.getManagedDevices()
            requireData(response.code, response.data, "读取设备列表")
        }

    suspend fun loadDay(
        session: NativeWorkbenchSession,
        date: LocalDate,
        deviceId: String?,
        page: Int = 1,
        existingSummary: MobileUsageSummaryResponse? = null,
    ): MobileUsageHistoryDay {
        require(page in 1..MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY) {
            "单日手机使用详情最多读取 $MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY 页。"
        }
        val summary = existingSummary ?: withSession(session) { api ->
            val response = api.getMobileSummary(date = date.toString(), deviceId = deviceId)
            val summary = requireData(response.code, response.data, "读取手机使用摘要")
            requireResponseDate(summary.date, date, "手机使用摘要")
            if (deviceId != null && summary.deviceId != deviceId) {
                throw IllegalStateException("读取手机使用摘要失败（服务端设备与筛选条件不一致）。")
            }
            summary
        }
        val timeline = withSession(session) { api ->
            val response = api.getMobileTimeline(
                date = date.toString(),
                deviceId = deviceId,
                page = page,
                pageSize = MOBILE_USAGE_HISTORY_PAGE_SIZE,
            )
            val timeline = requireData(response.code, response.data, "读取手机使用时间线")
            requireResponseDate(timeline.date, date, "手机使用时间线")
            if (timeline.page != null && timeline.page != page) {
                throw IllegalStateException("读取手机使用时间线失败（服务端页码与请求不一致）。")
            }
            if (deviceId != null && timeline.deviceId != deviceId) {
                throw IllegalStateException("读取手机使用时间线失败（服务端设备与筛选条件不一致）。")
            }
            if (timeline.items.size > MOBILE_USAGE_HISTORY_PAGE_SIZE) {
                throw IllegalStateException("读取手机使用时间线失败（单页记录超过客户端限制）。")
            }
            timeline.copy(
                hasMore = timeline.hasMore == true || timeline.truncated == true,
                truncated = timeline.hasMore == true || timeline.truncated == true,
            )
        }
        return MobileUsageHistoryDay(date, summary, timeline)
    }

    private fun <T> requireData(code: Int, data: T?, operation: String): T {
        if (code != 0) throw IllegalStateException("$operation失败（服务端代码 $code）。")
        return data ?: throw IllegalStateException("$operation失败（服务端未返回数据）。")
    }

    private fun requireResponseDate(value: String, requested: LocalDate, operation: String) {
        if (runCatching { LocalDate.parse(value) }.getOrNull() != requested) {
            throw IllegalStateException("读取$operation失败（服务端日期与请求不一致）。")
        }
    }

    private suspend fun <T> withSession(
        session: NativeWorkbenchSession,
        block: suspend (ApiService) -> T,
    ): T {
        if (!transport.isCurrent(session)) throw UploadSessionChangedException()
        val result = block(transport.apiFor(session))
        if (!transport.isCurrent(session)) throw UploadSessionChangedException()
        return result
    }
}

@HiltViewModel
class MobileUsageHistoryViewModel @Inject constructor(
    private val transport: NativeWorkbenchTransport,
) : ViewModel() {
    private val repository = MobileUsageHistoryRepository(transport)
    private val mutableState = MutableStateFlow(MobileUsageHistoryUiState())
    val state: StateFlow<MobileUsageHistoryUiState> = mutableState.asStateFlow()

    private var requestGeneration = 0L
    private var loadedSession: NativeWorkbenchSession? = null
    private var queryJob: Job? = null

    fun setStartDate(value: String) = updateFilter { copy(startDate = value) }

    fun setEndDate(value: String) = updateFilter { copy(endDate = value) }

    fun setDevice(deviceId: String?) {
        if (mutableState.value.selectedDeviceId == deviceId) return
        updateFilter { copy(selectedDeviceId = deviceId) }
    }

    fun refresh() {
        queryJob?.cancel()
        val generation = ++requestGeneration
        var current = mutableState.value
        val session = transport.currentSession()
        if (session == null) {
            clearAccountData(current)
            mutableState.value = mutableState.value.copy(message = "请先登录，再读取此账号的手机使用记录。")
            return
        }
        if (loadedSession != null && loadedSession != session) {
            clearAccountData(current)
            current = mutableState.value
        }
        val query = try {
            mobileUsageHistoryQuery(current.startDate, current.endDate, current.selectedDeviceId)
        } catch (error: IllegalArgumentException) {
            mutableState.value = current.copy(loading = false, error = error.message, days = emptyList())
            return
        }
        loadedSession = session
        mutableState.value = current.copy(loading = true, error = null, message = null, days = emptyList())
        queryJob = viewModelScope.launch {
            try {
                val devices = repository.devices(session)
                if (!isCurrent(generation, session)) {
                    clearForSessionChange(generation, session)
                    return@launch
                }
                val days = mutableListOf<MobileUsageHistoryDay>()
                for (date in query.dates) {
                    days += repository.loadDay(session, date, query.deviceId)
                    if (!isCurrent(generation, session)) {
                        clearForSessionChange(generation, session)
                        return@launch
                    }
                }
                mutableState.value = mutableState.value.copy(
                    devices = devices,
                    days = days,
                    loading = false,
                    error = null,
                    message = if (days.all { it.summary.appsUsed == 0 && it.timeline.items.isEmpty() }) {
                        "所选日期与设备暂无手机使用摘要。"
                    } else {
                        null
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation != requestGeneration) return@launch
                if (!transport.isCurrent(session)) {
                    clearForSessionChange(generation, session)
                } else {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = "读取手机使用记录失败；可能存在服务端拒绝或缺少摘要数据，请检查后重试。",
                    )
                }
            }
        }
    }

    fun loadMore(date: LocalDate) {
        val current = mutableState.value
        val session = transport.currentSession()
        if (session == null || loadedSession != session) {
            queryJob?.cancel()
            requestGeneration++
            clearAccountData(current)
            mutableState.value = mutableState.value.copy(
                message = "账号、服务器或登录会话已变化；旧手机使用数据已清除，请重新查询。",
            )
            return
        }
        val day = current.days.firstOrNull { it.date == date } ?: return
        val nextPage = (day.timeline.page ?: 1) + 1
        if (current.days.any { it.loadingMore } ||
            day.timeline.hasMore != true ||
            nextPage > MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY
        ) {
            return
        }
        val generation = ++requestGeneration
        queryJob?.cancel()
        mutableState.value = current.copy(
            days = current.days.map { if (it.date == date) it.copy(loadingMore = true) else it },
            error = null,
            message = null,
        )
        queryJob = viewModelScope.launch {
            try {
                val next = repository.loadDay(
                    session = session,
                    date = date,
                    deviceId = current.selectedDeviceId,
                    page = nextPage,
                    existingSummary = day.summary,
                )
                if (!isCurrent(generation, session)) {
                    clearForSessionChange(generation, session)
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    days = mutableState.value.days.map { existing ->
                        if (existing.date != date) existing else existing.copy(
                            timeline = next.timeline.copy(
                                items = existing.timeline.items + next.timeline.items,
                                sessions = existing.timeline.sessions + next.timeline.sessions,
                                fallbackSummaries = existing.timeline.fallbackSummaries + next.timeline.fallbackSummaries,
                            ),
                            loadingMore = false,
                        )
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation != requestGeneration) return@launch
                if (!transport.isCurrent(session)) {
                    clearForSessionChange(generation, session)
                } else {
                    mutableState.value = mutableState.value.copy(
                        days = mutableState.value.days.map { if (it.date == date) it.copy(loadingMore = false) else it },
                        error = "读取更多手机使用记录失败；当前已加载内容仍保留，请重试。",
                    )
                }
            }
        }
    }

    private fun updateFilter(change: MobileUsageHistoryUiState.() -> MobileUsageHistoryUiState) {
        queryJob?.cancel()
        requestGeneration++
        mutableState.value = mutableState.value.change().copy(
            days = emptyList(),
            loading = false,
            error = null,
            message = "筛选条件已更改，请重新查询。",
        )
    }

    private fun clearAccountData(current: MobileUsageHistoryUiState) {
        loadedSession = null
        mutableState.value = MobileUsageHistoryUiState(
            startDate = current.startDate,
            endDate = current.endDate,
        )
    }

    private fun isCurrent(generation: Long, session: NativeWorkbenchSession): Boolean =
        generation == requestGeneration && transport.isCurrent(session)

    private fun clearForSessionChange(generation: Long, session: NativeWorkbenchSession) {
        if (generation != requestGeneration || transport.isCurrent(session)) return
        clearAccountData(mutableState.value)
        mutableState.value = mutableState.value.copy(
            loading = false,
            message = "账号、服务器或登录会话已变化；旧手机使用数据已清除，请重新查询。",
        )
    }
}

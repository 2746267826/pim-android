package com.pim.app.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pim.app.nativework.NativeWorkbenchSession
import com.pim.app.nativework.NativeWorkbenchTransport
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.MobileLocationPointDto
import com.pim.core.contract.MobileLocationSegmentDto
import com.pim.core.contract.MobileLocationTrackDto
import com.pim.core.network.ApiService
import com.pim.core.network.UploadSessionChangedException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val LOCATION_HISTORY_MAX_RANGE_DAYS = 7L
internal const val LOCATION_HISTORY_MAX_RENDERED_TRACKS = 50
internal const val LOCATION_HISTORY_POINTS_PAGE_SIZE = 100
internal const val LOCATION_HISTORY_MAX_DETAIL_POINTS = 500
internal const val LOCATION_HISTORY_MAX_ACCURACY_METERS = 50.0

data class LocationHistoryQuery(
    val startUtc: String,
    val endUtc: String,
    val deviceId: String?,
    val timeZone: String,
)

data class LocationHistoryUiState(
    val startDate: String = LocalDate.now().toString(),
    val endDate: String = LocalDate.now().toString(),
    val selectedDeviceId: String? = null,
    val devices: List<DeviceListDto> = emptyList(),
    val tracks: List<MobileLocationTrackDto> = emptyList(),
    val totalTrackCount: Int = 0,
    val query: LocationHistoryQuery? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val selectedTrack: MobileLocationTrackDto? = null,
    val selectedSegment: MobileLocationSegmentDto? = null,
    val detailPoints: List<MobileLocationPointDto> = emptyList(),
    val detailHasMore: Boolean = false,
    val detailNextCursor: String? = null,
    val detailLoading: Boolean = false,
)

internal fun locationHistoryQuery(
    startDate: String,
    endDate: String,
    deviceId: String?,
    zoneId: ZoneId,
): LocationHistoryQuery {
    val start = runCatching { LocalDate.parse(startDate) }.getOrElse {
        throw IllegalArgumentException("开始日期请使用 YYYY-MM-DD 格式。")
    }
    val end = runCatching { LocalDate.parse(endDate) }.getOrElse {
        throw IllegalArgumentException("结束日期请使用 YYYY-MM-DD 格式。")
    }
    if (end.isBefore(start)) throw IllegalArgumentException("结束日期不能早于开始日期。")
    val days = ChronoUnit.DAYS.between(start, end) + 1
    if (days > LOCATION_HISTORY_MAX_RANGE_DAYS) {
        throw IllegalArgumentException("单次轨迹查询最多覆盖 7 天。")
    }
    return LocationHistoryQuery(
        startUtc = start.atStartOfDay(zoneId).toInstant().toString(),
        endUtc = end.plusDays(1).atStartOfDay(zoneId).toInstant().toString(),
        deviceId = deviceId,
        timeZone = zoneId.id,
    )
}

class LocationHistoryRepository(
    private val transport: NativeWorkbenchTransport,
) {
    suspend fun devices(session: NativeWorkbenchSession): List<DeviceListDto> =
        withSession(session) { api ->
            val response = api.getManagedDevices()
            requireData(response.code, response.message, response.data, "读取设备列表")
        }

    suspend fun tracks(
        session: NativeWorkbenchSession,
        query: LocationHistoryQuery,
    ): List<MobileLocationTrackDto> = withSession(session) { api ->
        val response = api.getMobileLocationTracks(
            rangeStartUtc = query.startUtc,
            rangeEndUtc = query.endUtc,
            deviceId = query.deviceId,
            maxAccuracyMeters = LOCATION_HISTORY_MAX_ACCURACY_METERS,
        )
        requireData(response.code, response.message, response.data, "读取位置轨迹")
    }

    suspend fun segmentPoints(
        session: NativeWorkbenchSession,
        query: LocationHistoryQuery,
        segmentId: String,
        cursor: String?,
        pageSize: Int,
    ) = withSession(session) { api ->
        val response = api.getMobileLocationSegmentPoints(
            segmentId = segmentId,
            rangeStartUtc = query.startUtc,
            rangeEndUtc = query.endUtc,
            timezone = query.timeZone,
            deviceId = query.deviceId,
            maxAccuracyMeters = LOCATION_HISTORY_MAX_ACCURACY_METERS,
            includeRejected = false,
            cursor = cursor,
            pageSize = pageSize,
        )
        requireData(response.code, response.message, response.data, "读取轨迹点")
    }

    private fun <T> requireData(code: Int, message: String, data: T?, operation: String): T {
        if (code != 0) {
            throw IllegalStateException(
                "${operation}失败：${message.trim().takeIf { it.isNotEmpty() } ?: "服务端拒绝请求"}"
            )
        }
        return data ?: throw IllegalStateException("${operation}失败：服务端未返回数据")
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
class LocationHistoryViewModel @Inject constructor(
    private val transport: NativeWorkbenchTransport,
) : ViewModel() {
    private val repository = LocationHistoryRepository(transport)
    private val mutableState = MutableStateFlow(LocationHistoryUiState())
    val state: StateFlow<LocationHistoryUiState> = mutableState.asStateFlow()

    private var requestGeneration = 0L
    private var detailGeneration = 0L
    private var loadedSession: NativeWorkbenchSession? = null
    private var queryJob: Job? = null
    private var detailJob: Job? = null

    fun setStartDate(value: String) = updateFilter { copy(startDate = value) }

    fun setEndDate(value: String) = updateFilter { copy(endDate = value) }

    fun setDevice(deviceId: String?) {
        if (mutableState.value.selectedDeviceId == deviceId) return
        updateFilter { copy(selectedDeviceId = deviceId) }
    }

    fun refresh() {
        queryJob?.cancel()
        detailJob?.cancel()
        detailGeneration++
        val generation = ++requestGeneration
        var current = mutableState.value
        val session = transport.currentSession()
        if (session == null) {
            clearAccountData(current)
            mutableState.value = mutableState.value.copy(
                message = "请先登录，再读取此账号的位置历史。",
            )
            return
        }
        if (loadedSession != null && loadedSession != session) {
            clearAccountData(current)
            current = mutableState.value
        }
        val query = try {
            locationHistoryQuery(
                current.startDate,
                current.endDate,
                current.selectedDeviceId,
                ZoneId.systemDefault(),
            )
        } catch (error: IllegalArgumentException) {
            mutableState.value = current.copy(
                loading = false,
                error = error.message,
                tracks = emptyList(),
                query = null,
                selectedTrack = null,
                selectedSegment = null,
                detailPoints = emptyList(),
                detailHasMore = false,
                detailNextCursor = null,
                detailLoading = false,
            )
            return
        }
        loadedSession = session
        mutableState.value = mutableState.value.copy(
            loading = true,
            error = null,
            message = null,
            query = query,
            tracks = emptyList(),
            selectedTrack = null,
            selectedSegment = null,
            detailPoints = emptyList(),
            detailHasMore = false,
            detailNextCursor = null,
            detailLoading = false,
        )
        queryJob = viewModelScope.launch {
            try {
                val devices = repository.devices(session)
                if (!isCurrent(generation, session)) {
                    clearForSessionChange(generation, session)
                    return@launch
                }
                val tracks = repository.tracks(session, query)
                if (!isCurrent(generation, session)) {
                    clearForSessionChange(generation, session)
                    return@launch
                }
                loadedSession = session
                mutableState.value = mutableState.value.copy(
                    devices = devices,
                    tracks = tracks.take(LOCATION_HISTORY_MAX_RENDERED_TRACKS),
                    totalTrackCount = tracks.size,
                    loading = false,
                    error = null,
                    message = if (tracks.isEmpty()) "所选日期与设备暂无可展示轨迹。" else null,
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
                        error = "读取位置历史失败，请检查网络与服务端状态后重试。",
                    )
                }
            }
        }
    }

    fun openSegment(track: MobileLocationTrackDto, segment: MobileLocationSegmentDto) {
        val session = loadedSession
        val query = mutableState.value.query
        if (session == null || query == null || !transport.isCurrent(session)) {
            refresh()
            return
        }
        detailJob?.cancel()
        val generation = ++detailGeneration
        val expectedRequestGeneration = requestGeneration
        mutableState.value = mutableState.value.copy(
            selectedTrack = track,
            selectedSegment = segment,
            detailPoints = emptyList(),
            detailNextCursor = null,
            detailHasMore = false,
            detailLoading = true,
            error = null,
        )
        detailJob = viewModelScope.launch {
            loadPage(
                session,
                query,
                segment,
                cursor = null,
                append = false,
                pageSize = LOCATION_HISTORY_POINTS_PAGE_SIZE,
                generation,
                expectedRequestGeneration,
            )
        }
    }

    fun loadNextPointsPage() {
        val session = loadedSession ?: return
        val query = mutableState.value.query ?: return
        val segment = mutableState.value.selectedSegment ?: return
        val cursor = mutableState.value.detailNextCursor ?: return
        if (mutableState.value.detailLoading || !mutableState.value.detailHasMore) return
        val remaining = LOCATION_HISTORY_MAX_DETAIL_POINTS - mutableState.value.detailPoints.size
        if (remaining <= 0) return
        val generation = ++detailGeneration
        val expectedRequestGeneration = requestGeneration
        mutableState.value = mutableState.value.copy(detailLoading = true, error = null)
        detailJob = viewModelScope.launch {
            loadPage(
                session,
                query,
                segment,
                cursor,
                append = true,
                pageSize = minOf(LOCATION_HISTORY_POINTS_PAGE_SIZE, remaining),
                generation,
                expectedRequestGeneration,
            )
        }
    }

    private suspend fun loadPage(
        session: NativeWorkbenchSession,
        query: LocationHistoryQuery,
        segment: MobileLocationSegmentDto,
        cursor: String?,
        append: Boolean,
        pageSize: Int,
        generation: Long,
        expectedRequestGeneration: Long,
    ) {
        try {
            val page = repository.segmentPoints(session, query, segment.id, cursor, pageSize)
            if (generation != detailGeneration || expectedRequestGeneration != requestGeneration) return
            if (!transport.isCurrent(session)) {
                clearForSessionChange(expectedRequestGeneration, session)
                return
            }
            val existing = if (append) mutableState.value.detailPoints else emptyList()
            val remaining = LOCATION_HISTORY_MAX_DETAIL_POINTS - existing.size
            val incoming = page.items.take(remaining)
            mutableState.value = mutableState.value.copy(
                detailPoints = (existing + incoming).distinctBy { it.id },
                detailNextCursor = page.nextCursor,
                detailHasMore = page.hasMore && !page.nextCursor.isNullOrBlank() &&
                    existing.size + incoming.size < LOCATION_HISTORY_MAX_DETAIL_POINTS,
                detailLoading = false,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (generation != detailGeneration || expectedRequestGeneration != requestGeneration) return
            if (!transport.isCurrent(session)) {
                clearForSessionChange(expectedRequestGeneration, session)
            } else {
                mutableState.value = mutableState.value.copy(
                    detailLoading = false,
                    error = "读取轨迹点失败，请重试。",
                )
            }
        }
    }

    private fun updateFilter(change: LocationHistoryUiState.() -> LocationHistoryUiState) {
        queryJob?.cancel()
        detailJob?.cancel()
        requestGeneration++
        detailGeneration++
        mutableState.value = mutableState.value.change().copy(
            tracks = emptyList(),
            totalTrackCount = 0,
            query = null,
            loading = false,
            error = null,
            message = "筛选条件已更改，请重新查询。",
            selectedTrack = null,
            selectedSegment = null,
            detailPoints = emptyList(),
            detailHasMore = false,
            detailNextCursor = null,
            detailLoading = false,
        )
    }

    private fun clearAccountData(current: LocationHistoryUiState) {
        loadedSession = null
        mutableState.value = LocationHistoryUiState(
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
            detailLoading = false,
            message = "账号、服务器或登录会话已变化；旧位置数据已清除，请重新查询。",
        )
    }
}

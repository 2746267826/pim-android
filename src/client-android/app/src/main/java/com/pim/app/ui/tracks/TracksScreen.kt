package com.pim.app.ui.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pim.app.ui.shell.PimWebViewScreen
import com.pim.app.ui.today.TodayViewModel
import com.pim.core.contract.MobileLocationPathPointDto
import com.pim.core.contract.MobileLocationSegmentDto
import com.pim.core.contract.MobileLocationTrackDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TracksScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    savedUrl: String? = null,
    onUrlChanged: ((String) -> Unit)? = null,
    viewModel: TodayViewModel = hiltViewModel(),
    historyViewModel: LocationHistoryViewModel = hiltViewModel(),
    usageHistoryViewModel: MobileUsageHistoryViewModel = hiltViewModel(),
) {
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    val usageHistoryState by usageHistoryViewModel.state.collectAsStateWithLifecycle()
    val todayState by viewModel.state.collectAsStateWithLifecycle()
    val refreshVersion by viewModel.refreshVersion.collectAsStateWithLifecycle()
    var showLegacy by remember { mutableStateOf(false) }
    var showUsageHistory by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, historyViewModel, usageHistoryViewModel, showUsageHistory) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (showUsageHistory) usageHistoryViewModel.refresh() else historyViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            if (showUsageHistory) usageHistoryViewModel.refresh() else historyViewModel.refresh()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (showLegacy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showLegacy = false }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Text("返回原生轨迹查询")
                }
                if (todayState.embedSupported == false) {
                    TextButton(onClick = onOpenSettings) { Text("打开设置") }
                }
            }
            if (todayState.embedSupported == false) {
                Text("此服务器不支持旧版轨迹页，请使用上方原生轨迹查询。", Modifier.padding(16.dp))
            } else {
                PimWebViewScreen(
                    route = "/embed/android/tracks",
                    serverUrl = viewModel.serverUrl,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    bridge = viewModel.bridge,
                    reloadKey = refreshVersion,
                    initialUrl = savedUrl,
                    onUrlChanged = onUrlChanged,
                    okHttpClient = viewModel.embedOkHttpClient,
                    authSessionStore = viewModel.embedSessionStore,
                    settingsStore = viewModel.embedSettingsStore,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (showUsageHistory) "手机使用历史" else "位置历史",
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(
                    onClick = { showUsageHistory = !showUsageHistory },
                    modifier = Modifier.testTag("mobile-history-mode-toggle"),
                ) {
                    Text(if (showUsageHistory) "查看位置历史" else "查看手机使用")
                }
            }
            if (showUsageHistory) {
                MobileUsageHistoryContent(
                    modifier = Modifier.weight(1f),
                    state = usageHistoryState,
                    onStartDateChange = usageHistoryViewModel::setStartDate,
                    onEndDateChange = usageHistoryViewModel::setEndDate,
                    onDeviceChange = usageHistoryViewModel::setDevice,
                    onSearch = usageHistoryViewModel::refresh,
                    onLoadMore = usageHistoryViewModel::loadMore,
                )
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = historyState.startDate,
                    onValueChange = historyViewModel::setStartDate,
                    label = { Text("开始日期") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("location-history-start-date"),
                )
                OutlinedTextField(
                    value = historyState.endDate,
                    onValueChange = historyViewModel::setEndDate,
                    label = { Text("结束日期") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("location-history-end-date"),
                )
            }
            DeviceFilter(
                selectedDeviceId = historyState.selectedDeviceId,
                devices = historyState.devices,
                onSelect = historyViewModel::setDevice,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = historyViewModel::refresh, modifier = Modifier.testTag("location-history-search")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("查询（最多 7 天）")
                }
                TextButton(onClick = { showLegacy = true }) { Text("打开旧版完整轨迹页") }
            }
            historyState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            historyState.message?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (historyState.loading) CircularProgressIndicator(Modifier.padding(16.dp))
            if (historyState.totalTrackCount > historyState.tracks.size) {
                Text(
                    "共 ${historyState.totalTrackCount} 条，仅显示前 ${historyState.tracks.size} 条以限制内存。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(historyState.tracks, key = { it.id }) { track ->
                    TrackCard(track, historyState.selectedTrack?.id == track.id, historyViewModel::openSegment)
                }
                historyState.selectedSegment?.let { segment ->
                    item(key = "detail-${segment.id}") {
                        SegmentDetail(
                            segment = segment,
                            points = historyState.detailPoints,
                            loading = historyState.detailLoading,
                            hasMore = historyState.detailHasMore,
                            onLoadMore = historyViewModel::loadNextPointsPage,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceFilter(
    selectedDeviceId: String?,
    devices: List<com.pim.core.contract.DeviceListDto>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = devices.firstOrNull { it.deviceId == selectedDeviceId }?.displayName ?: "全部设备"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("location-history-device-filter")) {
            Text("设备：$selectedName")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部设备") }, onClick = { onSelect(null); expanded = false })
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.displayName.ifBlank { device.model }) },
                    onClick = { onSelect(device.deviceId); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: MobileLocationTrackDto,
    selected: Boolean,
    onOpenSegment: (MobileLocationTrackDto, MobileLocationSegmentDto) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("location-track-${track.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${formatUtc(track.startUtc)} - ${formatUtc(track.endUtc)}", fontWeight = FontWeight.SemiBold)
            Text("距离 ${formatDistance(track.distanceMeters)} · ${track.durationSeconds / 60} 分钟 · ${track.pointCount} 点")
            track.segments.forEach { segment ->
                TextButton(onClick = { onOpenSegment(track, segment) }) {
                    Text(if (selected) "查看此轨迹段：${segment.kind}" else "查看轨迹段：${segment.kind}")
                }
            }
        }
    }
}

@Composable
private fun SegmentDetail(
    segment: MobileLocationSegmentDto,
    points: List<com.pim.core.contract.MobileLocationPointDto>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("轨迹段详情", style = MaterialTheme.typography.titleMedium)
        Text("${segment.localStart} - ${segment.localEnd} · ${formatDistance(segment.distanceMeters)}")
        Text("轨迹示意：${coordinateSummary(segment.path)}", style = MaterialTheme.typography.bodySmall)
        points.forEach { point ->
            Text(
                "${formatUtc(point.recordedAtUtc)}  ${"%.5f".format(Locale.ROOT, point.latitude)}, ${"%.5f".format(Locale.ROOT, point.longitude)}  精度 ${point.horizontalAccuracyMeters.toInt()} 米",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (loading) CircularProgressIndicator()
        if (hasMore) Button(onClick = onLoadMore, enabled = !loading) { Text("加载下一页坐标（每页 100 点）") }
        if (!hasMore && points.isNotEmpty()) Text("已显示 ${points.size} 个坐标点。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun coordinateSummary(path: List<MobileLocationPathPointDto>): String =
    if (path.isEmpty()) "服务端未提供轨迹简化点"
    else path.take(8).joinToString(" → ") {
        "${"%.4f".format(Locale.ROOT, it.latitude)},${"%.4f".format(Locale.ROOT, it.longitude)}"
    } + if (path.size > 8) " …（共 ${path.size} 点）" else ""

private fun formatUtc(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "%.2f 公里".format(Locale.ROOT, meters / 1000) else "${meters.toInt()} 米"

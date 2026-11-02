package com.pim.app.ui.tracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pim.core.contract.DeviceListDto
import com.pim.core.contract.MobileAppUsageSummaryDto
import com.pim.core.contract.MobileTimelineItemDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MobileUsageHistoryContent(
    modifier: Modifier = Modifier,
    state: MobileUsageHistoryUiState,
    onStartDateChange: (String) -> Unit,
    onEndDateChange: (String) -> Unit,
    onDeviceChange: (String?) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: (java.time.LocalDate) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.startDate,
                onValueChange = onStartDateChange,
                label = { Text("开始日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("mobile-usage-start-date"),
            )
            OutlinedTextField(
                value = state.endDate,
                onValueChange = onEndDateChange,
                label = { Text("结束日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("mobile-usage-end-date"),
            )
        }
        DeviceFilter(
            selectedDeviceId = state.selectedDeviceId,
            devices = state.devices,
            onSelect = onDeviceChange,
        )
        Button(onClick = onSearch, enabled = !state.loading, modifier = Modifier.testTag("mobile-usage-search")) {
            Text("查询手机使用（最多 7 天）")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        state.message?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }
        if (state.loading) CircularProgressIndicator(Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.days, key = { it.date.toString() }) { day ->
                val summary = day.summary
                Card(Modifier.fillMaxWidth().testTag("mobile-usage-day-${day.date}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(day.date.toString(), fontWeight = FontWeight.SemiBold)
                        Text(
                            "前台 ${formatUsageDuration(summary.totalForegroundSeconds)} · " +
                                "${summary.appsUsed} 个应用 · ${summary.appSwitchCount} 次切换",
                        )
                        Text(
                            "完整度 ${(summary.completeness * 100).toInt()}% · " +
                                "质量异常 ${summary.qualityIssueCount} · " +
                                "同步批次 ${summary.syncBatches.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        summary.appRanking.take(MOBILE_USAGE_HISTORY_MAX_APPS_PER_DAY).forEachIndexed { index, app ->
                            UsageAppRow(index + 1, app)
                        }
                        if (summary.appRanking.size > MOBILE_USAGE_HISTORY_MAX_APPS_PER_DAY) {
                            Text(
                                "应用排名仅显示前 $MOBILE_USAGE_HISTORY_MAX_APPS_PER_DAY 项。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "活动记录（第 ${day.timeline.page ?: 1} 页，共 ${day.timeline.totalCount ?: day.timeline.items.size} 条）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (day.timeline.items.isEmpty()) {
                            Text("此日期没有返回手机活动时间线记录。")
                        }
                        day.timeline.items.forEach { item ->
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("mobile-usage-item-${item.id}"),
                            ) {
                                Text("${item.displayName.ifBlank { item.packageName }} · ${item.kindLabel()}")
                                Text(
                                    "${formatUsageTimestamp(item.start)} · ${formatUsageDuration(item.durationSeconds)} · ${item.source}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    modifier = Modifier.testTag("mobile-usage-details-${item.id}"),
                                ) {
                                    Text(if (expanded) "收起详情" else "查看详情")
                                }
                                if (expanded) {
                                    Text("应用包：${item.packageName}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "设备：${state.devices.firstOrNull { it.deviceId == item.deviceId }?.displayName ?: "所选设备"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    item.end?.let {
                                        Text("结束：${formatUsageTimestamp(it)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("时长：${formatUsageDuration(item.durationSeconds)}", style = MaterialTheme.typography.bodySmall)
                                    Text("来源：${item.source} · 置信度 ${(item.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                    if (item.reason.isNotBlank()) {
                                        Text("说明：${item.reason}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        val page = day.timeline.page ?: 1
                        if (day.timeline.hasMore == true && page < MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY) {
                            TextButton(
                                onClick = { onLoadMore(day.date) },
                                enabled = state.days.none { it.loadingMore },
                                modifier = Modifier.testTag("mobile-usage-load-more-${day.date}"),
                            ) {
                                Text(if (day.loadingMore) "正在读取…" else "读取更多活动记录")
                            }
                        } else if (day.timeline.hasMore == true) {
                            Text(
                                "客户端单日最多加载 ${MOBILE_USAGE_HISTORY_MAX_PAGES_PER_DAY * MOBILE_USAGE_HISTORY_PAGE_SIZE} 条；服务端仍有更多记录，请缩小日期或设备范围。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceFilter(
    selectedDeviceId: String?,
    devices: List<DeviceListDto>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = devices.firstOrNull { it.deviceId == selectedDeviceId }?.displayName ?: "全部设备"
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("mobile-usage-device-filter")) {
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
private fun UsageAppRow(rank: Int, app: MobileAppUsageSummaryDto) {
    val lastUsed = app.lastUsedAt?.let(::formatUsageTimestamp) ?: "时间未知"
    Text(
        "$rank. ${app.displayName} · ${formatUsageDuration(app.foregroundSeconds)} · " +
            "启动 ${app.launchCount} 次 · 最近 $lastUsed",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatUsageDuration(seconds: Long): String {
    val duration = seconds.coerceAtLeast(0)
    val hours = duration / 3600
    val minutes = duration % 3600 / 60
    val remainingSeconds = duration % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${remainingSeconds}秒"
        else -> "${remainingSeconds}秒"
    }
}

private fun formatUsageTimestamp(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)

private fun MobileTimelineItemDto.kindLabel(): String = when (kind) {
    "session" -> "事件会话"
    "fallback" -> "回退汇总"
    else -> "手机活动"
}

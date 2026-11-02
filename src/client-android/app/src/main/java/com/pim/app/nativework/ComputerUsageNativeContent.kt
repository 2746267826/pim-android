@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pim.app.nativework

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ComputerUsageNativeContent(
    state: NativeWorkbenchUiState,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPageChange: (Int) -> Unit,
    onSelectRecord: (Int?) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("电脑活动记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("明细默认包含应用窗口与浏览器活动；URL 仅显示文本，不会自动打开。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.computerUsageFrom,
                onValueChange = onFromChange,
                modifier = Modifier.weight(1f).testTag("computer-usage-date-from"),
                label = { Text("开始日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.computerUsageTo,
                onValueChange = onToChange,
                modifier = Modifier.weight(1f).testTag("computer-usage-date-to"),
                label = { Text("结束日期") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
            )
        }
        Text("明细设备", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.computerUsageDeviceId.isBlank(),
                onClick = { onDeviceIdChange("") },
                label = { Text("全部设备") },
                modifier = Modifier.testTag("computer-device-all"),
            )
            state.computerUsageDevices.forEach { device ->
                FilterChip(
                    selected = state.computerUsageDeviceId == device.deviceId,
                    onClick = { onDeviceIdChange(device.deviceId) },
                    label = { Text(device.displayName.ifBlank { device.deviceId }) },
                    modifier = Modifier.testTag("computer-device-${device.deviceId}"),
                )
            }
        }
        Button(onClick = onSearch, enabled = !state.loading, modifier = Modifier.testTag("computer-usage-search")) {
            Text("查询")
        }

        val data = state.computerUsage
        if (data == null) {
            if (!state.loading && state.error == null) Text("选择日期后查询电脑应用与浏览器活动。")
            return@Column
        }
        Text("浏览器统计（全设备）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("摘要、每日站点与时间线均按所选日期范围统计所有设备；设备筛选只作用于下方明细。", style = MaterialTheme.typography.bodySmall)
        Text("${data.summary.from} 至 ${data.summary.to} · ${data.summary.siteCount} 个站点")
        Text("全设备：访问 ${data.summary.totalVisits} 次 · 活跃 ${formatDuration(data.summary.totalFocusMs)} · 媒体 ${formatDuration(data.summary.totalMediaMs)}")

        Text("每日站点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (data.daily.isEmpty()) Text("所选日期范围没有浏览器统计。")
        data.daily.forEach { row ->
            Text("${row.date} · ${row.host} · ${row.visitCount} 次 · ${formatDuration(row.focusMs)} 活跃")
        }

        Text("${state.computerUsageFrom} 时间线（仅开始日，全设备）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (data.timeline.isEmpty()) Text("这一天没有浏览器时间线记录。")
        data.timeline.forEach { row ->
            Text("${formatInstant(row.startMs)} · ${row.host} · ${formatDuration(row.durationMs)}")
        }

        Text("电脑明细（全部类型）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("${data.records.totalCount} 条记录 · 第 ${data.records.page} / ${data.records.totalPages.coerceAtLeast(1)} 页")
        if (data.records.items.isEmpty()) Text("当前筛选条件没有电脑活动明细。")
        data.records.items.forEachIndexed { index, record ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    onSelectRecord(if (state.selectedComputerRecordIndex == index) null else index)
                }.testTag("computer-record-${record.deviceId}-${record.start}"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        record.title?.takeIf(String::isNotBlank)
                            ?: record.domain
                            ?: record.displayName
                            ?: record.appName
                            ?: record.browserAppName
                            ?: "电脑活动",
                        fontWeight = FontWeight.Medium,
                    )
                    Text("${record.start} · ${record.deviceId}", style = MaterialTheme.typography.bodySmall)
                    if (state.selectedComputerRecordIndex == index) {
                        record.appName?.let { Text("应用：$it") }
                        record.domain?.let { Text("域名：$it") }
                        record.browserAppName?.let { Text("浏览器：$it") }
                        record.browserWindowTitle?.let { Text("窗口：$it") }
                        record.url?.let { Text("URL：$it", style = MaterialTheme.typography.bodySmall) }
                        record.durationSeconds?.let { Text("时长：${formatDuration((it * 1000).toLong())}") }
                        Text("来源：${record.recordType} · ${record.sourceType.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                onClick = { onPageChange(data.records.page - 1) },
                enabled = data.records.page > 1 && !state.loading,
                modifier = Modifier.testTag("computer-usage-previous"),
            ) { Text("上一页") }
            TextButton(
                onClick = { onPageChange(data.records.page + 1) },
                enabled = data.records.page < data.records.totalPages && !state.loading,
                modifier = Modifier.testTag("computer-usage-next"),
            ) { Text("下一页") }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
}

private fun formatInstant(milliseconds: Long): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(milliseconds))
}.getOrDefault("时间未知")

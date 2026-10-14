package com.pim.app.mute.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pim.app.ui.components.PimSection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置页「日程静音」分区（工单 04 E3.4 / E3.3）。
 *
 * 四项内容：设备级开关、勿扰访问权限状态与授权入口、精确闹钟权限状态、当前计划状态。
 * 文案为简体中文；未授权时**明确提示**而不是留空（AC-5：不允许静默失效）。
 */
@Composable
fun EventMuteSection(
    state: EventMuteSectionState,
    onToggleFollowRule: (Boolean) -> Unit,
    onOpenDoNotDisturbSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onRefreshPlan: () -> Unit,
    modifier: Modifier = Modifier
) {
    PimSection("日程静音", modifier = modifier.testTag("settings-event-mute")) {
        // ── 设备级开关（E3.4 / AC-7）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("本设备是否遵循该规则")
                Text(
                    text = if (state.followRuleEnabled) {
                        "已开启：日程期间自动静音，结束时恢复进入前的状态"
                    } else {
                        "已关闭：本机不改变铃声与免打扰，任何日程都不会影响它"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("event-mute-enabled-text")
                )
            }
            Switch(
                checked = state.followRuleEnabled,
                onCheckedChange = onToggleFollowRule,
                modifier = Modifier.testTag("event-mute-switch")
            )
        }

        Divider(Modifier.padding(vertical = 10.dp))

        // ── 勿扰访问权限（E3.3 / AC-5）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("勿扰访问权限")
                Text(
                    text = if (state.doNotDisturbAccessGranted) {
                        "已授权：可以切换静音"
                    } else {
                        "未授权：无法在日程期间静音，请先授权"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.doNotDisturbAccessGranted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.testTag("event-mute-dnd-hint")
                )
            }
            TextButton(
                onClick = onOpenDoNotDisturbSettings,
                modifier = Modifier.testTag("event-mute-dnd-settings")
            ) {
                Text(if (state.doNotDisturbAccessGranted) "查看" else "去授权")
            }
        }

        Divider(Modifier.padding(vertical = 10.dp))

        // ── 精确闹钟权限（E3.2 的降级入口）──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("精确闹钟权限")
                Text(
                    text = if (state.exactAlarmGranted) {
                        "已授权：可准点触发"
                    } else {
                        "未授权：日程静音无法准点生效（不会退回不精确的闹钟）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.exactAlarmGranted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
            TextButton(onClick = onOpenExactAlarmSettings) {
                Text(if (state.exactAlarmGranted) "查看" else "去授权")
            }
        }

        Divider(Modifier.padding(vertical = 10.dp))

        // ── 当前计划状态 ──
        Text(
            text = when {
                !state.followRuleEnabled -> "计划：已关闭"
                state.isMutingNow -> "当前正在按日程静音中"
                state.plannedAlarmCount > 0 -> "计划：已登记 ${state.plannedAlarmCount} 次触发"
                else -> "计划：暂无需要静音的日程"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("event-mute-plan-text")
        )
        state.nextAlarmAtUtcMillis?.let { next ->
            Text(
                text = "下一次生效时间：${formatLocal(next)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.lastError?.let { error ->
            Text(
                text = "提示：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("event-mute-error-text")
            )
        }

        TextButton(onClick = onRefreshPlan, modifier = Modifier.testTag("event-mute-refresh")) {
            Text("重新读取日程并重建计划")
        }
    }
}

private val LOCAL_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

internal fun formatLocal(utcMillis: Long): String =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneId.systemDefault()).format(LOCAL_TIME_FORMATTER)

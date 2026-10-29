package com.pim.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pim.app.mobile.sync.UsageOutboxRecoveryPreview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LegacyOutboxClaimDialog(
    preview: UsageOutboxRecoveryPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.testTag("legacy-outbox-claim-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("确认发送历史手机使用记录") },
        text = {
            Text(
                buildString {
                    append("当前账号：")
                    append(preview.ownerName?.takeIf { it.isNotBlank() } ?: preview.ownerId)
                    append("\n当前服务器：")
                    append(preview.serverIdentity)
                    append("\n待传记录：")
                    append(preview.pendingCount)
                    append(" 条")
                    append("\n时间范围：")
                    append(formatRange(preview.earliestUtc, preview.latestUtc))
                    append("\n\n确认后，升级前未绑定账号的历史 phone usage 将发送给上述账号和服务器。")
                    append("拒绝或身份变化时不会发送，本地记录会继续保留。")
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("legacy-outbox-claim-confirm")
            ) {
                Text("确认发送给此身份")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private val legacyRangeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatRange(earliestUtc: Long?, latestUtc: Long?): String {
    if (earliestUtc == null && latestUtc == null) return "未知"
    val start = earliestUtc?.let { legacyRangeFormatter.format(Instant.ofEpochMilli(it)) } ?: "未知"
    val end = latestUtc?.let { legacyRangeFormatter.format(Instant.ofEpochMilli(it)) } ?: "未知"
    return "$start 至 $end"
}

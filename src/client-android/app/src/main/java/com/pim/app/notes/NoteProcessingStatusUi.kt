package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pim.core.contract.NoteProcessingTaskKind

@Composable
internal fun NoteProcessingStatusDetails(
    tag: String,
    materialReceipt: String,
    localAsr: String,
    localDerived: String,
    server: NoteProcessingStatusView?,
    problem: String?,
    syncing: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "处理状态（独立于上传）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            noteProcessingMaterialLine(materialReceipt),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("note-processing-material-$tag"),
        )
        Text(
            localAsr,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("note-processing-asr-$tag"),
        )
        Text(
            localDerived,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("note-processing-derived-$tag"),
        )
        if (server != null) {
            Text(
                "服务器总状态：${noteProcessingStateLabel(server.state)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("note-processing-overall-$tag"),
            )
            Text(
                noteProcessingChainLine("转写（ASR）", server.asr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("note-processing-server-asr-$tag"),
            )
            Text(
                noteProcessingChainLine("整理（派生）", server.derived),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("note-processing-server-derived-$tag"),
            )
            server.pendingReason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "服务器等待原因：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            server.issues.forEach { issue ->
                Text(
                    "服务器提示${issue.task?.let { task -> "（${taskName(task)}）" }.orEmpty()}：${issue.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "服务器状态时间：${server.updatedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!syncing) {
            Text(
                "尚未读取服务器双链路状态；上传回执、转写和整理分别核对。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        problem?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(
            onClick = onRefresh,
            enabled = !syncing,
            modifier = Modifier.testTag("note-processing-refresh-$tag"),
        ) {
            Text(if (syncing) "正在读取服务器处理状态" else "刷新服务器处理状态")
        }
    }
}

private fun taskName(task: NoteProcessingTaskKind): String = when (task) {
    NoteProcessingTaskKind.ASR -> "转写"
    NoteProcessingTaskKind.DERIVED -> "整理"
}

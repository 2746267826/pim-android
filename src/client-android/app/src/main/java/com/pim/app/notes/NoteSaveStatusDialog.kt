package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pim.app.ui.theme.PimTheme

@Composable
internal fun NoteSaveStatusDialog(
    state: NoteSaveState,
    saveProblem: String?,
    latestUpload: NoteUploadJob?,
    onRetrySave: () -> Unit,
    onReview: () -> Unit,
    onOpenMaterials: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("note-save-status-dialog"),
        title = { Text("保存与上传状态") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("note-save-status-details"),
                verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceSmall),
            ) {
                Text(
                    "本机保存：${noteSaveStateLabel(state)}",
                    Modifier.testTag("note-save-status-local"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == NoteSaveState.SaveFailed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when (state) {
                        NoteSaveState.SaveFailed -> saveProblem
                            ?: "本机文件写入失败；上次成功保存的内容仍保留。"
                        NoteSaveState.SavedLocally -> "正文已写入本机文件。"
                        NoteSaveState.NeedsReview -> "正文有需要核对的内容，确认后才能结束或离开。"
                        NoteSaveState.Saving -> "正在写入本机文件。"
                    },
                    Modifier.testTag("note-save-status-reason"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Divider(Modifier.padding(vertical = 4.dp))
                Text(
                    latestUpload?.let { "材料上传：${uploadPhaseText(it.phase)}" } ?: "材料上传：暂无上传任务",
                    Modifier.testTag("note-save-status-upload"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                latestUpload?.problem?.takeIf { it.isNotBlank() }?.let { problem ->
                    Text(
                        "材料上传原因：$problem",
                        Modifier.testTag("note-save-status-upload-problem"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "这里只显示本机保存与材料上传的实际结果，不代表服务器已同步正文。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            when (state) {
                NoteSaveState.SaveFailed -> TextButton(
                    onClick = onRetrySave,
                    modifier = Modifier.testTag("note-save-status-retry"),
                ) { Text("重试本机保存") }
                NoteSaveState.NeedsReview -> TextButton(
                    onClick = onReview,
                    modifier = Modifier.testTag("note-save-status-review"),
                ) { Text("查看待核对正文") }
                else -> TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("note-save-status-close"),
                ) { Text("关闭") }
            }
        },
        dismissButton = {
            when {
                state == NoteSaveState.SaveFailed || state == NoteSaveState.NeedsReview -> TextButton(
                    onClick = onDismiss,
                ) { Text("稍后") }
                latestUpload != null && latestUpload.phase != NoteUploadPhase.Uploaded -> TextButton(
                    onClick = onOpenMaterials,
                    modifier = Modifier.testTag("note-save-status-materials"),
                ) { Text("材料与上传") }
                else -> Unit
            }
        },
    )
}

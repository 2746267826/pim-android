package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.pim.app.ui.theme.PimTheme

@Composable
internal fun NoteUploadHistoryDialog(
    state: NoteUploadsState,
    target: NoteUploadTarget?,
    focusNoteId: String?,
    focusTitle: String?,
    onResume: (String) -> Unit,
    onPause: (String) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = PimTheme.dimensions
    val jobs = focusNoteId?.let { noteId -> state.jobs.filter { it.noteId == noteId } } ?: state.jobs
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("note-upload-history"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = d.floatingElevation,
        ) {
            Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("上传记录", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (focusNoteId == null) "全部材料上传任务"
                            else "仅显示《${focusTitle ?: "未命名记录"}》关联的上传任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("note-upload-history-close")) {
                        Icon(Icons.Default.Close, "关闭上传记录")
                    }
                }
                if (focusNoteId != null) {
                    TextButton(onClick = onShowAll, modifier = Modifier.testTag("note-upload-history-all")) {
                        Text("查看全部上传记录")
                    }
                }
                Spacer(Modifier.height(d.spaceExtraSmall))
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .testTag("note-upload-history-list"),
                ) {
                    if (jobs.isEmpty() && !state.loadFailed) {
                        Text(
                            "暂无上传记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NoteUploadJobs(
                        state = state.copy(jobs = jobs),
                        target = target,
                        onResume = onResume,
                        onPause = onPause,
                        showHeading = false,
                        initiallyExpanded = true,
                        showProcessingStatuses = false,
                    )
                }
            }
        }
    }
}

package com.pim.app.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.pim.app.recording.*
import com.pim.app.ui.components.PimButton
import com.pim.app.ui.theme.PimTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteMaterialsSheet(
    note: NoteDocument, sessions: List<RecordingSession>, materials: NoteMaterialsState, busy: Boolean,
    onDismiss: () -> Unit, onRetry: (String) -> Unit, onExport: (String) -> Unit
) {
    val d = PimTheme.dimensions
    val versions = materials.entries.filter { it.noteId == note.id }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth().testTag("material-version-sheet"),
            contentPadding = PaddingValues(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceLarge)) {
            item {
                Text("材料版本", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(d.spaceSmall))
                Text("仅保存在本机，未上传。每版包含当时的笔记、照片、可编辑笔迹和全部录音；后续修改不会改变旧版。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (versions.isEmpty()) Text("还没有固定版本，点击右上角“结束”后准备。", Modifier.padding(top = d.spaceMedium))
                if (materials.loadFailed || materials.entries.any { it.noteId == null })
                    Text("部分版本暂时无法读取或确定归属，原文件保留，没有改用当前新稿。", color = MaterialTheme.colorScheme.error)
            }
            items(versions, key = { it.id }) { version ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("material-version-${version.id}")) {
                    Column(Modifier.padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                            Icon(when (version.phase) {
                                NoteMaterialPhase.Ready -> Icons.Default.CheckCircleOutline
                                NoteMaterialPhase.Preparing -> Icons.Default.HourglassEmpty
                                NoteMaterialPhase.Failed -> Icons.Default.ErrorOutline
                            }, null, tint = if (version.phase == NoteMaterialPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text("r${version.revision} · ${materialPhaseText(version.phase)}", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("${version.recordingCount} 次录音 · ${version.segmentCount} 段 · ${if (version.matches(note, sessions)) "与当前材料相同" else "有未纳入固定版本的修改或补录"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (version.phase == NoteMaterialPhase.Ready) {
                            Text("${version.fileCount} 个文件 · ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, version.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onDismiss(); onExport(version.id) }, enabled = !busy,
                                modifier = Modifier.testTag("material-export-${version.id}")) { Text("导出完整材料包（含录音）") }
                        }
                        if (version.phase == NoteMaterialPhase.Preparing)
                            Text("可继续编辑。系统中断后续作同一版本，不会自动开麦。", style = MaterialTheme.typography.bodySmall)
                        version.problem?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (version.phase == NoteMaterialPhase.Failed) TextButton(onClick = { onRetry(version.id) }, enabled = !busy,
                            modifier = Modifier.testTag("material-retry-${version.id}")) { Text("重试此版本，不改用新稿") }
                    }
                }
            }
            item {
                Text("首次准备占用额外空间，未变材料跨版本复用；暂无自动清理或导入。导出包含原始音频和照片（可能含 EXIF），请谨慎分享。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss) { Text("返回编辑") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteRecordingsSheet(
    sessions: List<RecordingSession>, recording: RecordingState, playing: String?,
    onDismiss: () -> Unit, onPlay: (RecordingSession, Int) -> Unit, onPauseResume: () -> Unit, onStop: () -> Unit
) {
    val d = PimTheme.dimensions
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth().testTag("note-recordings-sheet"),
            contentPadding = PaddingValues(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceLarge)) {
            item {
                Text("本篇录音", style = MaterialTheme.typography.titleLarge)
                Text("每段单独保留。正式转写尚未接入。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sessions.isEmpty()) Text("还没有录音，关闭面板后点击左下角开始。", Modifier.padding(top = d.spaceLarge))
            }
            items(sessions, key = { it.id }) { session ->
                Column(verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    val index = sessions.indexOf(session) + 1
                    Text("录音 $index · ${recordingDuration(session.durationMillis)}", style = MaterialTheme.typography.titleMedium)
                    Text(when (session.phase) {
                        RecordingPhase.Recording -> "录音中"
                        RecordingPhase.Paused -> "已暂停，暂停时间不计入时长"
                        RecordingPhase.Completed -> "已保存本机"
                        RecordingPhase.Interrupted -> "录音已中断，已保存的材料保留"
                    }, color = if (session.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    session.problem?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (session.active) {
                        Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                            TextButton(onClick = onPauseResume) { Text(if (session.phase == RecordingPhase.Paused) "继续录音" else "暂停录音") }
                            TextButton(onClick = onStop, modifier = Modifier.testTag("note-stop-recording")) { Text("停止这次录音") }
                        }
                        Text("停止后仍可写笔记、再录一段；右上角“结束”才会固定整篇材料。", style = MaterialTheme.typography.bodySmall)
                    }
                    session.chunks.map { it.segment }.distinct().forEach { segment ->
                        val isPlaying = playing == "${session.id}:$segment"
                        val millis = session.chunks.filter { it.segment == segment }.sumOf { it.samples } * 1000 / RecordingPolicy.SAMPLE_RATE
                        OutlinedButton(onClick = { onPlay(session, segment) },
                            enabled = !recording.active && session.problem != RecordingProblem.Damaged,
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(d.spaceSmall))
                            Text(if (isPlaying) "停止回放" else "第 $segment 段 · ${recordingDuration(millis)}")
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Text("单段录音满 3 小时自动接续下一段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteCompletionSheet(
    version: NoteMaterialEntry?, onDismiss: () -> Unit, onMaterials: () -> Unit, onLibrary: () -> Unit
) {
    val d = PimTheme.dimensions
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(d.spaceExtraLarge).testTag("note-completion"),
            verticalArrangement = Arrangement.spacedBy(d.spaceLarge)) {
            Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(d.minimumTouchTarget), tint = PimTheme.statusColors.onSuccessContainer)
            Text("记录已保存在本机", style = MaterialTheme.typography.titleLarge)
            Text(when (version?.phase) {
                NoteMaterialPhase.Ready -> "这次记录的完整材料已准备好。你可以继续编辑或补录，旧版本会保留。"
                NoteMaterialPhase.Failed -> "原始记录已保存，但完整材料准备失败。请在材料版本中查看并重试。"
                else -> "完整材料正在本机后台准备，你可以离开页面，也可以继续编辑或补录。"
            }, style = MaterialTheme.typography.bodyMedium)
            Text("当前预览版尚不能上传、转写或生成 AI 分析。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            PimButton(onDismiss, Modifier.fillMaxWidth().testTag("note-continue-editing")) { Text("继续编辑") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onMaterials, modifier = Modifier.weight(1f)) { Text("查看材料版本") }
                TextButton(onClick = onLibrary, modifier = Modifier.weight(1f)) { Text("返回笔记列表") }
            }
        }
    }
}

internal fun materialPhaseText(phase: NoteMaterialPhase): String = when (phase) {
    NoteMaterialPhase.Preparing -> "本机准备中"
    NoteMaterialPhase.Ready -> "本机材料已齐备"
    NoteMaterialPhase.Failed -> "本机准备失败"
}

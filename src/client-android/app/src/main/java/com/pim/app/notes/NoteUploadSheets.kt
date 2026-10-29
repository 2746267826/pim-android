package com.pim.app.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pim.core.network.BoundUploadSession
import com.pim.app.ui.theme.PimTheme

internal fun uploadPhaseText(phase: NoteUploadPhase): String = when (phase) {
    NoteUploadPhase.Queued -> "等待网络上传"
    NoteUploadPhase.Uploading -> "正在上传材料"
    NoteUploadPhase.WaitingForNetwork -> "等待网络或服务器确认"
    NoteUploadPhase.Paused -> "后续上传已暂停"
    NoteUploadPhase.NeedsLogin -> "等待原账号登录"
    NoteUploadPhase.Failed -> "上传未完成，请核对后重试"
    NoteUploadPhase.Uploaded -> "服务器已接收完整材料"
    NoteUploadPhase.Conflict -> "材料已保存，存在版本冲突"
}

@Composable
internal fun NoteUploadJobs(state: NoteUploadsState, target: NoteUploadTarget?, onResume: (String) -> Unit, onPause: (String) -> Unit) {
    val d = PimTheme.dimensions
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
        Text("材料上传", style = MaterialTheme.typography.titleSmall)
        if (state.loadFailed) Text("部分上传任务无法读取，原文件保留；其余任务继续处理。", color = MaterialTheme.colorScheme.error)
        (if (expanded) state.jobs else state.jobs.take(3)).forEach { job ->
            val same = target?.binding == BoundUploadSession(job.serverIdentity, job.ownerId)
            OutlinedCard(Modifier.fillMaxWidth().testTag("note-upload-${job.id}")) {
                Column(Modifier.padding(d.spaceMedium), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    Text("r${job.revision} · ${uploadPhaseText(job.phase)}", style = MaterialTheme.typography.titleSmall)
                    Text(if (same) "${job.ownerName.ifBlank { "当前账号" }} · ${job.serverIdentity}" else "任务绑定原账号和服务器，请切回原目标查看或继续。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (job.phase in setOf(NoteUploadPhase.Queued, NoteUploadPhase.Uploading, NoteUploadPhase.WaitingForNetwork)) {
                        LinearProgressIndicator(if (job.totalBytes == 0L) 0f else (job.uploadedBytes.toDouble() / job.totalBytes).toFloat(), Modifier.fillMaxWidth())
                        Text("已确认 ${job.uploadedBytes / 1024} / ${job.totalBytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onPause(job.id) }, modifier = Modifier.testTag("note-upload-pause-${job.id}")) { Text("暂停后续上传") }
                    }
                    if (job.phase in setOf(NoteUploadPhase.Paused, NoteUploadPhase.NeedsLogin, NoteUploadPhase.Failed))
                        TextButton(onClick = { onResume(job.id) }, enabled = same, modifier = Modifier.testTag("note-upload-resume-${job.id}")) { Text("核对此版本并继续") }
                    job.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (job.phase == NoteUploadPhase.Conflict) Text("服务器保留了双方完整材料；请核对后选择保留版本，未自动覆盖。冲突比较与选择尚未开放。", style = MaterialTheme.typography.bodySmall)
                    if (job.phase == NoteUploadPhase.Uploaded) Text("仅确认原始材料保存成功；不代表转写或分析已完成。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.jobs.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起上传记录" else "查看全部 ${state.jobs.size} 个上传任务") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteUploadConsentSheet(version: NoteMaterialEntry, target: NoteUploadTarget?, onDismiss: () -> Unit,
    onSubmit: (String, NoteUploadTarget) -> Unit) {
    val d = PimTheme.dimensions
    val pinnedTarget = remember(version.id) { target }
    // Recreating a page must not carry approval over to a freshly captured account/server.
    var consent by remember(version.id, pinnedTarget?.binding) { mutableStateOf(false) }
    val same = pinnedTarget != null && pinnedTarget.binding == target?.binding
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(d.spaceLarge).testTag("note-upload-consent"),
            verticalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
            Text("上传固定材料版本", style = MaterialTheme.typography.titleLarge)
            Text("r${version.revision} · ${version.fileCount} 个文件 · ${version.recordingCount} 次录音", style = MaterialTheme.typography.bodyMedium)
            Text("包含当时的正文、原照片（可能含EXIF）、可编辑笔迹及全部录音。此后的编辑和补录不会改变本次版本。", style = MaterialTheme.typography.bodyMedium)
            if (pinnedTarget == null) Text("请先从设置登录。旧登录若缺少可靠账号标识，需要重新登录后才能上传。", color = MaterialTheme.colorScheme.error)
            else {
                Text("账号：${pinnedTarget.name.ifBlank { "当前登录账号" }}", style = MaterialTheme.typography.titleSmall)
                Text("服务器：${pinnedTarget.binding.serverIdentity}", style = MaterialTheme.typography.bodyMedium)
                if (!same) Text("账号或服务器已变化，请关闭后重新核对目标。", color = MaterialTheme.colorScheme.error)
            }
            Text("本次只将材料保存至上述服务器，不自动调用模型。录音和笔记仍保留本机；上传进度可在材料版本与笔记库查看。", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }, enabled = same && version.phase == NoteMaterialPhase.Ready, modifier = Modifier.testTag("note-upload-consent-check"))
                Text("确认将该固定版本的全部原始材料上传至上述账号和服务器", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { if (consent && same) { onSubmit(version.id, pinnedTarget!!); onDismiss() } },
                enabled = consent && same && version.phase == NoteMaterialPhase.Ready,
                modifier = Modifier.fillMaxWidth().testTag("note-upload-submit")) { Text("加入后台上传队列") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("保留本机，暂不上传") }
        }
    }
}

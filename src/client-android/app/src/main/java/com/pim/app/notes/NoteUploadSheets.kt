package com.pim.app.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

internal data class NoteUploadCardVisibility(
    val sameTarget: Boolean,
    val showProgress: Boolean,
    val showReceipt: Boolean,
    val showConflict: Boolean,
    val allowPause: Boolean,
    val allowResume: Boolean,
    val allowRefreshReceipt: Boolean,
    val allowPromotion: Boolean,
)

private val activeUploadPhases = setOf(NoteUploadPhase.Queued, NoteUploadPhase.Uploading, NoteUploadPhase.WaitingForNetwork)
private val resumableUploadPhases = setOf(NoteUploadPhase.Paused, NoteUploadPhase.NeedsLogin, NoteUploadPhase.Failed)

internal fun NoteUploadJob.cardVisibility(
    target: NoteUploadTarget?,
    busy: Boolean,
    showConflictActions: Boolean,
): NoteUploadCardVisibility {
    val sameTarget = target?.binding == BoundUploadSession(serverIdentity, ownerId)
    val active = phase in activeUploadPhases
    val conflict = phase == NoteUploadPhase.Conflict
    return NoteUploadCardVisibility(
        sameTarget = sameTarget,
        showProgress = sameTarget && active,
        showReceipt = sameTarget,
        showConflict = sameTarget && conflict,
        allowPause = sameTarget && active,
        allowResume = sameTarget && phase in resumableUploadPhases,
        allowRefreshReceipt = sameTarget && !busy,
        allowPromotion = sameTarget && !busy && showConflictActions && conflict,
    )
}

@Composable
internal fun NoteUploadJobs(
    state: NoteUploadsState,
    target: NoteUploadTarget?,
    onResume: (String) -> Unit,
    onPause: (String) -> Unit,
    asr: NoteAsrRepositoryState = NoteAsrRepositoryState(),
    artifacts: NoteArtifactRepositoryState = NoteArtifactRepositoryState(),
    localMaterial: NoteMaterialEntry? = null,
    showProcessingStatuses: Boolean = true,
    showConflictActions: Boolean = false,
    onRefreshReceipt: (String) -> Unit = {},
    onPromoteConflict: (String, String) -> Unit = { _, _ -> },
) {
    val d = PimTheme.dimensions
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
        Text("材料上传", style = MaterialTheme.typography.titleSmall)
        if (state.loadFailed) Text("部分上传任务无法读取，原文件保留；其余任务继续处理。", color = MaterialTheme.colorScheme.error)
        (if (expanded) state.jobs else state.jobs.take(3)).forEach { job ->
            val busy = job.id in state.busyJobIds
            val visibility = job.cardVisibility(target, busy, showConflictActions)
            val receipt = job.serverUpload
            var showReceipt by rememberSaveable(job.id) { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth().testTag("note-upload-${job.id}")) {
                Column(Modifier.padding(d.spaceMedium), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                    Text("r${job.revision} · ${if (visibility.sameTarget) uploadPhaseText(job.phase) else "任务绑定原账号和服务器"}",
                        style = MaterialTheme.typography.titleSmall)
                    Text(if (visibility.sameTarget) "${job.ownerName.ifBlank { "当前账号" }} · ${job.serverIdentity}" else "任务绑定原账号和服务器，请切回原目标查看或继续。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (job.phase in activeUploadPhases) {
                        if (visibility.showProgress) {
                            val confirmed = receipt?.confirmedBytes() ?: 0
                            LinearProgressIndicator(if (job.totalBytes == 0L) 0f else (confirmed.toDouble() / job.totalBytes).toFloat(), Modifier.fillMaxWidth())
                            Text(
                                if (receipt == null) "本地累计 ${job.uploadedBytes / 1024} / ${job.totalBytes / 1024} KiB；尚无服务器分块回执，不能视为服务器完整确认"
                                else "服务器已确认 ${confirmed / 1024} / ${job.totalBytes / 1024} KiB",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onPause(job.id) }, enabled = visibility.allowPause,
                            modifier = Modifier.testTag("note-upload-pause-${job.id}")) { Text("暂停后续上传") }
                    }
                    if (job.phase in resumableUploadPhases)
                        TextButton(onClick = { onResume(job.id) }, enabled = visibility.allowResume,
                            modifier = Modifier.testTag("note-upload-resume-${job.id}")) { Text("核对此版本并继续") }
                    if (visibility.showReceipt) {
                        Text(uploadReceiptPhaseText(job), style = MaterialTheme.typography.bodySmall,
                            color = if (job.phase == NoteUploadPhase.Conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("note-upload-receipt-status-${job.id}"))
                    }
                    if (!visibility.sameTarget) {
                        Text("服务器逐文件回执、处理状态与冲突摘要仅原账号和服务器可查看；本机固定材料仍保留。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (receipt != null) {
                        val completeFiles = receipt.files.count { it.isComplete() }
                        Text("逐文件回执：$completeFiles/${receipt.files.size} 个文件已确认完整",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showReceipt = !showReceipt }, enabled = !busy,
                            modifier = Modifier.testTag("note-upload-receipt-toggle-${job.id}")) {
                            Text(if (showReceipt) "收起逐块回执" else "查看逐文件逐分块回执")
                        }
                        if (showReceipt) receipt.files.forEach { file ->
                            Column(Modifier.fillMaxWidth().testTag("note-upload-receipt-file-${job.id}-${file.index}"),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${file.kind} · ${file.path}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "分块 ${file.uploadedParts.size}/${file.partCount}：${formatUploadedPartIndexes(file.uploadedParts)}" +
                                        if (file.isComplete()) " · 文件已完整" else " · 文件未完整",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { onRefreshReceipt(job.id) }, enabled = visibility.allowRefreshReceipt,
                            modifier = Modifier.testTag("note-upload-receipt-refresh-${job.id}")) { Text("重新核对服务器回执") }
                    } else {
                        Text("尚无服务器分块回执；本地累计字节不会被当作完整确认。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRefreshReceipt(job.id) }, enabled = visibility.allowRefreshReceipt,
                            modifier = Modifier.testTag("note-upload-receipt-refresh-${job.id}")) {
                            Text(if (busy) "正在核对服务器回执" else "从服务器恢复回执")
                        }
                    }
                    if (visibility.sameTarget && showProcessingStatuses) NoteProcessingStatuses(job, target, asr, artifacts)
                    if (visibility.sameTarget) job.problem?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (visibility.showConflict) {
                        if (showConflictActions) NoteUploadConflictPanel(job, localMaterial, busy,
                            visibility.allowPromotion, visibility.allowRefreshReceipt, onRefreshReceipt, onPromoteConflict)
                        else Text("服务器保留了双方完整材料；请在材料版本中查看双方摘要并显式选择保留版本。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (visibility.sameTarget && job.phase == NoteUploadPhase.Uploaded)
                        Text("仅确认原始材料保存成功；不代表转写或分析已完成。", style = MaterialTheme.typography.bodySmall)
                    if (visibility.sameTarget) state.jobMessages[job.id]?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("note-upload-message-${job.id}"))
                    }
                }
            }
        }
        if (state.jobs.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起上传记录" else "查看全部 ${state.jobs.size} 个上传任务") }
    }
}

@Composable
private fun NoteProcessingStatuses(
    job: NoteUploadJob,
    target: NoteUploadTarget?,
    asr: NoteAsrRepositoryState,
    artifacts: NoteArtifactRepositoryState,
) {
    val binding = target?.binding
    val asrTask = binding?.let { current ->
        asr.tasks.filter { it.versionId == job.versionId && it.noteId == job.noteId && it.binding == current }
            .maxByOrNull { it.updatedAtMillis }
    }
    val artifactTask = binding?.let { current ->
        artifacts.tasks.filter {
            it.materialVersionId == job.versionId && it.noteId == job.noteId && it.binding == current
        }.maxByOrNull { it.updatedAtMillis }
    }
    Text("处理状态（独立于上传）", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(processingStatusLabel("转写", asrTask, null, target), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("note-upload-asr-status-${job.id}"))
    Text(processingStatusLabel("整理", null, artifactTask, target), style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("note-upload-artifact-status-${job.id}"))
}

@Composable
private fun NoteUploadConflictPanel(
    job: NoteUploadJob,
    localMaterial: NoteMaterialEntry?,
    busy: Boolean,
    promotionEnabled: Boolean,
    refreshEnabled: Boolean,
    onRefreshReceipt: (String) -> Unit,
    onPromoteConflict: (String, String) -> Unit,
) {
    val d = PimTheme.dimensions
    Text("版本冲突 · 双方材料均保留", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
    Text("必须显式选择一个保留版本。服务器用上次看到的当前版本做 CAS；若期间又被另一端更新，会拒绝并保留双方。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val local = job.localVersion
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-upload-conflict-local-${job.id}")) {
        Column(Modifier.padding(d.spaceMedium), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
            Text("本机固定版本", style = MaterialTheme.typography.titleSmall)
            Text(local?.let { "${it.title} · r${it.revision}" }
                ?: localMaterial?.let { "r${it.revision} · ${it.fileCount} 个文件" }
                ?: "本机固定版本 ${job.versionId.take(8)}",
                style = MaterialTheme.typography.bodyMedium)
            Text(local?.let {
                "${it.fileCount} 个文件 · ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, it.sizeBytes)} · " +
                    "${it.recordingCount} 次录音 · ${it.segmentCount} 段"
            } ?: localMaterial?.let {
                "${it.fileCount} 个文件 · ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, it.sizeBytes)} · " +
                    "${it.recordingCount} 次录音 · ${it.segmentCount} 段"
            } ?: "摘要暂时不可用，但本机材料未删除",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Version ${job.versionId}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onPromoteConflict(job.id, job.versionId) }, enabled = promotionEnabled,
                modifier = Modifier.fillMaxWidth().testTag("note-conflict-keep-local-${job.id}")) {
                Text(if (busy) "正在提交 CAS" else "保留本机版本")
            }
        }
    }
    val remote = job.conflictRemoteVersion
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-upload-conflict-server-${job.id}")) {
        Column(Modifier.padding(d.spaceMedium), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
            Text("服务器当前版本", style = MaterialTheme.typography.titleSmall)
            if (remote == null) {
                Text("尚未取得服务器当前版本摘要；请先重新核对，不会猜测另一端内容。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            } else {
                Text("${remote.title} · r${remote.revision}", style = MaterialTheme.typography.bodyMedium)
                Text("${remote.fileCount} 个文件 · ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, remote.sizeBytes)} · " +
                    "${remote.recordingCount} 次录音 · ${remote.segmentCount} 段",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Version ${remote.versionId}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onPromoteConflict(job.id, remote.versionId) }, enabled = promotionEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("note-conflict-keep-server-${job.id}")) {
                    Text(if (busy) "正在提交 CAS" else "保留服务器版本")
                }
            }
            TextButton(onClick = { onRefreshReceipt(job.id) }, enabled = refreshEnabled,
                modifier = Modifier.testTag("note-conflict-refresh-${job.id}")) {
                Text(if (busy) "正在核对双方摘要" else "重新核对双方摘要")
            }
        }
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

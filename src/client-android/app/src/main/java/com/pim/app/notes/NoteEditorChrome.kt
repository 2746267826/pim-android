package com.pim.app.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.pim.app.recording.*
import com.pim.app.ui.components.PimFloatingSurface
import com.pim.app.ui.components.PimRecordingPill
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme

@Composable
internal fun NoteEditorTopBar(
    saved: Boolean, saveFailed: Boolean, busy: Boolean, preview: Boolean,
    onBack: () -> Unit, onSaveStatus: () -> Unit, onPreview: () -> Unit, onFinish: () -> Unit,
    onMaterials: () -> Unit, onRecordings: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
    onAbout: () -> Unit,
    needsReview: Boolean = false, canUndo: Boolean = false, canRedo: Boolean = false,
    onUndo: () -> Unit = {}, onRedo: () -> Unit = {},
) {
    val d = PimTheme.dimensions
    var menu by remember { mutableStateOf(false) }
    val saveState = noteSaveState(saved = saved, saveFailed = saveFailed, needsReview = needsReview)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = d.spaceSmall, vertical = d.spaceSmall).testTag("note-top-bar"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(d.minimumTouchTarget)) { Icon(Icons.Default.ArrowBack, "返回记录列表") }
            Row(Modifier.weight(1f).heightIn(min = d.minimumTouchTarget).clickable(onClick = onSaveStatus)
                .testTag("note-save-status-open"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceExtraSmall)) {
                Icon(when (saveState) {
                    NoteSaveState.SaveFailed -> Icons.Default.ErrorOutline
                    NoteSaveState.NeedsReview -> Icons.Default.WarningAmber
                    NoteSaveState.SavedLocally -> Icons.Default.Check
                    NoteSaveState.Saving -> Icons.Default.Sync
                },
                    null, Modifier.size(d.iconSize), tint = when {
                        saveState == NoteSaveState.SaveFailed -> MaterialTheme.colorScheme.error
                        saveState == NoteSaveState.NeedsReview -> PimTheme.statusColors.onWarningContainer
                        saveState == NoteSaveState.SavedLocally -> PimTheme.statusColors.onSuccessContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                Text(noteSaveStateLabel(saveState),
                    Modifier.testTag("note-save-status"), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPreview, enabled = !busy, modifier = Modifier.size(d.minimumTouchTarget).testTag("note-preview")) {
                Icon(if (preview) Icons.Default.Edit else Icons.Default.Visibility, if (preview) "编辑笔记" else "预览笔记",
                    tint = if (preview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(d.minimumTouchTarget)) { Icon(Icons.Default.MoreHoriz, "更多文档操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("撤销整篇操作") }, leadingIcon = { Icon(Icons.Default.Undo, null) },
                        enabled = canUndo && !busy, modifier = Modifier.testTag("note-undo"),
                        onClick = { menu = false; onUndo() })
                    DropdownMenuItem(text = { Text("重做整篇操作") }, leadingIcon = { Icon(Icons.Default.Redo, null) },
                        enabled = canRedo && !busy, modifier = Modifier.testTag("note-redo"),
                        onClick = { menu = false; onRedo() })
                    Divider()
                    DropdownMenuItem(text = { Text("本篇录音") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                        onClick = { menu = false; onRecordings() })
                    DropdownMenuItem(text = { Text("材料版本与上传") }, leadingIcon = { Icon(Icons.Default.Layers, null) },
                        modifier = Modifier.testTag("note-material-versions"), onClick = { menu = false; onMaterials() })
                    DropdownMenuItem(text = { Text("导出文档包（不含录音）") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        enabled = saved, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("导入文档包为新笔记") }, leadingIcon = { Icon(Icons.Default.FileUpload, null) },
                        enabled = !busy, modifier = Modifier.testTag("note-import"), onClick = { menu = false; onImport() })
                    DropdownMenuItem(text = { Text("关于笔记") }, onClick = { menu = false; onAbout() })
                }
            }
            Button(onClick = onFinish, enabled = !busy, shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = d.floatingElevation),
                contentPadding = PaddingValues(horizontal = d.spaceLarge),
                modifier = Modifier.heightIn(min = d.minimumTouchTarget).testTag("note-finish")) { Text(if (busy) "保存中" else "结束", maxLines = 1) }
        }
    }
}

@Composable
internal fun NoteDocumentHeading(
    note: NoteDocument, enabled: Boolean, preview: Boolean, latest: NoteMaterialEntry?, changed: Boolean,
    recordingCount: Int, onTitle: (String) -> Unit, onMaterials: () -> Unit, onRecordings: () -> Unit
) {
    val d = PimTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
        BasicTextField(note.title, { onTitle(it.take(500)) }, modifier = Modifier.fillMaxWidth().heightIn(min = d.minimumTouchTarget).testTag("note-title"),
            enabled = enabled, readOnly = preview,
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { field ->
                Box { if (note.title.isBlank()) Text("未命名记录", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
            })
        noteDate(note).takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recordingCount > 0 || latest != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (recordingCount > 0) TextButton(onClick = onRecordings, modifier = Modifier.testTag("note-recordings"),
                contentPadding = PaddingValues(end = d.spaceMedium)) {
                Icon(Icons.Default.GraphicEq, null, Modifier.size(d.iconSize))
                Spacer(Modifier.width(d.spaceExtraSmall))
                Text("$recordingCount 次录音", style = MaterialTheme.typography.labelMedium)
            }
            if (latest != null) TextButton(onClick = onMaterials, modifier = Modifier.weight(1f, fill = false).testTag("note-material-status"),
                contentPadding = PaddingValues(horizontal = d.spaceSmall)) {
                Text(when {
                    latest.phase == NoteMaterialPhase.Failed -> "材料准备失败"
                    changed -> "有新修改 · 未上传"
                    latest.phase == NoteMaterialPhase.Preparing -> "材料准备中 · 未上传"
                    else -> "材料已备好 · 未上传"
                }, style = MaterialTheme.typography.labelMedium,
                    color = if (latest.phase == NoteMaterialPhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.height(d.spaceSmall))
    }
}

@Composable
internal fun NoteEditorDock(
    recording: RecordingState, ownActive: Boolean, activeOtherTitle: String?, canRecord: Boolean, canInsert: Boolean,
    onRecord: () -> Unit, onPauseResume: () -> Unit, onRecordings: () -> Unit,
    onPhoto: (Boolean) -> Unit, onInk: () -> Unit, modifier: Modifier = Modifier
) {
    val d = PimTheme.dimensions
    var insert by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().testTag("note-dock"), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(d.spaceLarge)) {
        Box(Modifier.weight(1f)) {
            if (ownActive) PimRecordingPill(recording, onPauseResume, onDetails = onRecordings)
            else PimFloatingSurface {
                TextButton(onClick = onRecord, enabled = canRecord,
                    contentPadding = PaddingValues(horizontal = d.spaceMedium),
                    modifier = Modifier.heightIn(min = d.minimumTouchTarget).testTag("note-start-recording")) {
                    Icon(Icons.Default.MicNone, null, Modifier.size(d.iconSize))
                    Spacer(Modifier.width(d.spaceSmall))
                    Text(when {
                        recording.preparing -> "正在开启"
                        activeOtherTitle != null -> "《$activeOtherTitle》正在录音"
                        else -> "开始录音"
                    }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box {
            PimFloatingSurface {
                IconButton(onClick = { insert = true }, enabled = canInsert, modifier = Modifier.size(d.minimumTouchTarget).testTag("note-insert-menu")) {
                    Icon(Icons.Default.Add, "插入照片或手写卡片", tint = if (canInsert) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = insert, onDismissRequest = { insert = false }) {
                DropdownMenuItem(text = { Text("拍照") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                    onClick = { insert = false; onPhoto(true) })
                DropdownMenuItem(text = { Text("从相册选择") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                    modifier = Modifier.testTag("note-insert-photo"), onClick = { insert = false; onPhoto(false) })
                DropdownMenuItem(text = { Text("手写卡片") }, leadingIcon = { Icon(Icons.Default.Draw, null) },
                    modifier = Modifier.testTag("note-insert-ink"), onClick = { insert = false; onInk() })
            }
        }
    }
}

@Composable
internal fun RecordingPermissionNotice(
    permissions: RecordingPermissions,
    requestEnabled: Boolean = true,
    onRequest: () -> Unit,
    onOpenSettings: (RecordingPermissionSetting) -> Unit,
) {
    val d = PimTheme.dimensions
    Column(
        Modifier.fillMaxWidth().testTag("recording-permission-notice"),
        verticalArrangement = Arrangement.spacedBy(d.spaceExtraSmall),
    ) {
        PimStatusBanner(permissions.message, PimStatusTone.Warning)
        if (permissions.canRequest && !requestEnabled) {
            Text(
                "笔记保存完成后才能请求录音权限；现在仍可先打开系统设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (permissions.canRequest) {
            TextButton(
                onClick = onRequest,
                enabled = requestEnabled,
                modifier = Modifier.testTag("recording-permission-request"),
            ) {
                Text(permissions.requestActionLabel ?: "允许录音权限")
            }
        }
        if (!permissions.microphoneGranted) {
            TextButton(
                onClick = { onOpenSettings(RecordingPermissionSetting.AppSettings) },
                modifier = Modifier.testTag("recording-permission-microphone-settings"),
            ) { Text("麦克风设置") }
        }
        if (!permissions.notificationPermissionGranted || !permissions.notificationsEnabled) {
            TextButton(
                onClick = { onOpenSettings(RecordingPermissionSetting.NotificationSettings) },
                modifier = Modifier.testTag("recording-permission-notification-settings"),
            ) { Text("通知设置") }
        }
        if (!permissions.channelEnabled) {
            TextButton(
                onClick = { onOpenSettings(RecordingPermissionSetting.RecordingChannelSettings) },
                modifier = Modifier.testTag("recording-permission-channel-settings"),
            ) { Text("录音通知渠道设置") }
        }
    }
}

@Composable
internal fun ActiveRecordingConflictDialog(
    title: String,
    canOpenRecording: Boolean,
    onOpenRecording: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onContinueEditing,
        title = { Text("另一篇笔记正在录音") },
        text = {
            Text("《$title》仍在录音。你可以返回原笔记，或留在这里继续编辑本篇；原录音不会被结束，也不会改归本篇。")
        },
        confirmButton = {
            if (canOpenRecording) {
                TextButton(onClick = onOpenRecording, modifier = Modifier.testTag("recording-conflict-open")) {
                    Text("返回正在录音笔记")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onContinueEditing, modifier = Modifier.testTag("recording-conflict-continue")) {
                Text("继续编辑本篇")
            }
        },
    )
}

@Composable
internal fun NoteMaterialsRail(note: NoteDocument, sessions: List<RecordingSession>, onRecordings: () -> Unit, onMaterials: () -> Unit, onBlock: (String) -> Unit) {
    val d = PimTheme.dimensions
    Column(Modifier.width(d.noteMaterialsRailWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(d.spaceLarge).testTag("note-materials-rail"),
        verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
        Text("本篇材料", Modifier.padding(vertical = d.spaceLarge), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRecordings) { Icon(Icons.Default.GraphicEq, null, Modifier.size(d.iconSize)); Spacer(Modifier.width(d.spaceSmall)); Text("录音 · ${sessions.size}") }
        note.blocks.filter { it.string("kind") in listOf("photo", "ink") }.forEachIndexed { index, block ->
            TextButton(onClick = { onBlock(block.string("id")) }) {
                Icon(if (block.string("kind") == "ink") Icons.Default.Draw else Icons.Default.Image, null, Modifier.size(d.iconSize))
                Spacer(Modifier.width(d.spaceSmall))
                Text("${if (block.string("kind") == "ink") "手写卡片" else "照片"} ${index + 1}", maxLines = 1)
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onMaterials) { Text("材料版本") }
    }
}

@Composable
internal fun NoteDocumentEnd(preview: Boolean) {
    val d = PimTheme.dimensions
    if (preview) {
        Text("原始笔记 · 预览", Modifier.padding(vertical = d.spaceLarge).testTag("note-document-end"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Spacer(Modifier.height(d.spaceLarge).testTag("note-document-end"))
    }
}

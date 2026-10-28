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
import com.pim.app.ui.theme.PimTheme

@Composable
internal fun NoteEditorTopBar(
    saved: Boolean, saveFailed: Boolean, busy: Boolean, preview: Boolean,
    onBack: () -> Unit, onSaveStatus: () -> Unit, onPreview: () -> Unit, onFinish: () -> Unit,
    onMaterials: () -> Unit, onRecordings: () -> Unit, onExport: () -> Unit, onAbout: () -> Unit,
    needsReview: Boolean = false
) {
    val d = PimTheme.dimensions
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = d.spaceSmall, vertical = d.spaceSmall).testTag("note-top-bar"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(d.minimumTouchTarget)) { Icon(Icons.Default.ArrowBack, "返回记录列表") }
            Row(Modifier.weight(1f).heightIn(min = d.minimumTouchTarget).clickable(onClick = onSaveStatus),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceExtraSmall)) {
                Icon(when { saveFailed -> Icons.Default.ErrorOutline; needsReview -> Icons.Default.WarningAmber; saved -> Icons.Default.Check; else -> Icons.Default.Sync },
                    null, Modifier.size(d.iconSize), tint = when {
                        saveFailed -> MaterialTheme.colorScheme.error
                        needsReview -> PimTheme.statusColors.onWarningContainer
                        saved -> PimTheme.statusColors.onSuccessContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                Text(when { saveFailed -> "保存失败"; needsReview -> "待核对正文"; saved -> "已保存本机"; else -> "保存中" },
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
                    DropdownMenuItem(text = { Text("本篇录音") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                        onClick = { menu = false; onRecordings() })
                    DropdownMenuItem(text = { Text("材料版本 · 仅本机") }, leadingIcon = { Icon(Icons.Default.Layers, null) },
                        modifier = Modifier.testTag("note-material-versions"), onClick = { menu = false; onMaterials() })
                    DropdownMenuItem(text = { Text("导出文档包（不含录音）") }, leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        enabled = saved, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("关于此预览版") }, onClick = { menu = false; onAbout() })
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
        Text("${noteDate(note)} · 本机原稿", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    recording: RecordingState, ownActive: Boolean, canRecord: Boolean, canInsert: Boolean,
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
                    Text(when { recording.preparing -> "正在开启"; recording.active -> "其他笔记录音中"; else -> "开始录音" }, maxLines = 1)
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
        Text("原始材料保留在本机。\n云端处理尚未接入。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

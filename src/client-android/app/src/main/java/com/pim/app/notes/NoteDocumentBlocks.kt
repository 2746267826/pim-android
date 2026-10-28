package com.pim.app.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pim.app.ui.theme.PimTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoBlockView(file: File) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { NoteFileStore.photoPreview(file) }.getOrNull() }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), "笔记照片",
        Modifier.fillMaxWidth().heightIn(max = PimTheme.dimensions.notePhotoMaxHeight)
            .clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit)
    else Text("照片读取中或原文件不可读取；原材料仍保留", style = MaterialTheme.typography.bodySmall)
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun InkCard(id: String, ink: InkData, readOnly: Boolean, onChange: (InkData) -> Unit) {
    val d = PimTheme.dimensions
    var draw by rememberSaveable(id) { mutableStateOf(false) }
    var erase by rememberSaveable(id) { mutableStateOf(false) }
    var stylusOnly by rememberSaveable(id) { mutableStateOf(false) }
    var history by remember(id) { mutableStateOf(emptyList<InkData>()) }
    var resizing by remember { mutableStateOf<InkData?>(null) }
    val shown = resizing ?: ink
    val inkColor = MaterialTheme.colorScheme.primary.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current.density
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val commit: (InkData) -> Unit = { next ->
        if (next != ink) { history = (history + ink).takeLast(100); onChange(next) }
    }
    Column(Modifier.fillMaxWidth().testTag("ink-card-$id")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Draw, null, Modifier.size(d.iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(d.spaceSmall))
            Text("手写卡片", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!readOnly) TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); draw = !draw }, modifier = Modifier.testTag("ink-draw-$id")) {
                Text(if (draw) "完成书写" else "书写")
            }
        }
        if (draw && !readOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = !erase, onCheckedChange = { erase = false }) {
                Icon(Icons.Default.Edit, "画笔", tint = if (!erase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(checked = erase, onCheckedChange = { erase = it }) {
                Icon(Icons.Default.AutoFixNormal, "整笔擦除", tint = if (erase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                if (history.isNotEmpty()) { val previous = history.last(); history = history.dropLast(1); onChange(previous) }
                else onChange(ink.copy(strokes = ink.strokes.dropLast(1)))
            }, enabled = history.isNotEmpty() || ink.strokes.isNotEmpty(), modifier = Modifier.testTag("ink-undo-$id")) {
                Icon(Icons.Default.Undo, "撤销上一笔或尺寸调整")
            }
            TextButton(onClick = { stylusOnly = !stylusOnly }, modifier = Modifier.weight(1f)) {
                Text(if (stylusOnly) "仅手写笔" else "手指和笔", style = MaterialTheme.typography.labelMedium)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = maxWidth.value
            val scale = minOf(1f, available / shown.width)
            Box(Modifier.width((shown.width * scale).dp).height((shown.height * scale).dp)
                .clip(MaterialTheme.shapes.medium)
                .border(d.borderWidth, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.background)) {
                Canvas(Modifier.fillMaxSize()) {
                    val step = d.inkGridSpacing.toPx()
                    var x = step
                    while (x < size.width) {
                        var y = step
                        while (y < size.height) { drawCircle(gridColor, d.borderWidth.toPx(), Offset(x, y)); y += step }
                        x += step
                    }
                }
                if (shown.strokes.isEmpty() && !draw) Text(if (readOnly) "空白手写卡片" else "点击“书写”，在这里记下想法", Modifier.padding(d.spaceLarge),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AndroidView(factory = { context -> InkInputView(context) }, modifier = Modifier.fillMaxSize().testTag("ink-canvas-$id"), update = {
                    it.ink = shown; it.drawingEnabled = draw && !readOnly && resizing == null; it.eraseEnabled = erase
                    it.stylusOnly = stylusOnly; it.inkColor = inkColor; it.onInkChanged = commit
                })
                if (!readOnly) Box(Modifier.align(Alignment.BottomEnd).size(d.minimumTouchTarget)
                    .testTag("ink-resize-$id")
                    .pointerInput(id, ink, available) {
                        detectDragGestures(onDragStart = { resizing = ink }, onDragCancel = { resizing = null },
                            onDragEnd = { resizing?.let(commit); resizing = null }) { change, delta ->
                            change.consume()
                            val current = resizing ?: ink
                            val currentScale = minOf(1f, available / current.width)
                            resizing = current.resized(current.width + delta.x / density / currentScale,
                                current.height + delta.y / density / currentScale, available)
                        }
                    }, contentAlignment = Alignment.BottomEnd) {
                    Icon(Icons.Default.SouthEast, "拖动右下角调整手写卡片宽高",
                        Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small).padding(d.spaceExtraSmall).size(d.iconSize),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if (draw && !readOnly) Text("书写时画布内不滚动；完成书写后恢复滚动。放大只添加空白，不拉伸笔迹。",
            Modifier.padding(top = d.spaceSmall), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

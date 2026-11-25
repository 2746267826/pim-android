package com.pim.app.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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

private data class InkColorChoice(val key: String, val label: String, val color: String)
private data class InkWidthChoice(val key: String, val label: String, val width: Float)

private val InkColorChoices = listOf(
    InkColorChoice("ink", "墨色", INK_COLOR_INK),
    InkColorChoice("red", "红", INK_COLOR_RED),
    InkColorChoice("blue", "蓝", INK_COLOR_BLUE),
    InkColorChoice("green", "绿", INK_COLOR_GREEN),
)
private val InkWidthChoices = listOf(
    InkWidthChoice("thin", "细", INK_WIDTH_THIN),
    InkWidthChoice("medium", "中", INK_WIDTH_MEDIUM),
    InkWidthChoice("coarse", "粗", INK_WIDTH_COARSE),
)

@Composable
private fun rememberInkPenPreference(): Pair<InkPenPreference, (InkPenPreference) -> Unit> {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(InkPenPreferenceStore.PREFERENCES, android.content.Context.MODE_PRIVATE)
    }
    val store = remember(preferences) { InkPenPreferenceStore(preferences) }
    val state = remember(store) { mutableStateOf(store.load()) }
    DisposableEffect(store) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == InkPenPreferenceStore.KEY_COLOR || key == InkPenPreferenceStore.KEY_WIDTH) {
                state.value = store.load()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val update = remember(store, state) {
        { next: InkPenPreference ->
            state.value = next
            store.save(next)
        }
    }
    return state.value to update
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun InkCard(id: String, ink: InkData, readOnly: Boolean, onChange: (InkData) -> Unit) {
    val d = PimTheme.dimensions
    var draw by rememberSaveable(id) { mutableStateOf(false) }
    var erase by rememberSaveable(id) { mutableStateOf(false) }
    var wholeStrokeErase by rememberSaveable(id) { mutableStateOf(false) }
    val smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp
    var stylusOnly by rememberSaveable(id) { mutableStateOf(defaultInkStylusOnly(smallestScreenWidthDp)) }
    var brushPanelOpen by rememberSaveable(id) { mutableStateOf(false) }
    var canUndo by remember(id) { mutableStateOf(false) }
    val undo = remember(id) { InkUndoStack() }
    var resizing by remember { mutableStateOf<InkData?>(null) }
    val (brush, setBrush) = rememberInkPenPreference()
    val shown = resizing ?: ink
    val gridColor = Color(0xFFD9E1ED)
    val paperText = Color(0xFF506176)
    val density = LocalDensity.current.density
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val commit: (InkData, InkData) -> Unit = { previous, next ->
        if (next != previous) {
            undo.record(previous)
            canUndo = undo.canUndo
            onChange(next)
        }
    }
    val colorLabel = InkColorChoices.firstOrNull { it.color == brush.color }?.label ?: "墨色"
    val widthLabel = InkWidthChoices.firstOrNull { kotlin.math.abs(it.width - brush.width) <= 1e-6f }?.label ?: "中"
    Column(Modifier.fillMaxWidth().testTag("ink-card-$id")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Draw, null, Modifier.size(d.iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(d.spaceSmall))
            Text("手写卡片", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!readOnly) TextButton(onClick = { keyboard?.hide(); focus.clearFocus(); draw = !draw }, modifier = Modifier.testTag("ink-draw-$id")) {
                Text(if (draw) "完成书写" else "书写")
            }
        }
        if (draw && !readOnly) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(
                    onClick = { erase = false; brushPanelOpen = true },
                    modifier = Modifier.testTag("ink-brush-$id").semantics {
                        selected = !erase
                        contentDescription = "画笔设置：$colorLabel，$widthLabel"
                    },
                ) {
                    Box {
                        Icon(Icons.Default.Brush, null, tint = if (!erase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.align(Alignment.BottomEnd).size(10.dp)
                            .background(Color(inkColorInt(brush.color)), CircleShape))
                    }
                }
                DropdownMenu(expanded = brushPanelOpen, onDismissRequest = { brushPanelOpen = false }) {
                    Column(Modifier.width(250.dp).padding(horizontal = d.spaceMedium)) {
                        Text("画笔 · $colorLabel · $widthLabel", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(d.spaceSmall))
                        InkColorChoices.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                                row.forEach { option ->
                                    FilterChip(
                                        selected = brush.color == option.color,
                                        onClick = { setBrush(brush.copy(color = option.color)) },
                                        label = { Text(option.label) },
                                        leadingIcon = {
                                            Box(Modifier.size(12.dp).background(Color(inkColorInt(option.color)), CircleShape))
                                        },
                                        modifier = Modifier.testTag("ink-color-${option.key}-$id"),
                                    )
                                }
                            }
                        }
                        Text("粗细", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                            InkWidthChoices.forEach { option ->
                                FilterChip(
                                    selected = kotlin.math.abs(brush.width - option.width) <= 1e-6f,
                                    onClick = { setBrush(brush.copy(width = option.width)) },
                                    label = { Text(option.label) },
                                    modifier = Modifier.testTag("ink-width-${option.key}-$id"),
                                )
                            }
                        }
                        TextButton(
                            onClick = { brushPanelOpen = false },
                            modifier = Modifier.align(Alignment.End).testTag("ink-brush-done-$id"),
                        ) { Text("完成设置") }
                    }
                }
            }
            IconToggleButton(
                checked = erase && !wholeStrokeErase,
                onCheckedChange = { if (it) { erase = true; wholeStrokeErase = false } },
                modifier = Modifier.testTag("ink-eraser-partial-$id").semantics {
                    contentDescription = "局部擦除（默认）"
                },
            ) {
                Icon(Icons.Default.AutoFixNormal, null,
                    tint = if (erase && !wholeStrokeErase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(
                checked = erase && wholeStrokeErase,
                onCheckedChange = { if (it) { erase = true; wholeStrokeErase = true } },
                modifier = Modifier.testTag("ink-eraser-whole-$id").semantics {
                    contentDescription = "整笔擦除"
                },
            ) {
                Icon(Icons.Default.DeleteSweep, null,
                    tint = if (erase && wholeStrokeErase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = {
                    val previous = undo.undo()
                    if (previous != null) {
                        canUndo = undo.canUndo
                        onChange(previous)
                    } else if (ink.strokes.isNotEmpty()) {
                        onChange(ink.copy(strokes = ink.strokes.dropLast(1)))
                    }
                },
                enabled = canUndo || ink.strokes.isNotEmpty(),
                modifier = Modifier.testTag("ink-undo-$id"),
            ) {
                Icon(Icons.Default.Undo, "撤销上一笔或擦除手势")
            }
            TextButton(
                onClick = { stylusOnly = !stylusOnly },
                modifier = Modifier.testTag("ink-stylus-only-$id"),
            ) {
                Text(if (stylusOnly) "仅手写笔" else "手指和笔", style = MaterialTheme.typography.labelMedium)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = maxWidth.value
            val scale = minOf(1f, available / shown.width)
            Box(Modifier.width((shown.width * scale).dp).height((shown.height * scale).dp)
                .clip(MaterialTheme.shapes.medium)
                .border(d.borderWidth, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .background(Color.White)) {
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
                    style = MaterialTheme.typography.bodySmall, color = paperText)
                AndroidView(factory = { context -> InkInputView(context) }, modifier = Modifier.fillMaxSize().testTag("ink-canvas-$id"), update = {
                    it.ink = shown; it.drawingEnabled = draw && !readOnly && resizing == null
                    it.eraserMode = if (erase) {
                        if (wholeStrokeErase) InkEraserMode.WholeStroke else InkEraserMode.Partial
                    } else {
                        null
                    }
                    it.stylusOnly = stylusOnly; it.penColor = brush.color; it.penWidth = brush.width
                    it.onInkChanged = commit
                })
                if (!readOnly) Box(Modifier.align(Alignment.BottomEnd).size(d.minimumTouchTarget)
                    .testTag("ink-resize-$id")
                    .pointerInput(id, ink, available) {
                        detectDragGestures(onDragStart = { resizing = ink }, onDragCancel = { resizing = null },
                            onDragEnd = { resizing?.let { commit(ink, it) }; resizing = null }) { change, delta ->
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
        if (draw && !readOnly) Text("书写时画布内不滚动；默认局部擦除，可切换整笔擦除。完成后恢复滚动，放大只添加空白，不拉伸笔迹。",
            Modifier.padding(top = d.spaceSmall), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

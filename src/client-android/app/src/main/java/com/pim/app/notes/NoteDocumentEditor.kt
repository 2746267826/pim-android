package com.pim.app.notes

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private class DocumentEditorGeometry { var origin: Offset? = null; var revealPosted = false }

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun ContinuousMarkdownDocument(
    note: NoteDocument,
    preview: Boolean,
    enabled: Boolean,
    coordinator: NoteEditorCoordinator,
    readCurrentBlocks: () -> List<JsonObject>?,
    onBlocks: (List<JsonObject>) -> Unit,
    onMaterial: (String) -> Unit,
    onProblem: (String) -> Unit,
    onFallback: (String) -> Unit,
    photoFile: (String, String) -> File,
    minimumHeight: Dp,
    viewportBounds: Rect?,
    onRevealCaret: suspend (Rect) -> Unit,
    materialScrollTarget: String?,
) {
    val d = PimTheme.dimensions
    val documentId = note.blocks.firstOrNull { it.string("kind") == "markdown" }?.string("id") ?: note.id
    val materialSignature = remember(note.blocks) { note.blocks.filter { it.string("kind") != "markdown" }.joinToString("\u0001") { it.toString() } }
    val materials = remember(note.id, materialSignature) { note.blocks.filter { it.string("kind") != "markdown" } }
    val previews by produceState<Map<String, String>>(emptyMap(), note.id, materialSignature) {
        value = withContext(Dispatchers.IO) { buildMaterialPreviews(note.id, materials, photoFile) }
    }
    var height by remember(documentId) { mutableStateOf(minimumHeight) }
    var richView by remember { mutableStateOf<OfflineMarkdownView?>(null) }
    var richFocused by remember { mutableStateOf(false) }
    var caret by remember { mutableStateOf<Rect?>(null) }
    val geometry = remember(documentId) { DocumentEditorGeometry() }
    val scope = rememberCoroutineScope()
    val host = LocalView.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography.bodyLarge
    val imeBottom = WindowInsets.ime.getBottom(density)
    val pinnable = LocalPinnableContainer.current
    val blocks = rememberUpdatedState(readCurrentBlocks)
    val previewState = rememberUpdatedState(previews)
    val fallback = rememberUpdatedState(onFallback)
    val problem = rememberUpdatedState(onProblem)
    val materialCallback = rememberUpdatedState(onMaterial)

    DisposableEffect(pinnable, richFocused, coordinator.committing, preview) {
        val pin = if ((richFocused || coordinator.committing) && !preview) pinnable?.pin() else null
        onDispose { pin?.release() }
    }
    val reveal = rememberUpdatedState<suspend () -> Unit> {
        if (richFocused && !preview) {
            val range = caret
            val viewport = viewportBounds
            val origin = geometry.origin
            if (range != null && viewport != null && origin != null && viewport.height > 0f) {
                val actual = range.translate(origin)
                if (actual.top < viewport.top || actual.bottom > viewport.bottom) onRevealCaret(actual)
            }
        }
    }
    fun requestReveal() {
        if (!geometry.revealPosted) {
            geometry.revealPosted = true
            host.post { geometry.revealPosted = false; scope.launch { reveal.value() } }
        }
    }
    LaunchedEffect(caret, richFocused, imeBottom, height, viewportBounds, preview) { requestReveal() }
    LaunchedEffect(materialScrollTarget, richView) {
        materialScrollTarget?.let { richView?.scrollToMaterial(it) }
    }
    fun cssColor(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    val theme = mapOf(
        "text" to cssColor(colors.onSurface.toArgb()), "muted" to cssColor(colors.onSurfaceVariant.toArgb()),
        "primary" to cssColor(colors.primary.toArgb()), "selected" to cssColor(colors.primaryContainer.toArgb()),
        "border" to cssColor(colors.outlineVariant.toArgb()), "code-surface" to cssColor(colors.surfaceVariant.toArgb()),
        "font-size" to "${typography.fontSize.value * density.fontScale}px",
        "line-height" to "${typography.lineHeight.value * density.fontScale}px",
        "paragraph-gap" to "${d.spaceMedium.value}px", "tool-gap" to "${d.spaceExtraSmall.value}px",
        "touch" to "${d.minimumTouchTarget.value}px", "radius" to "${d.spaceSmall.value}px",
        "minimum-height" to "${minimumHeight.value}px",
    )
    AndroidView(
        factory = { context ->
            OfflineMarkdownView(context).also { view ->
                view.blockId = documentId
                view.readCurrentBlocks = { blocks.value() }
                view.onBlocks = onBlocks
                view.onInsertion = { anchor, focused, marks ->
                    if (coordinator.isCurrent(documentId, view)) { richFocused = focused; coordinator.remember(anchor, focused, marks) }
                }
                view.onMaterial = { materialCallback.value(it) }
                view.onCaret = { top, bottom ->
                    val start = (top - d.spaceSmall.value).coerceAtLeast(0f)
                    if (coordinator.isCurrent(documentId, view)) caret = Rect(
                        0f, start * density.density, density.density,
                        (bottom + d.spaceSmall.value).coerceAtLeast(start + 1f) * density.density,
                    )
                }
                view.onHeight = { height = it.dp.coerceAtLeast(minimumHeight) }
                view.onFailure = { reason -> fallback.value(reason) }
                view.onReviewRequired = { reason -> coordinator.review(documentId, reason) }
                view.onSourceOnly = { reason -> fallback.value(reason) }
                view.onNotice = { problem.value(it) }
                view.onPending = { coordinator.pending(view, it) }
                coordinator.register(documentId, view)
                richView = view
            }
        },
        modifier = Modifier.fillMaxWidth().height(height)
            .onGloballyPositioned { geometry.origin = it.positionInRoot(); requestReveal() }
            .testTag("note-document-editor"),
        onReset = null,
        onRelease = { view ->
            coordinator.retire(view)
            if (richView === view) richView = null
        },
        update = { view ->
            view.updateMaterialPreviews(previewState.value)
            view.bindDocument(
                blocks.value() ?: note.blocks,
                enabled && !preview,
                theme,
                previewState.value,
            )
        },
    )
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun ContinuousSourceDocument(
    note: NoteDocument,
    preview: Boolean,
    enabled: Boolean,
    reason: String?,
    reviewPending: Boolean,
    canAcknowledgeReview: Boolean,
    onAcknowledgeReview: () -> Unit,
    onChangeBlock: (String, String) -> Unit,
    onInkChange: (String, InkData) -> Unit,
    onSelection: (NoteInsertion) -> Unit,
    onProblem: (String) -> Unit,
    photoFile: (String, String) -> File,
    onMaterial: (String) -> Unit,
) {
    val d = PimTheme.dimensions
    Column(Modifier.fillMaxWidth().testTag("note-source-document"), verticalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
        PimStatusBanner(
            reason ?: "Markdown 源码编辑：整篇正文共用一个模式，材料仍按原顺序保留。",
            PimStatusTone.Warning,
        )
        if (reviewPending) {
            PimStatusBanner("有正文需要核对：富文本输入未能完整确认，以下保留最近收到的原文。请核对或补充后确认继续。", PimStatusTone.Warning)
            TextButton(onClick = onAcknowledgeReview, enabled = canAcknowledgeReview,
                modifier = Modifier.testTag("note-review-source-document")) { Text("已核对以上原文，继续") }
        }
        note.blocks.forEach { block ->
            when (block.string("kind")) {
                "markdown" -> SourceMarkdownBlockView(
                    block = block,
                    preview = preview,
                    enabled = enabled,
                    readCurrentText = {
                        note.blocks.firstOrNull { it.string("id") == block.string("id") }?.string("text")
                    },
                    onText = { text -> onChangeBlock(block.string("id"), text) },
                    onSelection = { offset -> onSelection(NoteInsertion(block.string("id"), offset)) },
                    minimumHeight = if (note.blocks.size == 1) d.noteEditorMinHeight else d.minimumTouchTarget,
                )
                "photo" -> PhotoBlockView(photoFile(note.id, block.string("assetId")))
                "ink" -> InkCard(block.string("id"), inkOf(block), preview || !enabled) { ink ->
                    onInkChange(block.string("id"), ink)
                }
                "legacyQuickNote" -> LegacyQuickNoteSourceBlock(block)
                else -> {
                    PimStatusBanner("未知扩展块已保留，当前版本不能编辑它。", PimStatusTone.Warning)
                    TextButton(onClick = { onMaterial(block.string("id")) }) { Text("查看材料") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteMaterialSheet(
    note: NoteDocument,
    blockId: String,
    readOnly: Boolean,
    photoFile: (String, String) -> File,
    onInk: (InkData) -> Unit,
    onDismiss: () -> Unit,
) {
    val block = note.blocks.firstOrNull { it.string("id") == blockId } ?: return
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("note-material-sheet")) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(PimTheme.dimensions.spaceLarge),
            verticalArrangement = Arrangement.spacedBy(PimTheme.dimensions.spaceMedium),
        ) {
            when (block.string("kind")) {
                "photo" -> {
                    Text("照片材料", style = MaterialTheme.typography.titleMedium)
                    PhotoBlockView(photoFile(note.id, block.string("assetId")))
                    Text("照片按原顺序保存在正文中；关闭后继续编辑文字。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "ink" -> InkCard(block.string("id"), inkOf(block), readOnly) { ink ->
                    onInk(ink)
                }
                "legacyQuickNote" -> LegacyQuickNoteSourceBlock(block)
                else -> PimStatusBanner("该材料类型已保留，当前版本不能编辑。", PimStatusTone.Warning)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭材料") }
        }
    }
}

private fun buildMaterialPreviews(
    noteId: String,
    materials: List<JsonObject>,
    photoFile: (String, String) -> File,
): Map<String, String> {
    var remaining = 900_000
    val result = linkedMapOf<String, String>()
    materials.forEach { block ->
        if (remaining <= 0) return@forEach
        val encoded = runCatching {
            when (block.string("kind")) {
                "photo" -> {
                    val file = photoFile(noteId, block.string("assetId"))
                    val bitmap = NoteFileStore.photoPreview(file, 720) ?: return@runCatching null
                    encodePreview(bitmap)
                }
                "ink" -> encodePreview(InkRendering.bitmap(inkOf(block)))
                else -> null
            }
        }.getOrNull() ?: return@forEach
        if (encoded.length <= remaining) {
            result[block.string("id")] = encoded
            remaining -= encoded.length
        }
    }
    return result
}

private fun encodePreview(source: Bitmap): String? {
    val scale = minOf(1f, 720f / maxOf(source.width, source.height))
    val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
    ) else source
    return try {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 66, output)) null
        else "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    } finally {
        if (bitmap !== source) bitmap.recycle()
        source.recycle()
    }
}

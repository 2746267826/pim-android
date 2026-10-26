package com.pim.app.notes

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch

internal class MarkdownEditText(context: Context) : EditText(context) {
    var selectionChanged: (Int) -> Unit = {}
    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (hasFocus()) selectionChanged(start)
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun MarkdownBlockView(
    block: JsonObject, preview: Boolean, enabled: Boolean, coordinator: NoteEditorCoordinator,
    readCurrentText: () -> String?, onText: (String) -> Unit, onProblem: (String) -> Unit,
    minimumHeight: Dp = PimTheme.dimensions.minimumTouchTarget
) {
    val id = block.string("id")
    var sourceMode by rememberSaveable(id) { mutableStateOf(false) }
    var reason by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(minimumHeight) }
    var richView by remember { mutableStateOf<OfflineMarkdownView?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val d = PimTheme.dimensions
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography.bodyLarge
    fun cssColor(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
    val theme = mapOf(
        "text" to cssColor(colors.onSurface.toArgb()), "muted" to cssColor(colors.onSurfaceVariant.toArgb()),
        "primary" to cssColor(colors.primary.toArgb()), "selected" to cssColor(colors.primaryContainer.toArgb()),
        "border" to cssColor(colors.outlineVariant.toArgb()), "code-surface" to cssColor(colors.surfaceVariant.toArgb()),
        "font-size" to "${typography.fontSize.value * density.fontScale}px",
        "line-height" to "${typography.lineHeight.value * density.fontScale}px",
        "paragraph-gap" to "${d.spaceMedium.value}px", "tool-gap" to "${d.spaceExtraSmall.value}px",
        "touch" to "${d.minimumTouchTarget.value}px", "radius" to "${d.spaceSmall.value}px",
        "minimum-height" to "${minimumHeight.value}px"
    )
    val textCallback = rememberUpdatedState(onText)
    val currentText = rememberUpdatedState(readCurrentText)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, richView) {
        val view = richView
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && view != null) scope.launch {
                try { view.commitAndFreeze() }
                catch (_: Exception) { onProblem("切换应用时未能确认正文输入完成，请返回后核对；已收到的原文保留。") }
                finally { view.resumeEditing() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth()) {
        if (!preview) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = enabled && !changing, modifier = Modifier.testTag("markdown-mode-$id"), onClick = {
                keyboard?.hide(); focus.clearFocus()
                changing = true
                scope.launch {
                    try { coordinator.flush(); reason = null; sourceMode = !sourceMode }
                    catch (_: Exception) { onProblem("尚未确认正文输入完成，没有切换编辑模式；请稍后重试。") }
                    finally { changing = false; coordinator.resume() }
                }
            }) { Text(if (sourceMode) "富文本" else "Markdown 源码", style = MaterialTheme.typography.labelMedium) }
        }
        reason?.let { PimStatusBanner(it, PimStatusTone.Warning) }
        if (sourceMode) SourceMarkdownBlockView(block, preview, enabled && !changing,
            readCurrentText = readCurrentText, onText = { coordinator.pending(id, false); onText(it) },
            onSelection = { coordinator.select(NoteInsertion(id, it)) }, minimumHeight = minimumHeight)
        else AndroidView(
            factory = { context -> OfflineMarkdownView(context).also { view ->
                view.blockId = id
                view.readCurrentText = { currentText.value() }
                view.onText = { textCallback.value(it) }
                view.onInsertion = { anchor, focused, marks -> coordinator.remember(anchor, focused, marks) }
                view.onHeight = { height = it.dp.coerceAtLeast(minimumHeight) }
                view.onFailure = { reason = it; sourceMode = true }
                view.onNotice = onProblem
                view.onPending = { coordinator.pending(id, it) }
                coordinator.register(id, view); richView = view
            } },
            modifier = Modifier.fillMaxWidth().height(height).testTag("rich-markdown-$id"),
            onReset = null,
            onRelease = { view -> coordinator.unregister(id, view); view.releaseEditor(); if (richView === view) richView = null },
            update = { it.bind(block.string("text"), enabled && !preview && !changing, theme) }
        )
    }
}

@Composable
private fun SourceMarkdownBlockView(
    block: JsonObject, preview: Boolean, enabled: Boolean, readCurrentText: () -> String?,
    onText: (String) -> Unit, onSelection: (Int) -> Unit,
    minimumHeight: Dp
) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) { builder.linkResolver { _, _ -> } }
    }).build() }
    val editor = remember(markwon) { MarkwonEditor.create(markwon) }
    val change = rememberUpdatedState(onText)
    val selection = rememberUpdatedState(onSelection)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val style = MaterialTheme.typography.bodyLarge
    val text = block.string("text")
    val modifier = Modifier.fillMaxWidth().heightIn(min = minimumHeight).testTag("markdown-${block.string("id")}")
    fun TextView.applyTypography() {
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        val lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.lineHeight.value, resources.displayMetrics)
        setLineSpacing(maxOf(0f, lineHeight - (paint.fontMetrics.descent - paint.fontMetrics.ascent)), 1f)
    }
    if (preview) AndroidView(factory = { TextView(it).apply { setPadding(0, 0, 0, 0); setTextIsSelectable(true) } }, modifier = modifier, update = {
        it.setTextColor(textColor); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        it.applyTypography()
        if (it.tag != text) { markwon.setMarkdown(it, text); it.tag = text }
    }) else AndroidView(factory = { ctx ->
        MarkdownEditText(ctx).apply {
            background = null; gravity = Gravity.TOP; setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false; setHorizontallyScrolling(false); setSelectAllOnFocus(false)
            hint = "从这里开始记录…"
            // Do not expose note text to personalized keyboard training or autofill.
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(text)
            addTextChangedListener(MarkwonEditorTextWatcher.withProcess(editor))
            editor.process(this.text)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { change.value(s.toString()) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            selectionChanged = { selection.value(it) }
            setOnFocusChangeListener { _, focused -> if (focused) selection.value(selectionStart.coerceAtLeast(0)) }
        }
    }, modifier = modifier, update = {
        it.isEnabled = enabled; it.setTextColor(textColor); it.setHintTextColor(hintColor)
        it.applyTypography()
        val current = readCurrentText() ?: text
        if (it.text.toString() != current) {
            val offset = it.selectionStart.coerceIn(0, current.length)
            it.setText(current); it.setSelection(offset)
        }
    })
}

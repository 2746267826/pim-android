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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.pim.app.ui.theme.PimTheme
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import kotlinx.serialization.json.JsonObject

internal class MarkdownEditText(context: Context) : EditText(context) {
    var selectionChanged: (Int) -> Unit = {}
    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (hasFocus()) selectionChanged(start)
    }
}

@Composable
internal fun MarkdownBlockView(block: JsonObject, preview: Boolean, enabled: Boolean, onText: (String) -> Unit, onSelection: (Int) -> Unit) {
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
    val modifier = Modifier.fillMaxWidth().heightIn(min = PimTheme.dimensions.minimumTouchTarget).testTag("markdown-${block.string("id")}")
    if (preview) AndroidView(factory = { TextView(it).apply { setPadding(0, 0, 0, 0); setTextIsSelectable(true) } }, modifier = modifier, update = {
        it.setTextColor(textColor); it.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        if (it.tag != text) { markwon.setMarkdown(it, text); it.tag = text }
    }) else AndroidView(factory = { ctx ->
        MarkdownEditText(ctx).apply {
            background = null; gravity = Gravity.TOP; setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false; setHorizontallyScrolling(false); setSelectAllOnFocus(false)
            hint = "写下 Markdown 正文，在光标位置插入照片或手写"
            // Do not expose note text to personalized keyboard training or autofill.
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(text)
            addTextChangedListener(MarkwonEditorTextWatcher.withProcess(editor))
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
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
        if (it.text.toString() != text) {
            val offset = it.selectionStart.coerceIn(0, text.length)
            it.setText(text); it.setSelection(offset)
        }
    })
}

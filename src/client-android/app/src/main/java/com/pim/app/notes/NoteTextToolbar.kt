package com.pim.app.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.pim.app.ui.theme.PimTheme

@Composable
internal fun NoteTextToolbar(editor: NoteEditorCoordinator, modifier: Modifier = Modifier) {
    val d = PimTheme.dimensions
    Surface(modifier.testTag("note-text-tools"), color = MaterialTheme.colorScheme.surface,
        tonalElevation = d.floatingElevation, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("bold" to "加粗", "italic" to "斜体", "heading" to "标题", "bulletList" to "列表", "taskList" to "待办",
                "blockquote" to "引用", "codeBlock" to "代码", "table" to "表格", "undo" to "撤销", "redo" to "重做").forEach { (command, label) ->
                val active = command in editor.activeMarks
                TextButton(onClick = { editor.command(command) },
                    modifier = Modifier.heightIn(min = d.minimumTouchTarget).testTag("note-format-$command").semantics { selected = active },
                    colors = ButtonDefaults.textButtonColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    contentPadding = PaddingValues(horizontal = d.spaceMedium)) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

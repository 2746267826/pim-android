package com.pim.app.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Coordinates asynchronous WebView/IME commits before native document operations. */
internal class NoteEditorCoordinator {
    private val editors = linkedMapOf<String, OfflineMarkdownView>()
    private val insertions = mutableMapOf<String, NoteInsertion>()
    private var pendingBlocks by mutableStateOf(emptySet<String>())
    val pending: Boolean get() = pendingBlocks.isNotEmpty()
    var activeRichBlock by mutableStateOf<String?>(null)
        private set
    var activeMarks by mutableStateOf(emptySet<String>())
        private set
    var selectedBlock: String? = null
        private set

    fun register(id: String, editor: OfflineMarkdownView) { editors[id] = editor }
    fun unregister(id: String, editor: OfflineMarkdownView) {
        if (editors[id] === editor) { editors.remove(id); if (activeRichBlock == id) activeRichBlock = null }
    }
    fun select(anchor: NoteInsertion) { selectedBlock = anchor.blockId; insertions[anchor.blockId] = anchor; activeRichBlock = null }
    fun remember(anchor: NoteInsertion, focused: Boolean, marks: Set<String>) {
        insertions[anchor.blockId] = anchor
        if (focused) { selectedBlock = anchor.blockId; activeRichBlock = anchor.blockId; activeMarks = marks }
        else if (activeRichBlock == anchor.blockId) activeRichBlock = null
    }
    fun pending(id: String, value: Boolean) { pendingBlocks = if (value) pendingBlocks + id else pendingBlocks - id }

    fun insertion(note: NoteDocument): NoteInsertion {
        val block = note.blocks.firstOrNull { it.string("id") == selectedBlock && it.string("kind") == "markdown" }
            ?: note.blocks.last { it.string("kind") == "markdown" }
        val text = block.string("text")
        return insertions[block.string("id")]?.takeIf { it.expectedText == null || it.expectedText == text }
            ?: NoteInsertion(block.string("id"), text.length)
    }

    suspend fun flush() {
        try { editors.values.toList().forEach { it.commitAndFreeze() }; check(!pending) { "Uncommitted IME input" } }
        catch (error: Exception) { resume(); throw error }
    }

    fun resume() { editors.values.toList().forEach { it.resumeEditing() } }
    fun command(name: String) { editors[activeRichBlock]?.command(name) }
}

package com.pim.app.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps retiring input owners alive until their final acknowledgement reaches the native source. */
internal class NoteEditorCoordinator(private val scope: CoroutineScope, restoredReviews: Set<String> = emptySet()) {
    private class Entry(val id: String, val view: OfflineMarkdownView) {
        val activated = CompletableDeferred<Unit>()
        var retirement: Deferred<Unit>? = null
    }
    private val entries = linkedMapOf<OfflineMarkdownView, Entry>()
    private val active = mutableMapOf<String, Entry>()
    private val insertions = mutableMapOf<String, NoteInsertion>()
    private val gate = Mutex()
    private var pendingViews by mutableStateOf(emptySet<OfflineMarkdownView>())
    private val reviews = mutableStateMapOf<String, String>().apply {
        restoredReviews.forEach { put(it, REVIEW_MESSAGE) }
    }
    val pending: Boolean get() = pendingViews.isNotEmpty() || reviews.isNotEmpty()
    val reviewIds: List<String> get() = reviews.keys.toList()
    var committing by mutableStateOf(false)
        private set
    var activeRichBlock by mutableStateOf<String?>(null)
        private set
    var activeMarks by mutableStateOf(emptySet<String>())
        private set
    var selectedBlock: String? = null
        private set

    fun register(id: String, view: OfflineMarkdownView) {
        val predecessors = entries.values.filter { it.id == id }.toList()
        val entry = Entry(id, view)
        entries[view] = entry; active[id] = entry
        if (predecessors.isEmpty()) entry.activated.complete(Unit)
        else {
            view.pauseBinding()
            predecessors.forEach { retire(it.view) }
            scope.launch {
                try {
                    predecessors.forEach { checkNotNull(it.retirement).await() }
                    if (entry.retirement == null && entries[view] === entry) view.allowBinding()
                    entry.activated.complete(Unit)
                } catch (_: Exception) {
                    review(id, REVIEW_MESSAGE)
                    view.requireSourceReview(REVIEW_MESSAGE)
                    entry.activated.complete(Unit)
                }
            }
        }
    }

    fun isCurrent(id: String, view: OfflineMarkdownView) = active[id]?.view === view

    fun retire(view: OfflineMarkdownView) {
        val entry = entries[view] ?: return
        if (entry.retirement != null) return
        if (active[entry.id] === entry) {
            active.remove(entry.id)
            if (activeRichBlock == entry.id) activeRichBlock = null
        }
        pending(view, true)
        val retirement = view.releaseEditor()
        entry.retirement = retirement
        scope.launch {
            try { retirement.await() }
            catch (_: Exception) { review(entry.id, REVIEW_MESSAGE) }
            finally { completeRetirement(entry) }
        }
    }
    fun select(anchor: NoteInsertion) { selectedBlock = anchor.blockId; insertions[anchor.blockId] = anchor; activeRichBlock = null }
    fun remember(anchor: NoteInsertion, focused: Boolean, marks: Set<String>) {
        insertions[anchor.blockId] = anchor
        if (focused) { selectedBlock = anchor.blockId; activeRichBlock = anchor.blockId; activeMarks = marks }
        else if (activeRichBlock == anchor.blockId) activeRichBlock = null
    }
    fun pending(view: OfflineMarkdownView, value: Boolean) {
        if (view in entries) pendingViews = if (value) pendingViews + view else pendingViews - view
    }
    fun review(id: String, reason: String) { reviews[id] = reason }
    fun reviewReason(id: String): String? = reviews[id]
    fun canAcknowledge(id: String): Boolean = pendingViews.none { entries[it]?.id == id }
    fun acknowledge(id: String) { check(canAcknowledge(id)); reviews.remove(id) }
    private fun completeRetirement(entry: Entry) { pending(entry.view, false); entries.remove(entry.view) }

    fun insertion(note: NoteDocument): NoteInsertion {
        val block = note.blocks.firstOrNull { it.string("id") == selectedBlock && it.string("kind") == "markdown" }
            ?: note.blocks.last { it.string("kind") == "markdown" }
        val text = block.string("text")
        return insertions[block.string("id")]?.takeIf { it.expectedText == null || it.expectedText == text }
            ?: NoteInsertion(block.string("id"), text.length)
    }

    suspend fun <T> withCommitted(before: () -> Unit = {}, action: () -> T): T = gate.withLock {
        committing = true
        try {
            before()
            val visited = mutableSetOf<OfflineMarkdownView>()
            var failed = false
            while (true) {
                val batch = entries.values.filter { it.view !in visited }.toList()
                if (batch.isEmpty()) break
                for (entry in batch) {
                    visited.add(entry.view)
                    try {
                        val retirement = entry.retirement
                        if (retirement != null) {
                            try { retirement.await() } finally { completeRetirement(entry) }
                        } else { entry.activated.await(); entry.view.commitAndFreeze() }
                    } catch (_: Exception) { review(entry.id, REVIEW_MESSAGE); failed = true }
                }
            }
            check(!failed && !pending) { "Editor input requires review" }
            action()
        } finally {
            entries.values.filter { it.retirement == null }.forEach { it.view.resumeEditing() }
            committing = false
        }
    }

    fun command(name: String) { active[activeRichBlock]?.view?.command(name) }

    companion object {
        const val REVIEW_MESSAGE = "有一段正文未能确认输入完成。下方保留了最近收到的 Markdown 原文；可能未送达的字符无法自动恢复。请核对或补充后确认继续。"
        fun saver(scope: CoroutineScope) = Saver<NoteEditorCoordinator, ArrayList<String>>(
            save = { editor -> ArrayList((editor.reviews.keys + editor.pendingViews.mapNotNull { editor.entries[it]?.id }).toSet()) },
            restore = { NoteEditorCoordinator(scope, it.toSet()) }
        )
    }
}

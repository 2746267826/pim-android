package com.pim.app.notes

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class NoteEditHistory(
    val noteId: String,
    val snapshots: List<String>,
    val cursor: Int,
    val groupKey: String? = null,
    val groupAtMillis: Long = 0,
) {
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < snapshots.lastIndex

    fun current(): JsonObject = Json.decodeFromString(snapshots[cursor])

    fun record(note: NoteDocument, key: String, nowMillis: Long): NoteEditHistory {
        val next = content(note)
        val canCoalesce = groupKey == key && nowMillis >= groupAtMillis && nowMillis - groupAtMillis <= COALESCE_MILLIS && cursor == snapshots.lastIndex
        val changed = if (canCoalesce) snapshots.take(cursor) + next
        else snapshots.take(cursor + 1) + next
        return bounded(copy(
            snapshots = changed,
            cursor = changed.lastIndex,
            groupKey = key,
            groupAtMillis = nowMillis,
        ))
    }

    fun movedTo(index: Int) = copy(cursor = index, groupKey = null, groupAtMillis = 0)

    fun reconcile(note: NoteDocument): NoteEditHistory {
        if (current() == JsonObject(note.source - "revision")) return this
        val recovered = snapshots.take(cursor + 1) + content(note)
        return bounded(copy(snapshots = recovered, cursor = recovered.lastIndex, groupKey = null, groupAtMillis = 0))
    }

    fun validate() {
        require(validNoteId(noteId) && snapshots.isNotEmpty() && cursor in snapshots.indices)
        require(snapshots.all { snapshot ->
            runCatching {
                val source = Json.decodeFromString<JsonObject>(snapshot)
                source.string("id") == noteId && !source.containsKey("revision")
            }.getOrDefault(false)
        })
        require(groupAtMillis >= 0)
    }

    companion object {
        const val MAX_SNAPSHOTS = 60
        const val MAX_HISTORY_BYTES = 12 * 1024 * 1024
        const val COALESCE_MILLIS = 1_200L

        fun initial(note: NoteDocument) = NoteEditHistory(note.id, listOf(content(note)), 0)

        private fun content(note: NoteDocument): String =
            JsonObject(note.source - "revision").toString()

        private fun bounded(history: NoteEditHistory): NoteEditHistory {
            var items = history.snapshots
            var cursor = history.cursor
            while (items.size > 1 && (items.size > MAX_SNAPSHOTS || items.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_HISTORY_BYTES)) {
                items = items.drop(1)
                cursor = (cursor - 1).coerceAtLeast(0)
            }
            return history.copy(snapshots = items, cursor = cursor)
        }
    }
}

internal class NoteEditHistoryStore(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun read(id: String): NoteEditHistory? {
        require(validNoteId(id))
        val file = file(id)
        if (!file.isFile && !File(file.path + ".bak").isFile) return null
        return json.decodeFromString<NoteEditHistory>(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            .also { require(it.noteId == id); it.validate() }
    }

    fun save(history: NoteEditHistory) {
        history.validate()
        val target = file(history.noteId)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(history).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun file(id: String) = File(root, "$id.json").also { require(validNoteId(id)) }
}

internal fun NoteDocument.withHistoryContent(content: JsonObject): NoteDocument =
    copy(source = JsonObject(content + ("revision" to JsonPrimitive(revision + 1)))).also { it.validate() }

internal fun NoteDocument.historyEditKey(next: NoteDocument): String {
    if (source == next.source) return ""
    val content = JsonObject(source - "revision")
    val nextContent = JsonObject(next.source - "revision")
    if (title != next.title && JsonObject(content - "title") == JsonObject(nextContent - "title")) return "title"
    val before = blocks
    val after = next.blocks
    if (before.size == after.size && before.map { it.string("id") } == after.map { it.string("id") }) {
        val changed = before.indices.filter { before[it] != after[it] }
        if (changed.size == 1) {
            val index = changed.single()
            val old = before[index]
            val new = after[index]
            if (old.string("kind") == "markdown" && new.string("kind") == "markdown" &&
                JsonObject(old - "text") == JsonObject(new - "text")) return "markdown:${old.string("id")}"
            if (old.string("kind") == "ink" && new.string("kind") == "ink" &&
                JsonObject(old - "ink") == JsonObject(new - "ink")) return "ink:${old.string("id")}"
        }
    }
    return "document"
}

package com.pim.app.notes

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
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
    val revision: Long = 0,
) {
    val canUndo get() = cursor > 0
    val canRedo get() = cursor < snapshots.lastIndex

    fun current(): JsonObject = Json.decodeFromString(snapshots[cursor])

    fun record(note: NoteDocument, key: String, nowMillis: Long): NoteEditHistory {
        require(canStore(note)) { "Note exceeds the bounded edit-history budget" }
        val next = content(note)
        val canCoalesce = groupKey == key && nowMillis >= groupAtMillis && nowMillis - groupAtMillis <= COALESCE_MILLIS && cursor == snapshots.lastIndex
        val changed = if (canCoalesce) snapshots.take(cursor) + next
        else snapshots.take(cursor + 1) + next
        return bounded(copy(
            snapshots = changed,
            cursor = changed.lastIndex,
            groupKey = key,
            groupAtMillis = nowMillis,
            revision = note.revision,
        ))
    }

    fun movedTo(index: Int, boundRevision: Long = revision) =
        copy(cursor = index, groupKey = null, groupAtMillis = 0, revision = boundRevision)

    fun matches(note: NoteDocument): Boolean =
        noteId == note.id && revision == note.revision && current() == JsonObject(note.source - "revision")

    fun reconcile(note: NoteDocument): NoteEditHistory {
        if (current() == JsonObject(note.source - "revision")) return copy(revision = note.revision)
        val recovered = snapshots.take(cursor + 1) + content(note)
        return bounded(copy(
            snapshots = recovered,
            cursor = recovered.lastIndex,
            groupKey = null,
            groupAtMillis = 0,
            revision = note.revision,
        ))
    }

    fun validate() {
        require(validNoteId(noteId) && snapshots.isNotEmpty() && snapshots.size <= MAX_SNAPSHOTS && cursor in snapshots.indices)
        require(revision >= 0)
        require(snapshots.all { snapshot ->
            runCatching {
                val source = Json.decodeFromString<JsonObject>(snapshot)
                source.string("id") == noteId && !source.containsKey("revision")
            }.getOrDefault(false)
        })
        require(groupAtMillis >= 0)
        require(snapshots.sumOf { it.toByteArray(Charsets.UTF_8).size } <= MAX_HISTORY_BYTES)
        require(snapshots.all { it.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES })
        require(encodedSize(this) <= MAX_ENCODED_BYTES)
    }

    companion object {
        const val MAX_SNAPSHOTS = 60
        const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
        const val MAX_HISTORY_BYTES = 12 * 1024 * 1024
        const val MAX_ENCODED_BYTES = 16 * 1024 * 1024
        const val COALESCE_MILLIS = 1_200L

        fun initial(note: NoteDocument): NoteEditHistory {
            require(canStore(note)) { "Note exceeds the bounded edit-history budget" }
            return NoteEditHistory(note.id, listOf(content(note)), 0, revision = note.revision)
        }

        fun initialOrNull(note: NoteDocument): NoteEditHistory? =
            if (canStore(note)) initial(note) else null

        fun canStore(note: NoteDocument): Boolean {
            val snapshot = content(note)
            if (snapshot.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES) return false
            return encodedSize(NoteEditHistory(note.id, listOf(snapshot), 0, revision = note.revision)) <= MAX_ENCODED_BYTES
        }

        private fun content(note: NoteDocument): String =
            JsonObject(note.source - "revision").toString()

        private val encodedJson = Json { encodeDefaults = true }

        private fun encodedSize(history: NoteEditHistory): Int =
            encodedJson.encodeToString(history).toByteArray(Charsets.UTF_8).size

        private fun bounded(history: NoteEditHistory): NoteEditHistory {
            var items = history.snapshots
            var cursor = history.cursor
            while (items.size > 1 && (
                    items.size > MAX_SNAPSHOTS ||
                        items.sumOf { it.toByteArray(Charsets.UTF_8).size } > MAX_HISTORY_BYTES ||
                        encodedSize(history.copy(snapshots = items, cursor = cursor)) > MAX_ENCODED_BYTES
                    )
            ) {
                items = items.drop(1)
                cursor = (cursor - 1).coerceAtLeast(0)
            }
            return history.copy(snapshots = items, cursor = cursor)
        }
    }
}

internal class NoteEditHistoryStore(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun exists(id: String): Boolean {
        require(validNoteId(id))
        val file = file(id)
        return file.isFile || File(file.path + ".bak").isFile
    }

    fun read(id: String): NoteEditHistory? {
        require(validNoteId(id))
        val file = file(id)
        if (!file.isFile && !File(file.path + ".bak").isFile) return null
        val bytes = AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= NoteEditHistory.MAX_ENCODED_BYTES) {
                    "Edit history exceeds the bounded read budget"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return json.decodeFromString<NoteEditHistory>(bytes.toString(Charsets.UTF_8))
            .also { require(it.noteId == id); it.validate() }
    }

    fun save(history: NoteEditHistory) {
        history.validate()
        val target = file(history.noteId)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            val encoded = json.encodeToString(history).toByteArray(Charsets.UTF_8)
            require(encoded.size <= NoteEditHistory.MAX_ENCODED_BYTES) {
                "Edit history exceeds the bounded write budget"
            }
            stream.write(encoded)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun delete(id: String) {
        require(validNoteId(id))
        AtomicFile(file(id)).delete()
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

package com.pim.app.notes

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal val NoteJson = Json { encodeDefaults = true }
internal fun newNoteId(): String = UUID.randomUUID().toString()
internal fun validNoteId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

/** Blocks remain JSON objects so unrecognized extension payloads survive edits verbatim. */
data class NoteDocument(val source: JsonObject) {
    val id: String get() = source.string("id")
    val title: String get() = source.string("title")
    val revision: Long get() = source.getValue("revision").jsonPrimitive.long
    val blocks: List<JsonObject> get() = source.getValue("blocks").jsonArray.map { it.jsonObject }

    fun withTitle(title: String) = copy(source = JsonObject(source + ("title" to JsonPrimitive(title))))
    fun withBlocks(blocks: List<JsonObject>) = copy(source = JsonObject(source + ("blocks" to JsonArray(blocks))))
    fun nextRevision() = copy(source = JsonObject(source + ("revision" to JsonPrimitive(revision + 1))))
    fun replaceBlock(id: String, transform: (JsonObject) -> JsonObject): NoteDocument {
        require(blocks.any { it.string("id") == id })
        return withBlocks(blocks.map { if (it.string("id") == id) transform(it) else it })
    }

    fun insert(anchor: NoteInsertion, block: JsonObject): NoteDocument {
        val index = blocks.indexOfFirst { it.string("id") == anchor.blockId && it.string("kind") == "markdown" }
        require(index >= 0) { "Insertion anchor no longer exists" }
        val old = blocks[index]
        val text = old.string("text")
        anchor.validate()
        require(anchor.expectedText == null || anchor.expectedText == text) { "Insertion source changed; original material retained" }
        val offset = anchor.offset.coerceIn(0, text.length)
        val before = JsonObject(old + ("text" to JsonPrimitive(anchor.before ?: text.substring(0, offset))))
        val after = markdownBlock(anchor.after ?: text.substring(offset))
        return withBlocks(blocks.take(index) + listOf(before, block, after) + blocks.drop(index + 1))
    }

    fun validate() {
        require(source.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported note version" }
        require(validNoteId(id) && revision >= 0 && title.length <= 500)
        require(blocks.isNotEmpty() && blocks.map { it.string("id") }.distinct().size == blocks.size)
        blocks.forEach {
            require(validNoteId(it.string("id")))
            when (it.string("kind")) {
                "markdown" -> it.string("text")
                "photo" -> require(validNoteId(it.string("assetId")))
                "ink" -> inkOf(it).validate()
            }
        }
    }

    companion object {
        fun create() = NoteDocument(buildJsonObject {
            put("schemaVersion", 1); put("id", newNoteId()); put("title", "未命名记录")
            put("revision", 0); put("createdAtMillis", System.currentTimeMillis())
            put("blocks", JsonArray(listOf(markdownBlock(""))))
        })
        fun decode(text: String) = NoteDocument(NoteJson.parseToJsonElement(text).jsonObject).also { it.validate() }
    }
}

@Serializable
data class NoteInsertion(
    val blockId: String, val offset: Int,
    val expectedText: String? = null, val before: String? = null, val after: String? = null
) {
    fun validate() {
        require(validNoteId(blockId) && offset >= 0)
        require(listOf(expectedText, before, after).count { it != null } in setOf(0, 3))
    }
}
fun markdownBlock(text: String) = buildJsonObject {
    put("id", newNoteId()); put("kind", "markdown"); put("text", text)
}
fun inkBlock(width: Float) = buildJsonObject {
    put("id", newNoteId()); put("kind", "ink")
    put("ink", NoteJson.encodeToJsonElement(InkData(width = width.coerceIn(160f, 720f))))
}
fun inkOf(block: JsonObject): InkData = NoteJson.decodeFromJsonElement(block.getValue("ink"))
fun withInk(block: JsonObject, ink: InkData) = JsonObject(block + ("ink" to NoteJson.encodeToJsonElement(ink)))

@Serializable data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)
@Serializable data class InkStroke(val points: List<InkPoint>, val width: Float = 2.5f)
@Serializable data class InkData(val width: Float = 320f, val height: Float = 240f, val strokes: List<InkStroke> = emptyList()) {
    fun validate() {
        require(width.isFinite() && height.isFinite() && width in 80f..2048f && height in 80f..4096f)
        strokes.forEach { stroke ->
            require(stroke.width.isFinite() && stroke.width in 0.5f..24f && stroke.points.isNotEmpty())
            stroke.points.forEach { p ->
                require(p.x.isFinite() && p.y.isFinite() && p.pressure.isFinite())
                require(p.x in 0f..width && p.y in 0f..height && p.pressure in 0f..1f)
            }
        }
    }

    // Keep all original points. A smaller viewport may scale the whole card, never its saved coordinates.
    fun resized(newWidth: Float, newHeight: Float, availableWidth: Float): InkData {
        val minWidth = maxOf(80f, (strokes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f) + 12f).coerceAtMost(2048f)
        val minHeight = maxOf(80f, (strokes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f) + 12f).coerceAtMost(4096f)
        return copy(width = newWidth.coerceIn(minWidth, maxOf(minWidth, availableWidth).coerceAtMost(2048f)),
            height = newHeight.coerceIn(minHeight, 4096f))
    }

    fun erasedBy(eraser: List<InkPoint>): InkData {
        val cells = HashSet<Pair<Int, Int>>()
        fun visit(points: List<InkPoint>, action: (Int, Int) -> Unit) {
            points.forEachIndexed { index, point ->
                val before = points.getOrElse(index - 1) { point }
                val steps = maxOf(1, kotlin.math.ceil(kotlin.math.hypot(point.x - before.x, point.y - before.y) / 6f).toInt())
                for (step in 0..steps) {
                    val t = step.toFloat() / steps
                    action(((before.x + (point.x - before.x) * t) / 12f).toInt(), ((before.y + (point.y - before.y) * t) / 12f).toInt())
                }
            }
        }
        visit(eraser) { x, y -> for (dx in -1..1) for (dy in -1..1) cells.add(x + dx to y + dy) }
        return copy(strokes = strokes.filterNot { stroke ->
            var hit = false
            visit(stroke.points) { x, y -> if (x to y in cells) hit = true }
            hit
        })
    }
}

package com.pim.app.notes

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val NoteJson = Json { encodeDefaults = true }
internal fun newNoteId(): String = UUID.randomUUID().toString()
internal fun validNoteId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal const val INK_LEGACY_COLOR = "#263b85"
private val InkColorPattern = Regex("""^#[0-9a-fA-F]{6}$""")
private val InkRootKeys = setOf("width", "height", "strokes", "strokeIds", "strokeStyles")
private val InkStrokeKeys = setOf("points", "width", "color")
private val InkPointKeys = setOf("x", "y", "pressure")
private val InkStyleKeys = setOf("color", "width")

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
    put("ink", encodeInk(InkData(width = width.coerceIn(160f, 720f))))
}
fun inkOf(block: JsonObject): InkData = parseInk(
    block.getValue("ink") as? JsonObject ?: throw IllegalArgumentException("Ink payload must be an object")
)
fun withInk(block: JsonObject, ink: InkData) = JsonObject(block + ("ink" to encodeInk(ink)))

data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

data class InkStroke(
    val points: List<InkPoint>,
    val width: Float = 2.5f,
    val color: String? = null,
    val id: String? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
    val styleExtensions: JsonObject = JsonObject(emptyMap()),
)

data class InkData(
    val width: Float = 320f,
    val height: Float = 240f,
    val strokes: List<InkStroke> = emptyList(),
    val extensions: JsonObject = JsonObject(emptyMap()),
    val hasStrokeIds: Boolean = false,
    val hasStrokeStyles: Boolean = false,
) {
    fun validate() {
        require(width.isFinite() && height.isFinite() && width in 80f..2048f && height in 80f..4096f)
        require(strokes.size <= MAX_STROKES) { "Too many ink strokes" }
        strokes.forEach { stroke ->
            require(stroke.width.isFinite() && stroke.width in 0.5f..24f && stroke.points.isNotEmpty())
            stroke.color?.let(::normalizedInkColor)
            stroke.id?.let { require(validInkStrokeId(it)) { "Invalid ink stroke identity" } }
            stroke.points.forEach { p ->
                require(p.x.isFinite() && p.y.isFinite() && p.pressure.isFinite())
                require(p.x in 0f..width && p.y in 0f..height && p.pressure in 0f..1f)
            }
        }
        if (hasStrokeIds) {
            val ids = strokes.map {
                requireNotNull(it.id) { "Missing ink stroke identity" }.lowercase()
            }
            require(ids.distinct().size == ids.size) { "Duplicate ink stroke identity" }
        } else {
            require(strokes.all { it.id == null }) { "Unexpected ink stroke identity" }
        }
        if (hasStrokeStyles) {
            require(strokes.all { it.color != null }) { "Missing ink stroke style" }
        }
    }

    /**
     * Append a stroke without changing the legacy JSON shape unless the source snapshot
     * already opted into the Web D04 identity/style arrays.
     */
    fun appendStroke(points: List<InkPoint>, styleColor: String? = null): InkData {
        require(points.isNotEmpty())
        return copy(strokes = strokes + InkStroke(
            points = points,
            color = if (hasStrokeStyles) normalizedInkColor(styleColor ?: INK_LEGACY_COLOR) else null,
            id = if (hasStrokeIds) newNoteId() else null,
        ))
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

    private companion object {
        const val MAX_STROKES = 10_000
    }
}

private data class ParsedInkStyle(
    val color: String,
    val width: Float,
    val extensions: JsonObject,
)

private fun parseInk(ink: JsonObject): InkData {
    val width = ink.requiredFiniteNumber("width", "画布宽度")
    val height = ink.requiredFiniteNumber("height", "画布高度")
    require(width in 80f..2048f && height in 80f..4096f) { "画布尺寸超出 schema-v1 范围" }

    val rawStrokes = ink["strokes"] as? JsonArray ?: throw IllegalArgumentException("笔画列表无效")
    require(rawStrokes.size <= 10_000) { "笔画数量无效" }

    val strokeIds = ink["strokeIds"]?.let { element ->
        val array = element as? JsonArray ?: throw IllegalArgumentException("笔迹身份无效")
        require(array.size == rawStrokes.size) { "笔迹身份数量必须与笔画一致" }
        val used = HashSet<String>()
        array.map { item ->
            val value = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("笔迹身份无效")
            val normalized = normalizedInkStrokeId(value)
            require(used.add(normalized)) { "笔迹身份不可重复" }
            normalized
        }
    }

    val strokeStyles = ink["strokeStyles"]?.let { element ->
        val array = element as? JsonArray ?: throw IllegalArgumentException("笔迹样式无效")
        require(array.size == rawStrokes.size) { "笔迹样式数量必须与笔画一致" }
        array.map { item ->
            val raw = item as? JsonObject ?: throw IllegalArgumentException("笔迹样式必须与笔画一一对应")
            val color = raw.requiredInkColor()
            val strokeWidth = raw.requiredFiniteNumber("width", "笔画宽度")
            require(strokeWidth in 0.5f..24f) { "笔画宽度超出 schema-v1 范围" }
            ParsedInkStyle(color, strokeWidth, JsonObject(raw.filterKeys { it !in InkStyleKeys }))
        }
    }

    val strokes = rawStrokes.mapIndexed { index, element ->
        val raw = element as? JsonObject ?: throw IllegalArgumentException("笔画必须包含坐标点对象")
        val rawPoints = raw["points"] as? JsonArray ?: throw IllegalArgumentException("笔画必须包含坐标点")
        require(rawPoints.isNotEmpty()) { "笔画必须包含坐标点" }
        val inlineWidth = raw["width"]?.let {
            val value = it.asFiniteNumber("笔画宽度")
            require(value in 0.5f..24f) { "笔画宽度超出 schema-v1 范围" }
            value
        }
        val inlineColor = raw["color"]?.let {
            normalizedInkColor((it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
                ?: throw IllegalArgumentException("笔迹颜色无效"))
        }
        val style = strokeStyles?.get(index)
        if (style != null) {
            require(inlineColor == null || inlineColor == style.color) { "笔迹颜色与样式数组不一致" }
            require(inlineWidth == null || kotlin.math.abs(inlineWidth - style.width) <= 1e-6f) {
                "笔画宽度与样式数组不一致"
            }
        }
        val points = rawPoints.map { pointElement ->
            val point = pointElement as? JsonObject ?: throw IllegalArgumentException("笔迹坐标点格式无效")
            val x = point.requiredFiniteNumber("x", "笔迹 X 坐标")
            val y = point.requiredFiniteNumber("y", "笔迹 Y 坐标")
            val pressure = if (point.containsKey("pressure")) {
                point.requiredFiniteNumber("pressure", "笔迹压力")
            } else {
                1f
            }
            require(x in 0f..width && y in 0f..height && pressure in 0f..1f) {
                "笔迹坐标或压力超出 schema-v1 范围"
            }
            InkPoint(x, y, pressure, JsonObject(point.filterKeys { it !in InkPointKeys }))
        }
        InkStroke(
            points = points,
            width = style?.width ?: inlineWidth ?: 2.5f,
            color = style?.color ?: inlineColor,
            id = strokeIds?.get(index),
            extensions = JsonObject(raw.filterKeys { it !in InkStrokeKeys }),
            styleExtensions = style?.extensions ?: JsonObject(emptyMap()),
        )
    }
    return InkData(
        width = width,
        height = height,
        strokes = strokes,
        extensions = JsonObject(ink.filterKeys { it !in InkRootKeys }),
        hasStrokeIds = strokeIds != null,
        hasStrokeStyles = strokeStyles != null,
    ).also(InkData::validate)
}

private fun encodeInk(ink: InkData): JsonObject {
    ink.validate()
    val root = LinkedHashMap<String, JsonElement>()
    ink.extensions.forEach { (key, value) -> if (key !in InkRootKeys) root[key] = value }
    root["width"] = JsonPrimitive(ink.width)
    root["height"] = JsonPrimitive(ink.height)
    if (ink.hasStrokeIds) {
        root["strokeIds"] = JsonArray(ink.strokes.map { JsonPrimitive(checkNotNull(it.id).lowercase()) })
    }
    root["strokes"] = JsonArray(ink.strokes.map { stroke ->
        val raw = LinkedHashMap<String, JsonElement>()
        stroke.extensions.forEach { (key, value) -> if (key !in InkStrokeKeys) raw[key] = value }
        raw["points"] = JsonArray(stroke.points.map { point ->
            val encoded = LinkedHashMap<String, JsonElement>()
            point.extensions.forEach { (key, value) -> if (key !in InkPointKeys) encoded[key] = value }
            encoded["x"] = JsonPrimitive(point.x)
            encoded["y"] = JsonPrimitive(point.y)
            encoded["pressure"] = JsonPrimitive(point.pressure)
            JsonObject(encoded)
        })
        raw["width"] = JsonPrimitive(stroke.width)
        stroke.color?.let { raw["color"] = JsonPrimitive(normalizedInkColor(it)) }
        JsonObject(raw)
    })
    if (ink.hasStrokeStyles) {
        root["strokeStyles"] = JsonArray(ink.strokes.map { stroke ->
            val style = LinkedHashMap<String, JsonElement>()
            stroke.styleExtensions.forEach { (key, value) -> if (key !in InkStyleKeys) style[key] = value }
            style["color"] = JsonPrimitive(normalizedInkColor(checkNotNull(stroke.color)))
            style["width"] = JsonPrimitive(stroke.width)
            JsonObject(style)
        })
    }
    return JsonObject(root)
}

private fun JsonObject.requiredFiniteNumber(key: String, label: String): Float =
    (get(key) ?: throw IllegalArgumentException("$label 缺失")).asFiniteNumber(label)

private fun JsonElement.asFiniteNumber(label: String): Float {
    val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException("$label 无效")
    require(!primitive.isString) { "$label 无效" }
    val value = primitive.floatOrNull ?: throw IllegalArgumentException("$label 无效")
    require(value.isFinite()) { "$label 无效" }
    return value
}

private fun JsonObject.requiredInkColor(): String {
    val value = (get("color") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("笔迹颜色无效")
    return normalizedInkColor(value)
}

private fun normalizedInkColor(value: String): String {
    val trimmed = value.trim()
    require(trimmed.matches(InkColorPattern)) { "笔迹颜色无效：必须使用 #RRGGBB" }
    return trimmed.lowercase()
}

private fun normalizedInkStrokeId(value: String): String {
    val normalized = value.lowercase()
    require(validInkStrokeId(normalized)) { "笔迹身份无效" }
    return normalized
}

private fun validInkStrokeId(value: String): Boolean = validNoteId(value.lowercase())

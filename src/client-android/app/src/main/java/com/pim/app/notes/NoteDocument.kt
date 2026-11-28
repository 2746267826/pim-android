package com.pim.app.notes

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val NoteJson = Json { encodeDefaults = true }
internal fun newNoteId(): String = UUID.randomUUID().toString()
internal fun validNoteId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal const val INK_LEGACY_COLOR = "#263b85"
internal const val INK_COLOR_INK = "#252b35"
internal const val INK_COLOR_RED = "#b8373c"
internal const val INK_COLOR_BLUE = "#245fba"
internal const val INK_COLOR_GREEN = "#216e4e"
internal const val INK_WIDTH_THIN = 1.8f
internal const val INK_WIDTH_MEDIUM = 3.4f
internal const val INK_WIDTH_COARSE = 6f
internal const val INK_DEFAULT_WIDTH = 2.5f
internal const val INK_ERASER_RADIUS = 12f
internal const val INK_MAX_POINTS = 50_000
internal const val INK_MAX_STROKES = 10_000

internal val InkColorOptions = listOf(INK_COLOR_INK, INK_COLOR_RED, INK_COLOR_BLUE, INK_COLOR_GREEN)
internal val InkWidthOptions = listOf(INK_WIDTH_THIN, INK_WIDTH_MEDIUM, INK_WIDTH_COARSE)
private val InkColorPattern = Regex("""#[0-9A-Fa-f]{6}""")

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

    /**
     * Applies the single-document editor's ordered projection without letting it rewrite
     * material identity or opaque extension payloads. Markdown blocks keep their extra
     * fields; material blocks are always taken verbatim from the stored document.
     */
    fun withDocumentBlocks(edited: List<JsonObject>): NoteDocument {
        require(edited.isNotEmpty() && edited.map { it.string("id") }.distinct().size == edited.size)
        require(edited.any { it.string("kind") == "markdown" }) { "A note requires an editable Markdown block" }
        val originalById = blocks.associateBy { it.string("id") }
        val originalLayout = blocks.map { it.string("kind") to it.string("id") }
        val editedLayout = edited.map { it.string("kind") to it.string("id") }
        require(editedLayout == originalLayout) {
            "Block order, material position and identity belong to the native document, not the editor"
        }
        val next = edited.map { candidate ->
            val id = candidate.string("id")
            val kind = candidate.string("kind")
            val previous = checkNotNull(originalById[id])
            if (kind != "markdown") {
                previous
            } else {
                require(previous.string("kind") == "markdown")
                JsonObject(previous + ("text" to JsonPrimitive(candidate.string("text"))))
            }
        }
        return withBlocks(next).also(NoteDocument::validate)
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
fun inkOf(block: JsonObject): InkData = parseInk(block.getValue("ink") as? JsonObject
    ?: throw IllegalArgumentException("Ink payload must be an object"))
fun withInk(block: JsonObject, ink: InkData) = JsonObject(block + ("ink" to encodeInk(ink)))

data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val extras: JsonObject = JsonObject(emptyMap()),
)

enum class InkEraserMode { Partial, WholeStroke }

data class InkStroke(
    val points: List<InkPoint>,
    val width: Float = INK_DEFAULT_WIDTH,
    val color: String = INK_LEGACY_COLOR,
    val id: String? = null,
    val extras: JsonObject = JsonObject(emptyMap()),
    val styleExtras: JsonObject = JsonObject(emptyMap()),
)

data class InkData(
    val width: Float = 320f,
    val height: Float = 240f,
    val strokes: List<InkStroke> = emptyList(),
    val extras: JsonObject = JsonObject(emptyMap()),
) {
    internal fun limitViolation(additionalStrokes: Int = 0, additionalPoints: Int = 0): InkLimitViolation? = when {
        strokes.size.toLong() + additionalStrokes > INK_MAX_STROKES.toLong() ->
            InkLimitViolation.TooManyStrokes
        strokes.sumOf { it.points.size.toLong() } + additionalPoints > INK_MAX_POINTS.toLong() ->
            InkLimitViolation.TooManyPoints
        else -> null
    }

    fun validate() {
        require(width.isFinite() && height.isFinite() && width in 80f..2048f && height in 80f..4096f)
        require(strokes.size <= INK_MAX_STROKES) { "笔画数量超过上限" }
        require(strokes.sumOf { it.points.size.toLong() } <= INK_MAX_POINTS.toLong()) { "笔迹点数超过上限" }
        strokes.forEach { stroke ->
            require(stroke.width.isFinite() && stroke.width in 0.5f..24f && stroke.points.isNotEmpty())
            require(stroke.color.matches(InkColorPattern)) { "Unsupported ink color" }
            stroke.points.forEach { p ->
                require(p.x.isFinite() && p.y.isFinite() && p.pressure.isFinite())
                require(p.x in 0f..width && p.y in 0f..height && p.pressure in 0f..1f)
            }
        }
        val identities = strokes.map { it.id }
        require(identities.all { it == null } || identities.all { it != null }) {
            "Stroke identities must be complete"
        }
        if (identities.all { it != null }) {
            val normalized = identities.filterNotNull()
            require(normalized.all(::validNoteId)) { "Invalid ink stroke identity" }
            require(normalized.distinct().size == normalized.size) { "Duplicate ink stroke identity" }
        }
    }

    // Keep all original points. A smaller viewport may scale the whole card, never its saved coordinates.
    fun resized(newWidth: Float, newHeight: Float, availableWidth: Float): InkData {
        val minWidth = maxOf(80f, (strokes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f) + 12f).coerceAtMost(2048f)
        val minHeight = maxOf(80f, (strokes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f) + 12f).coerceAtMost(4096f)
        return copy(width = newWidth.coerceIn(minWidth, maxOf(minWidth, availableWidth).coerceAtMost(2048f)),
            height = newHeight.coerceIn(minHeight, 4096f))
    }

    fun withStrokeIdentities(): InkData {
        val used = HashSet<String>()
        return copy(strokes = strokes.map { stroke ->
            val id = stroke.id?.let(::normalizedInkId) ?: run {
                var candidate = newNoteId()
                while (used.contains(candidate)) candidate = newNoteId()
                candidate
            }
            require(used.add(id)) { "Duplicate ink stroke identity" }
            stroke.copy(id = id)
        })
    }

    fun erasedBy(eraser: List<InkPoint>, mode: InkEraserMode = InkEraserMode.Partial): InkData {
        if (eraser.isEmpty()) return this
        // Keep untouched legacy strokes byte-for-byte equivalent until a write normalizes identities.
        val base = if (strokes.any { it.id != null }) withStrokeIdentities() else this
        val eraserSegments = inkPathSegments(eraser)
        val used = base.strokes.mapNotNull { it.id }.toHashSet()
        val next = mutableListOf<InkStroke>()
        var changed = false

        base.strokes.forEach { stroke ->
            val hitRadius = INK_ERASER_RADIUS + stroke.width / 2f
            if (mode == InkEraserMode.WholeStroke) {
                if (strokeTouchesPath(stroke, eraserSegments, hitRadius)) {
                    changed = true
                } else {
                    next += stroke
                }
                return@forEach
            }

            val pieces = splitInkStroke(stroke, eraserSegments, hitRadius)
            if (pieces.size == 1 && sameInkPoints(pieces.single(), stroke.points)) {
                next += stroke
            } else {
                changed = true
                pieces.forEachIndexed { index, points ->
                    val id = if (index == 0) stroke.id else stroke.id?.let {
                        var candidate = newNoteId()
                        while (used.contains(candidate)) candidate = newNoteId()
                        used += candidate
                        candidate
                    }
                    next += stroke.copy(points = points, id = id)
                }
            }
        }
        return if (changed) base.copy(strokes = next) else this
    }
}

internal enum class InkLimitViolation(val message: String) {
    TooManyStrokes("手写内容已达 10,000 笔上限，本次输入未写入。可先擦除或撤销后再继续。"),
    TooManyPoints("手写内容已达 50,000 点上限，本次输入未写入。可先擦除或撤销后再继续。"),
}

internal fun normalizedInkColor(value: String): String {
    val trimmed = value.trim()
    require(trimmed.matches(InkColorPattern)) { "Unsupported ink color" }
    return trimmed.lowercase()
}

private fun normalizedInkId(value: String): String {
    val normalized = value.lowercase()
    require(validNoteId(normalized)) { "Invalid ink stroke identity" }
    return normalized
}

private fun parseInk(ink: JsonObject): InkData {
    val width = ink.requiredFiniteNumber("width", "画布宽度")
    val height = ink.requiredFiniteNumber("height", "画布高度")
    require(width in 80f..2048f && height in 80f..4096f) { "画布尺寸超出 schema-v1 范围" }
    val rawStrokes = ink["strokes"] as? JsonArray ?: throw IllegalArgumentException("笔画列表无效")
    require(rawStrokes.size <= INK_MAX_STROKES) { "笔画数量无效" }
    var pointCount = 0

    val rawIds = ink["strokeIds"]
    val ids = if (rawIds == null) null else {
        val array = rawIds as? JsonArray ?: throw IllegalArgumentException("笔迹身份无效")
        require(array.size == rawStrokes.size) { "笔迹身份无效：数量必须与笔画一致" }
        val used = HashSet<String>()
        array.map { element ->
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("笔迹身份无效")
            val id = normalizedInkId(value)
            require(used.add(id)) { "笔迹身份无效：不可重复" }
            id
        }
    }

    val rawStyles = ink["strokeStyles"]
    val styles = if (rawStyles == null) null else {
        val array = rawStyles as? JsonArray ?: throw IllegalArgumentException("笔迹样式无效")
        require(array.size == rawStrokes.size) { "笔迹样式无效：数量必须与笔画一致" }
        array.map { element ->
            val raw = element as? JsonObject ?: throw IllegalArgumentException("笔迹样式必须与笔画一一对应")
            val color = normalizedInkColor(raw.requiredString("color", "笔迹颜色"))
            val strokeWidth = raw.requiredFiniteNumber("width", "笔画宽度")
            require(strokeWidth in 0.5f..24f) { "笔画宽度超出 schema-v1 范围" }
            ParsedInkStyle(color, strokeWidth, JsonObject(raw.filterKeys { it !in INK_STYLE_KEYS }))
        }
    }

    val strokes = rawStrokes.mapIndexed { index, element ->
        val raw = element as? JsonObject ?: throw IllegalArgumentException("笔画必须包含坐标点对象")
        val rawPoints = raw["points"] as? JsonArray ?: throw IllegalArgumentException("笔画必须包含坐标点")
        require(rawPoints.isNotEmpty()) { "笔画必须包含坐标点" }
        pointCount += rawPoints.size
        require(pointCount <= INK_MAX_POINTS) { "笔迹点数超过上限" }
        val inlineWidth = raw["width"]?.let {
            val value = it.asFiniteNumber("笔画宽度")
            require(value in 0.5f..24f) { "笔画宽度超出 schema-v1 范围" }
            value
        }
        val inlineColor = raw["color"]?.let {
            normalizedInkColor((it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
                ?: throw IllegalArgumentException("笔迹颜色无效"))
        }
        val style = styles?.get(index)
        if (style != null) {
            require(inlineColor == null || inlineColor == style.color) { "笔迹颜色与样式数组不一致" }
            require(inlineWidth == null || kotlin.math.abs(inlineWidth - style.width) <= 1e-6f) {
                "笔画宽度与样式数组不一致"
            }
        }
        val strokeWidth = style?.width ?: inlineWidth ?: INK_DEFAULT_WIDTH
        val strokeColor = style?.color ?: inlineColor ?: INK_LEGACY_COLOR
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
            InkPoint(x, y, pressure, JsonObject(point.filterKeys { it !in INK_POINT_KEYS }))
        }
        InkStroke(
            points = points,
            width = strokeWidth,
            color = strokeColor,
            id = ids?.get(index),
            extras = JsonObject(raw.filterKeys { it !in INK_STROKE_KEYS }),
            styleExtras = style?.extras ?: JsonObject(emptyMap()),
        )
    }
    return InkData(
        width = width,
        height = height,
        strokes = strokes,
        extras = JsonObject(ink.filterKeys { it !in INK_ROOT_KEYS }),
    ).also(InkData::validate)
}

private fun encodeInk(ink: InkData): JsonObject {
    // A write is the point where legacy strokes acquire the stable IDs required by the
    // shared schema. Reads keep them absent so an untouched old snapshot is not rewritten.
    val normalized = ink.withStrokeIdentities()
    normalized.validate()
    val ids = normalized.strokes.map { checkNotNull(it.id) }
    val root = LinkedHashMap<String, JsonElement>(normalized.extras.filterKeys { it !in INK_ROOT_KEYS })
    root["width"] = JsonPrimitive(normalized.width)
    root["height"] = JsonPrimitive(normalized.height)
    root["strokeIds"] = JsonArray(ids.map { JsonPrimitive(it) })
    root["strokes"] = JsonArray(normalized.strokes.map { stroke ->
        val raw = LinkedHashMap<String, JsonElement>(stroke.extras.filterKeys { it !in INK_STROKE_KEYS })
        raw["points"] = JsonArray(stroke.points.map { point ->
            val encoded = LinkedHashMap<String, JsonElement>(point.extras.filterKeys { it !in INK_POINT_KEYS })
            encoded["x"] = JsonPrimitive(point.x)
            encoded["y"] = JsonPrimitive(point.y)
            encoded["pressure"] = JsonPrimitive(point.pressure)
            JsonObject(encoded)
        })
        raw["width"] = JsonPrimitive(stroke.width)
        raw["color"] = JsonPrimitive(normalizedInkColor(stroke.color))
        JsonObject(raw)
    })
    root["strokeStyles"] = JsonArray(normalized.strokes.map { stroke ->
        val style = LinkedHashMap<String, JsonElement>(stroke.styleExtras.filterKeys { it !in INK_STYLE_KEYS })
        style["color"] = JsonPrimitive(normalizedInkColor(stroke.color))
        style["width"] = JsonPrimitive(stroke.width)
        JsonObject(style)
    })
    return JsonObject(root)
}

private val INK_ROOT_KEYS = setOf("width", "height", "strokes", "strokeIds", "strokeStyles")
private val INK_STROKE_KEYS = setOf("points", "width", "color")
private val INK_POINT_KEYS = setOf("x", "y", "pressure")
private val INK_STYLE_KEYS = setOf("color", "width")
private const val INK_GEOMETRY_EPSILON = 1e-5f

private data class ParsedInkStyle(val color: String, val width: Float, val extras: JsonObject)

private fun JsonObject.requiredFiniteNumber(key: String, label: String): Float =
    (get(key) ?: throw IllegalArgumentException("$label 缺失")).asFiniteNumber(label)

private fun JsonElement.asFiniteNumber(label: String): Float {
    val primitive = this as? JsonPrimitive ?: throw IllegalArgumentException("$label 无效")
    require(!primitive.isString) { "$label 无效" }
    val value = primitive.floatOrNull ?: throw IllegalArgumentException("$label 无效")
    require(value.isFinite()) { "$label 无效" }
    return value
}

private fun JsonObject.requiredString(key: String, label: String): String =
    ((get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content)
        ?: throw IllegalArgumentException("$label 无效")

private data class InkInterval(val from: Float, val to: Float)
private data class InkEraserSegment(val start: InkPoint, val end: InkPoint)

private fun inkPathSegments(points: List<InkPoint>): List<InkEraserSegment> = when (points.size) {
    0 -> emptyList()
    1 -> listOf(InkEraserSegment(points[0], points[0]))
    else -> points.zipWithNext { start, end -> InkEraserSegment(start, end) }
}

private fun strokeTouchesPath(
    stroke: InkStroke,
    eraser: List<InkEraserSegment>,
    radius: Float,
): Boolean {
    if (stroke.points.size == 1) {
        val point = stroke.points.single()
        return eraser.any { distanceToInkSegment(point, it.start, it.end) <= radius }
    }
    return stroke.points.zipWithNext().any { (start, end) ->
        erasableIntervals(start, end, eraser, radius).isNotEmpty()
    }
}

private fun splitInkStroke(
    stroke: InkStroke,
    eraser: List<InkEraserSegment>,
    radius: Float,
): List<List<InkPoint>> {
    val points = stroke.points
    if (points.size == 1) {
        val hit = eraser.any { distanceToInkSegment(points[0], it.start, it.end) <= radius }
        return if (hit) emptyList() else listOf(points)
    }
    val pieces = mutableListOf<MutableList<InkPoint>>()
    var current: MutableList<InkPoint>? = null
    for (index in 1 until points.size) {
        val start = points[index - 1]
        val end = points[index]
        val kept = complementInkIntervals(erasableIntervals(start, end, eraser, radius))
        for (interval in kept) {
            val first = interpolateInkPoint(start, end, interval.from)
            val last = interpolateInkPoint(start, end, interval.to)
            var active = current
            if (active == null) {
                active = mutableListOf(first)
                current = active
            } else if (!sameInkPosition(active.last(), first)) {
                pieces += active
                active = mutableListOf(first)
                current = active
            }
            appendInkPosition(active, last)
            if (interval.to < 1f - INK_GEOMETRY_EPSILON) {
                pieces += active
                current = null
            }
        }
    }
    current?.let { pieces += it }
    return pieces.map { it.toList() }.filter { it.isNotEmpty() }
}

private fun erasableIntervals(
    start: InkPoint,
    end: InkPoint,
    eraser: List<InkEraserSegment>,
    radius: Float,
): List<InkInterval> {
    if (radius <= 0f) return emptyList()
    var intervals = emptyList<InkInterval>()
    eraser.forEach { segment ->
        val next = inkSegmentCapsuleIntervals(start, end, segment.start, segment.end, radius)
        if (next.isNotEmpty()) intervals = mergeInkIntervals(intervals + next)
    }
    return intervals
}

private fun inkSegmentCapsuleIntervals(
    start: InkPoint,
    end: InkPoint,
    eraserStart: InkPoint,
    eraserEnd: InkPoint,
    radius: Float,
): List<InkInterval> {
    val vx = end.x - start.x
    val vy = end.y - start.y
    val lengthSquared = vx * vx + vy * vy
    if (lengthSquared <= INK_GEOMETRY_EPSILON) {
        return if (distanceToInkSegment(start, eraserStart, eraserEnd) <= radius) {
            listOf(InkInterval(0f, 1f))
        } else {
            emptyList()
        }
    }

    val wx = eraserEnd.x - eraserStart.x
    val wy = eraserEnd.y - eraserStart.y
    val eraserLengthSquared = wx * wx + wy * wy
    if (eraserLengthSquared <= INK_GEOMETRY_EPSILON) {
        return inkCircleInterval(start, end, eraserStart, radius)?.let(::listOf) ?: emptyList()
    }

    val projectionStart = (start.x - eraserStart.x) * wx + (start.y - eraserStart.y) * wy
    val projectionSpeed = vx * wx + vy * wy
    val breakpoints = linkedSetOf(0f, 1f)
    if (kotlin.math.abs(projectionSpeed) > INK_GEOMETRY_EPSILON) {
        val atStart = -projectionStart / projectionSpeed
        val atEnd = (eraserLengthSquared - projectionStart) / projectionSpeed
        if (atStart > INK_GEOMETRY_EPSILON && atStart < 1f - INK_GEOMETRY_EPSILON) breakpoints += atStart
        if (atEnd > INK_GEOMETRY_EPSILON && atEnd < 1f - INK_GEOMETRY_EPSILON) breakpoints += atEnd
    }
    val sorted = breakpoints.sorted()
    var intervals = emptyList<InkInterval>()
    for (index in 1 until sorted.size) {
        val from = sorted[index - 1]
        val to = sorted[index]
        if (to - from <= INK_GEOMETRY_EPSILON) continue
        val middle = (from + to) / 2f
        val projection = projectionStart + projectionSpeed * middle
        val interval = when {
            projection <= 0f -> inkCircleInterval(start, end, eraserStart, radius)
            projection >= eraserLengthSquared -> inkCircleInterval(start, end, eraserEnd, radius)
            else -> inkInfiniteLineInterval(start, end, eraserStart, wx, wy, eraserLengthSquared, radius)
        }
        interval?.let { clipped ->
            intersectInkInterval(clipped, InkInterval(from, to))?.let { intervals += it }
        }
    }
    return mergeInkIntervals(intervals)
}

private fun inkCircleInterval(
    start: InkPoint,
    end: InkPoint,
    center: InkPoint,
    radius: Float,
): InkInterval? {
    val vx = end.x - start.x
    val vy = end.y - start.y
    val dx = start.x - center.x
    val dy = start.y - center.y
    val a = vx * vx + vy * vy
    val b = 2f * (dx * vx + dy * vy)
    val c = dx * dx + dy * dy - radius * radius
    if (a <= INK_GEOMETRY_EPSILON) return if (c <= 0f) InkInterval(0f, 1f) else null
    val discriminant = b * b - 4f * a * c
    if (discriminant < 0f) return null
    val root = kotlin.math.sqrt(discriminant)
    return intersectInkInterval(
        InkInterval((-b - root) / (2f * a), (-b + root) / (2f * a)),
        InkInterval(0f, 1f),
    )
}

private fun inkInfiniteLineInterval(
    start: InkPoint,
    end: InkPoint,
    eraserStart: InkPoint,
    wx: Float,
    wy: Float,
    eraserLengthSquared: Float,
    radius: Float,
): InkInterval? {
    val startCross = wx * (start.y - eraserStart.y) - wy * (start.x - eraserStart.x)
    val speedCross = wx * (end.y - start.y) - wy * (end.x - start.x)
    val limit = radius * kotlin.math.sqrt(eraserLengthSquared)
    if (kotlin.math.abs(speedCross) <= INK_GEOMETRY_EPSILON) {
        return if (kotlin.math.abs(startCross) <= limit) InkInterval(0f, 1f) else null
    }
    val first = (-limit - startCross) / speedCross
    val second = (limit - startCross) / speedCross
    return InkInterval(kotlin.math.min(first, second), kotlin.math.max(first, second))
}

private fun intersectInkInterval(first: InkInterval, second: InkInterval): InkInterval? {
    val from = kotlin.math.max(first.from, second.from)
    val to = kotlin.math.min(first.to, second.to)
    return if (to - from > INK_GEOMETRY_EPSILON) InkInterval(from, to) else null
}

private fun mergeInkIntervals(intervals: List<InkInterval>): List<InkInterval> {
    val sorted = intervals
        .map { InkInterval(it.from.coerceIn(0f, 1f), it.to.coerceIn(0f, 1f)) }
        .filter { it.to - it.from > INK_GEOMETRY_EPSILON }
        .sortedBy { it.from }
    if (sorted.isEmpty()) return emptyList()
    val merged = mutableListOf(sorted.first())
    sorted.drop(1).forEach { interval ->
        val last = merged.last()
        if (interval.from <= last.to + INK_GEOMETRY_EPSILON) {
            merged[merged.lastIndex] = InkInterval(last.from, kotlin.math.max(last.to, interval.to))
        } else {
            merged += interval
        }
    }
    return merged
}

private fun complementInkIntervals(intervals: List<InkInterval>): List<InkInterval> {
    if (intervals.isEmpty()) return listOf(InkInterval(0f, 1f))
    val kept = mutableListOf<InkInterval>()
    var cursor = 0f
    intervals.forEach { interval ->
        if (interval.from > cursor + INK_GEOMETRY_EPSILON) kept += InkInterval(cursor, interval.from)
        cursor = kotlin.math.max(cursor, interval.to)
    }
    if (cursor < 1f - INK_GEOMETRY_EPSILON) kept += InkInterval(cursor, 1f)
    return kept
}

private fun distanceToInkSegment(point: InkPoint, start: InkPoint, end: InkPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= INK_GEOMETRY_EPSILON) {
        return kotlin.math.hypot(point.x - start.x, point.y - start.y)
    }
    val projection = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    return kotlin.math.hypot(point.x - (start.x + projection * dx), point.y - (start.y + projection * dy))
}

private fun interpolateInkPoint(start: InkPoint, end: InkPoint, amount: Float): InkPoint = when {
    amount <= INK_GEOMETRY_EPSILON -> start
    amount >= 1f - INK_GEOMETRY_EPSILON -> end
    else -> InkPoint(
        x = start.x + (end.x - start.x) * amount,
        y = start.y + (end.y - start.y) * amount,
        pressure = start.pressure + (end.pressure - start.pressure) * amount,
    )
}

private fun appendInkPosition(points: MutableList<InkPoint>, next: InkPoint) {
    if (points.isNotEmpty() && sameInkPosition(points.last(), next)) return
    points += next
}

private fun sameInkPosition(first: InkPoint, second: InkPoint): Boolean =
    kotlin.math.abs(first.x - second.x) <= INK_GEOMETRY_EPSILON &&
        kotlin.math.abs(first.y - second.y) <= INK_GEOMETRY_EPSILON

private fun sameInkPoints(first: List<InkPoint>, second: List<InkPoint>): Boolean =
    first.size == second.size && first.indices.all { index ->
        sameInkPosition(first[index], second[index]) &&
            kotlin.math.abs(first[index].pressure - second[index].pressure) <= INK_GEOMETRY_EPSILON
    }

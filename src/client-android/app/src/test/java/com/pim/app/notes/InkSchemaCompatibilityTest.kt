package com.pim.app.notes

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure JVM regression for the frozen ink-v1 shape shared by Android and Web D04 (#594). */
class InkSchemaCompatibilityTest {
    @Test fun webD04SnapshotDecodesItsIdentityStylesAndUnknownFieldsThenRoundTrips() {
        val note = NoteDocument.decode(resource("notes/ink-web-d04-v1.json"))
        val block = note.blocks.single()
        val ink = inkOf(block)

        assertEquals(listOf(INK_COLOR_RED, INK_COLOR_BLUE), ink.strokes.map { it.color })
        assertEquals(listOf(INK_WIDTH_THIN, INK_WIDTH_COARSE), ink.strokes.map { it.width })
        assertEquals(
            listOf(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            ),
            ink.strokes.map { it.id },
        )
        assertEquals(JsonPrimitive(true), ink.extras.getValue("webInkExtension").jsonObject.getValue("keep"))
        assertEquals("keep", ink.strokes[0].extras.string("futureStroke"))
        assertEquals("keep", ink.strokes[0].points[0].extras.string("futurePoint"))
        assertEquals("keep", ink.strokes[0].styleExtras.string("futureStyle"))
        assertEquals("keep", note.source.getValue("futureRoot").jsonObject.string("keep"))

        val written = withInk(block, ink)
        val writtenInk = written.getValue("ink").jsonObject
        assertEquals(
            JsonArray(ink.strokes.map { JsonPrimitive(checkNotNull(it.id)) }),
            writtenInk.getValue("strokeIds"),
        )
        assertEquals(2, writtenInk.getValue("strokeStyles").jsonArray.size)
        assertEquals(INK_COLOR_RED, writtenInk.getValue("strokes").jsonArray[0].jsonObject.string("color"))
        assertEquals(INK_WIDTH_COARSE, writtenInk.getValue("strokeStyles").jsonArray[1].jsonObject
            .getValue("width").jsonPrimitive.content.toFloat())

        val reopened = NoteDocument.decode(note.withBlocks(listOf(written)).source.toString())
        val reopenedInk = inkOf(reopened.blocks.single())
        assertEquals(ink.strokes.map { it.id }, reopenedInk.strokes.map { it.id })
        assertEquals(ink.strokes.map { it.color }, reopenedInk.strokes.map { it.color })
        assertEquals(ink.strokes.map { it.width }, reopenedInk.strokes.map { it.width })
        assertEquals(
            JsonPrimitive(true),
            reopenedInk.extras.getValue("webInkExtension").jsonObject.getValue("keep"),
        )
        assertEquals("keep", reopenedInk.strokes[0].styleExtras.string("futureStyle"))
        assertEquals("keep", reopenedInk.strokes[0].extras.string("futureStroke"))
        assertEquals("keep", reopenedInk.strokes[0].points[0].extras.string("futurePoint"))

        val material = NoteMaterialInput(newNoteId(), 1L, reopened.source, emptyList())
        assertEquals(
            reopened.source,
            NoteJson.decodeFromString<NoteMaterialInput>(NoteJson.encodeToString(material)).note,
        )
    }

    @Test fun legacySnapshotGetsStableDefaultsAndAcquiresIdsOnlyWhenWritten() {
        val note = NoteDocument.decode(resource("notes/ink-legacy-v1.json"))
        val block = note.blocks.single()
        val ink = inkOf(block)
        val legacy = ink.strokes.single()

        assertEquals(INK_LEGACY_COLOR, legacy.color)
        assertEquals(INK_DEFAULT_WIDTH, legacy.width)
        assertNull(legacy.id)
        assertEquals(JsonPrimitive(true), ink.extras.getValue("futureInk").jsonObject.getValue("keep"))
        assertEquals("keep", legacy.extras.string("futureStroke"))
        assertEquals("keep", legacy.points[0].extras.string("futurePoint"))
        assertEquals("keep", note.source.string("futureRoot"))

        val written = withInk(block, ink)
        val writtenInk = inkOf(written)
        assertEquals(INK_LEGACY_COLOR, writtenInk.strokes.single().color)
        assertEquals(INK_DEFAULT_WIDTH, writtenInk.strokes.single().width)
        assertNotNull(writtenInk.strokes.single().id)
        assertEquals(
            JsonArray(listOf(JsonPrimitive(checkNotNull(writtenInk.strokes.single().id)))),
            written.getValue("ink").jsonObject.getValue("strokeIds"),
        )
        assertEquals(1, written.getValue("ink").jsonObject.getValue("strokeStyles").jsonArray.size)
        assertEquals(JsonPrimitive(true), writtenInk.extras.getValue("futureInk").jsonObject.getValue("keep"))
        assertEquals("keep", writtenInk.strokes.single().extras.string("futureStroke"))
        assertEquals("keep", writtenInk.strokes.single().points[0].extras.string("futurePoint"))
    }

    @Test fun knownFieldsFailClosedInsteadOfBeingSilentlyIgnoredOrRewritten() {
        assertFalse(NoteJson.configuration.ignoreUnknownKeys)
        val note = NoteDocument.decode(resource("notes/ink-legacy-v1.json"))
        val block = note.blocks.single()
        val original = note.source.toString()

        fun replaceInk(transform: (JsonObject) -> JsonObject): String =
            note.replaceBlock(block.string("id")) { old ->
                JsonObject(old + ("ink" to transform(old.getValue("ink").jsonObject)))
            }.source.toString()

        val strokes = block.getValue("ink").jsonObject.getValue("strokes").jsonArray
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                JsonObject(ink + ("strokeIds" to JsonArray(listOf(
                    JsonPrimitive("gggggggg-gggg-4ggg-8ggg-gggggggggggg"),
                ))))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                JsonObject(ink + ("strokeStyles" to JsonArray(listOf(
                    buildJsonObject { put("color", "#b8373c"); put("width", 1.8) },
                ))))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                JsonObject(ink + mapOf(
                    "strokes" to JsonArray(listOf(
                        JsonObject(strokes[0].jsonObject + mapOf(
                            "color" to JsonPrimitive("#245fba"),
                            "width" to JsonPrimitive(6.0),
                        )),
                    )),
                    "strokeStyles" to JsonArray(listOf(
                        buildJsonObject { put("color", "#b8373c"); put("width", 1.8) },
                    )),
                ))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                JsonObject(ink + ("strokes" to JsonArray(listOf(
                    JsonObject(strokes[0].jsonObject + ("color" to JsonPrimitive("<svg>"))),
                ))))
            })
        }
        assertEquals(original, note.source.toString())
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}

package com.pim.app.notes

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        assertEquals("keep", block.string("futureBlock"))
        assertEquals(JsonPrimitive(true), note.source.getValue("futureRoot").jsonObject.getValue("keep"))

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
        val writtenStrokes = writtenInk.getValue("strokes").jsonArray
        writtenInk.getValue("strokeStyles").jsonArray.forEachIndexed { index, element ->
            val style = element.jsonObject
            val stroke = writtenStrokes[index].jsonObject
            assertEquals(style.string("color"), stroke.string("color"))
            assertEquals(style.getValue("width"), stroke.getValue("width"))
        }

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
        assertEquals("keep", reopened.blocks.single().string("futureBlock"))

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

    @Test fun missingStrokeColorFallsBackToTheFrozenLegacyColor() {
        assertEquals("#263b85", INK_LEGACY_COLOR)
        val block = buildJsonObject {
            put("id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            put("kind", "ink")
            put("ink", buildJsonObject {
                put("width", 320)
                put("height", 240)
                put("strokes", JsonArray(listOf(buildJsonObject {
                    put("points", JsonArray(listOf(buildJsonObject {
                        put("x", 20)
                        put("y", 30)
                    })))
                })))
            })
        }

        val ink = inkOf(block)
        assertEquals(INK_LEGACY_COLOR, ink.strokes.single().color)
        assertEquals(INK_DEFAULT_WIDTH, ink.strokes.single().width)

        val written = withInk(block, ink).getValue("ink").jsonObject
        val writtenStroke = written.getValue("strokes").jsonArray.single().jsonObject
        assertEquals(INK_LEGACY_COLOR, writtenStroke.string("color"))
        assertEquals(INK_DEFAULT_WIDTH, writtenStroke.getValue("width").jsonPrimitive.content.toFloat())
        assertEquals(
            INK_LEGACY_COLOR,
            written.getValue("strokeStyles").jsonArray.single().jsonObject.string("color"),
        )
    }

    @Test fun inlineOnlyColorAndWidthSurviveReencoding() {
        val block = buildJsonObject {
            put("id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            put("kind", "ink")
            put("ink", buildJsonObject {
                put("width", 320)
                put("height", 240)
                put("strokes", JsonArray(listOf(buildJsonObject {
                    put("color", "#245fba")
                    put("width", 6)
                    put("points", JsonArray(listOf(buildJsonObject {
                        put("x", 20)
                        put("y", 30)
                    })))
                })))
            })
        }

        val ink = inkOf(block)
        assertEquals("#245fba", ink.strokes.single().color)
        assertEquals(6f, ink.strokes.single().width)

        val written = withInk(block, ink).getValue("ink").jsonObject
        val writtenStroke = written.getValue("strokes").jsonArray.single().jsonObject
        assertEquals("#245fba", writtenStroke.string("color"))
        assertEquals(6f, writtenStroke.getValue("width").jsonPrimitive.content.toFloat())
        assertEquals(
            "#245fba",
            written.getValue("strokeStyles").jsonArray.single().jsonObject.string("color"),
        )
    }

    @Test fun emptyD04IdentityAndStyleArraysSurviveAnEdit() {
        val note = NoteDocument.decode(
            """
            {
              "schemaVersion": 1,
              "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
              "title": "empty ink",
              "revision": 0,
              "createdAtMillis": 1,
              "blocks": [{
                "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "kind": "ink",
                "ink": {
                  "width": 320,
                  "height": 240,
                  "strokeIds": [],
                  "strokeStyles": [],
                  "strokes": []
                }
              }]
            }
            """.trimIndent(),
        )
        val encoded = withInk(note.blocks.single(), inkOf(note.blocks.single()))
            .getValue("ink").jsonObject

        assertEquals(0, encoded.getValue("strokeIds").jsonArray.size)
        assertEquals(0, encoded.getValue("strokeStyles").jsonArray.size)
        assertEquals(0, encoded.getValue("strokes").jsonArray.size)
    }

    @Test fun erasingOneWebStrokeKeepsTheOtherIdentityStyleAndExtensions() {
        val note = NoteDocument.decode(resource("notes/ink-web-d04-v1.json"))
        val erased = inkOf(note.blocks.single()).erasedBy(
            listOf(InkPoint(150f, 90f), InkPoint(150f, 110f)),
            InkEraserMode.WholeStroke,
        )

        assertEquals(1, erased.strokes.size)
        assertEquals("dddddddd-dddd-4ddd-8ddd-dddddddddddd", erased.strokes.single().id)
        assertEquals(INK_COLOR_BLUE, erased.strokes.single().color)
        assertEquals(INK_WIDTH_COARSE, erased.strokes.single().width)
        assertEquals(JsonPrimitive(true), erased.extras.getValue("webInkExtension").jsonObject.getValue("keep"))
    }

    @Test fun totalPointCountMatchesTheWebEditorLimit() {
        assertEquals(50_000, INK_MAX_POINTS)
        val point = buildJsonObject { put("x", 1); put("y", 1) }
        fun inkWithPointCount(count: Int) = buildJsonObject {
            put("width", 320)
            put("height", 240)
            put("strokes", JsonArray(listOf(buildJsonObject {
                put("points", JsonArray(List(count) { point }))
            })))
        }

        val accepted = inkOf(buildJsonObject { put("ink", inkWithPointCount(INK_MAX_POINTS)) })
        assertEquals(INK_MAX_POINTS, accepted.strokes.single().points.size)
        assertThrows(IllegalArgumentException::class.java) {
            inkOf(buildJsonObject { put("ink", inkWithPointCount(INK_MAX_POINTS + 1)) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            InkData(
                strokes = listOf(InkStroke(List(INK_MAX_POINTS + 1) { InkPoint(1f, 1f) })),
            ).validate()
        }
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
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                JsonObject(ink + mapOf(
                    "strokeIds" to JsonArray(listOf(
                        JsonPrimitive("11111111-1111-4111-8111-111111111111"),
                        JsonPrimitive("11111111-1111-4111-8111-111111111111"),
                    )),
                    "strokes" to JsonArray(listOf(strokes[0], strokes[0])),
                ))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                JsonObject(ink + ("strokes" to JsonArray(listOf(
                    JsonObject(strokes[0].jsonObject + ("points" to JsonArray(emptyList<JsonElement>()))),
                ))))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                val points = strokes[0].jsonObject.getValue("points").jsonArray
                JsonObject(ink + ("strokes" to JsonArray(listOf(
                    JsonObject(strokes[0].jsonObject + ("points" to JsonArray(listOf(
                        JsonObject(points[0].jsonObject + ("x" to JsonPrimitive(321))),
                    )))),
                ))))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink ->
                val points = strokes[0].jsonObject.getValue("points").jsonArray
                JsonObject(ink + ("strokes" to JsonArray(listOf(
                    JsonObject(strokes[0].jsonObject + ("points" to JsonArray(listOf(
                        JsonObject(points[0].jsonObject + ("pressure" to JsonPrimitive(1.1))),
                    )))),
                ))))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink -> JsonObject(ink + ("width" to JsonPrimitive(79))) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(replaceInk { ink -> JsonObject(ink + ("height" to JsonPrimitive(4097))) })
        }
        assertEquals(original, note.source.toString())
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "Missing test resource: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}

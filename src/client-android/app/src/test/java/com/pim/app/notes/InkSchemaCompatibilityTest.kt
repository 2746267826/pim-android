package com.pim.app.notes

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InkSchemaCompatibilityTest {
    @Test
    fun webD04FixtureDecodesIdentityStylesAndAllUnknownExtensions() {
        val note = NoteDocument.decode(InkSchemaFixtures.text("notes/ink-web-d04-v1.json"))
        val block = note.blocks.single()
        val ink = inkOf(block)

        assertFalse(NoteJson.configuration.ignoreUnknownKeys)
        assertTrue(ink.hasStrokeIds)
        assertTrue(ink.hasStrokeStyles)
        assertEquals(listOf("#b8373c", "#245fba"), ink.strokes.map { it.color })
        assertEquals(listOf(1.8f, 6f), ink.strokes.map { it.width })
        assertEquals(
            listOf("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            ink.strokes.map { it.id },
        )
        assertEquals(JsonPrimitive(true), ink.extensions.getValue("webInkExtension").jsonObject.getValue("keep"))
        assertEquals("keep", ink.strokes[0].extensions.string("futureStroke"))
        assertEquals("keep", ink.strokes[0].points[0].extensions.string("futurePoint"))
        assertEquals("keep", ink.strokes[0].styleExtensions.string("futureStyle"))
        assertEquals("keep", block.string("futureBlock"))
        assertEquals(JsonPrimitive(true), note.source.getValue("futureRoot").jsonObject.getValue("keep"))
    }

    @Test
    fun titleRevisionResizeAppendReopenAndEncodeKeepSchemaFields() {
        val note = NoteDocument.decode(InkSchemaFixtures.text("notes/ink-web-d04-v1.json"))
        val block = note.blocks.single()
        val original = inkOf(block)
        val resized = original.resized(400f, 300f, 600f)
        val editedInk = resized.appendStroke(
            listOf(InkPoint(350f, 50f, 0.6f, buildJsonObject { put("futurePoint", "new") })),
            styleColor = "#216e4e",
        )
        val edited = note
            .withTitle("Android 编辑后标题")
            .nextRevision()
            .replaceBlock(block.string("id")) { withInk(it, editedInk) }

        val reopened = NoteDocument.decode(edited.source.toString())
        val reopenedBlock = reopened.blocks.single()
        val reopenedInk = inkOf(reopenedBlock)

        assertEquals("Android 编辑后标题", reopened.title)
        assertEquals(note.revision + 1, reopened.revision)
        assertEquals(400f, reopenedInk.width)
        assertEquals(300f, reopenedInk.height)
        assertEquals(original.strokes.map { it.id }, reopenedInk.strokes.take(2).map { it.id })
        assertNotNull(reopenedInk.strokes[2].id)
        assertEquals(3, reopenedInk.strokes.map { it.id }.distinct().size)
        assertEquals(listOf("#b8373c", "#245fba", "#216e4e"), reopenedInk.strokes.map { it.color })
        assertEquals(listOf(1.8f, 6f, 2.5f), reopenedInk.strokes.map { it.width })
        assertEquals("keep", reopenedInk.strokes[0].extensions.string("futureStroke"))
        assertEquals("keep", reopenedInk.strokes[0].points[0].extensions.string("futurePoint"))
        assertEquals("keep", reopenedInk.strokes[0].styleExtensions.string("futureStyle"))
        assertEquals(JsonPrimitive(true), reopenedInk.extensions.getValue("webInkExtension").jsonObject.getValue("keep"))
        assertEquals("keep", reopenedBlock.string("futureBlock"))
        assertEquals(JsonPrimitive(true), reopened.source.getValue("futureRoot").jsonObject.getValue("keep"))

        val encodedInk = reopenedBlock.getValue("ink").jsonObject
        assertEquals(3, encodedInk.getValue("strokeIds").jsonArray.size)
        assertEquals(3, encodedInk.getValue("strokeStyles").jsonArray.size)
        assertEquals(
            encodedInk.getValue("strokeIds").jsonArray[0],
            JsonPrimitive(checkNotNull(original.strokes[0].id)),
        )
        assertEquals("keep", encodedInk.getValue("strokeStyles").jsonArray[0].jsonObject.string("futureStyle"))
    }

    @Test
    fun legacyInkKeepsDefaultShapeUntilItExplicitlyOptsIntoD04Arrays() {
        val note = NoteDocument.decode(InkSchemaFixtures.text("notes/ink-legacy-v1.json"))
        val block = note.blocks.single()
        val ink = inkOf(block)

        assertFalse(ink.hasStrokeIds)
        assertFalse(ink.hasStrokeStyles)
        assertNull(ink.strokes.single().id)
        assertNull(ink.strokes.single().color)
        assertEquals(2.5f, ink.strokes.single().width)

        val edited = ink.appendStroke(listOf(InkPoint(200f, 120f)), styleColor = "#216e4e")
        val encoded = withInk(block, edited).getValue("ink").jsonObject
        assertFalse(encoded.containsKey("strokeIds"))
        assertFalse(encoded.containsKey("strokeStyles"))
        assertFalse(encoded.getValue("strokes").jsonArray.last().jsonObject.containsKey("color"))
        assertEquals("keep", ink.strokes.single().extensions.string("futureStroke"))
        assertEquals("keep", ink.strokes.single().points[0].extensions.string("futurePoint"))
        assertEquals(JsonPrimitive(true), ink.extensions.getValue("futureInk").jsonObject.getValue("keep"))
    }

    @Test
    fun inlineOnlyStrokeColorAndWidthSurviveWithoutInventingStyleArrays() {
        val block = buildJsonObject {
            put("id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            put("kind", "ink")
            put("ink", buildJsonObject {
                put("width", 320); put("height", 240)
                put("strokes", JsonArray(listOf(buildJsonObject {
                    put("color", "#245fba"); put("width", 6)
                    put("points", JsonArray(listOf(buildJsonObject { put("x", 20); put("y", 30) })))
                })))
            })
        }
        val ink = inkOf(block)
        assertFalse(ink.hasStrokeIds)
        assertFalse(ink.hasStrokeStyles)
        assertEquals("#245fba", ink.strokes.single().color)
        assertEquals(6f, ink.strokes.single().width)

        val encoded = withInk(block, ink).getValue("ink").jsonObject
        assertFalse(encoded.containsKey("strokeIds"))
        assertFalse(encoded.containsKey("strokeStyles"))
        assertEquals("#245fba", encoded.getValue("strokes").jsonArray.single().jsonObject.string("color"))
        assertEquals(6f, encoded.getValue("strokes").jsonArray.single().jsonObject.getValue("width").jsonPrimitive.float)
    }

    @Test
    fun emptyD04IdentityAndStyleArraysSurviveAnEdit() {
        val note = NoteDocument.decode("""
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
        """.trimIndent())
        val encoded = withInk(note.blocks.single(), inkOf(note.blocks.single()))
            .getValue("ink").jsonObject

        assertTrue(encoded.containsKey("strokeIds"))
        assertTrue(encoded.containsKey("strokeStyles"))
        assertEquals(0, encoded.getValue("strokeIds").jsonArray.size)
        assertEquals(0, encoded.getValue("strokeStyles").jsonArray.size)
    }

    @Test
    fun erasingOneWebStrokeKeepsTheRemainingIdentityStyleAndUnknownExtensions() {
        val note = NoteDocument.decode(InkSchemaFixtures.text("notes/ink-web-d04-v1.json"))
        val ink = inkOf(note.blocks.single())
        val erased = ink.erasedBy(listOf(InkPoint(150f, 90f), InkPoint(150f, 110f)))

        assertEquals(1, erased.strokes.size)
        assertEquals("dddddddd-dddd-4ddd-8ddd-dddddddddddd", erased.strokes.single().id)
        assertEquals("#245fba", erased.strokes.single().color)
        assertEquals(6f, erased.strokes.single().width)
        assertEquals(JsonPrimitive(true), erased.extensions.getValue("webInkExtension").jsonObject.getValue("keep"))
    }

    @Test
    fun malformedKnownFieldsFailClosedWithoutRewritingTheSource() {
        val note = NoteDocument.decode(InkSchemaFixtures.text("notes/ink-web-d04-v1.json"))
        val block = note.blocks.single()
        val ink = block.getValue("ink").jsonObject
        val original = note.source.toString()

        fun decodeWith(nextInk: JsonObject): NoteDocument {
            val nextBlock = JsonObject(block + ("ink" to nextInk))
            return NoteDocument.decode(note.withBlocks(listOf(nextBlock)).source.toString())
        }

        val oneStroke = ink.getValue("strokes").jsonArray.first().jsonObject
        val style = ink.getValue("strokeStyles").jsonArray.first().jsonObject
        val duplicate = ink.getValue("strokeIds").jsonArray.first().jsonPrimitive.content

        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + ("strokeIds" to JsonArray(listOf(
                JsonPrimitive(duplicate), JsonPrimitive(duplicate),
            )))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + ("strokeIds" to JsonArray(listOf(
                JsonPrimitive(duplicate), JsonPrimitive("not-a-uuid"),
            )))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + ("strokeStyles" to JsonArray(listOf(style)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + mapOf(
                "strokes" to JsonArray(listOf(
                    JsonObject(oneStroke + ("color" to JsonPrimitive("#245fba"))),
                    ink.getValue("strokes").jsonArray[1],
                )),
                "strokeStyles" to JsonArray(listOf(
                    JsonObject(style + ("color" to JsonPrimitive("#b8373c"))),
                    ink.getValue("strokeStyles").jsonArray[1],
                )),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + ("strokes" to JsonArray(listOf(
                JsonObject(oneStroke + mapOf(
                    "color" to JsonPrimitive("<svg>"),
                    "width" to JsonPrimitive(1.8),
                )),
                ink.getValue("strokes").jsonArray[1],
            )))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + ("strokes" to JsonArray(listOf(
                JsonObject(oneStroke + ("points" to JsonArray(emptyList()))),
                ink.getValue("strokes").jsonArray[1],
            )))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + ("strokes" to JsonArray(listOf(
                JsonObject(oneStroke + ("points" to JsonArray(listOf(
                    buildJsonObject { put("x", 401); put("y", 20); put("pressure", 0.5) },
                )))),
                ink.getValue("strokes").jsonArray[1],
            )))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeWith(JsonObject(ink + mapOf(
                "width" to JsonPrimitive(79),
                "strokes" to JsonArray(emptyList()),
                "strokeIds" to JsonArray(emptyList()),
                "strokeStyles" to JsonArray(emptyList()),
            )))
        }
        assertEquals(original, note.source.toString())
    }
}

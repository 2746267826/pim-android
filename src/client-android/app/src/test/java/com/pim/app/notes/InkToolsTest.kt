package com.pim.app.notes

import android.app.Application
import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.app.testing.InMemorySharedPreferences
import java.io.File
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkToolsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun partialEraserSplitsOneStrokeAndPreservesBothStylesAndIdentities() {
        val crossedId = newNoteId()
        val otherId = newNoteId()
        val crossed = InkStroke(
            points = listOf(InkPoint(20f, 100f), InkPoint(300f, 100f)),
            width = INK_WIDTH_COARSE,
            color = INK_COLOR_RED,
            id = crossedId,
        )
        val other = InkStroke(
            points = listOf(InkPoint(20f, 200f), InkPoint(300f, 200f)),
            width = INK_WIDTH_THIN,
            color = INK_COLOR_BLUE,
            id = otherId,
        )

        val erased = InkData(strokes = listOf(crossed, other))
            .erasedBy(listOf(InkPoint(150f, 80f), InkPoint(150f, 120f)), InkEraserMode.Partial)

        assertEquals(3, erased.strokes.size)
        val left = erased.strokes[0]
        val right = erased.strokes[1]
        assertEquals(InkPoint(20f, 100f), left.points.first())
        assertEquals(InkPoint(300f, 100f), right.points.last())
        assertTrue(left.points.last().x < 150f)
        assertTrue(right.points.first().x > 150f)
        assertEquals(crossedId, left.id)
        assertNotNull(right.id)
        assertNotEquals(crossedId, right.id)
        assertEquals(INK_WIDTH_COARSE, left.width)
        assertEquals(INK_COLOR_RED, right.color)
        assertEquals(other, erased.strokes[2])
        assertEquals(3, erased.strokes.mapNotNull { it.id }.distinct().size)
    }

    @Test fun wholeEraserRemovesOnlyTouchedStrokesAndPartialMissReturnsOriginalInk() {
        val crossed = InkStroke(
            points = listOf(InkPoint(20f, 100f), InkPoint(300f, 100f)),
            width = INK_WIDTH_COARSE,
            color = INK_COLOR_RED,
            id = newNoteId(),
        )
        val other = InkStroke(
            points = listOf(InkPoint(20f, 200f), InkPoint(300f, 200f)),
            width = INK_WIDTH_THIN,
            color = INK_COLOR_BLUE,
            id = newNoteId(),
        )
        val ink = InkData(strokes = listOf(crossed, other))
        val path = listOf(InkPoint(150f, 80f), InkPoint(150f, 120f))

        assertEquals(listOf(other), ink.erasedBy(path, InkEraserMode.WholeStroke).strokes)
        assertEquals(ink.strokes, ink.erasedBy(listOf(InkPoint(150f, 20f)), InkEraserMode.Partial).strokes)
    }

    @Test fun fireAndForgetLegacyStrokeDefaultsAndExtensionFieldsSurviveANonDestructiveReopen() {
        val blockId = newNoteId()
        val note = NoteDocument.create().let { original ->
            NoteDocument(JsonObject(original.source + ("futureRoot" to JsonPrimitive("keep"))))
        }.withBlocks(listOf(
            buildJsonObject {
                put("id", blockId); put("kind", "ink"); put("futureBlock", "keep")
                put("ink", buildJsonObject {
                    put("width", 320); put("height", 240)
                    put("futureInk", buildJsonObject { put("x", 7) })
                    put("strokes", JsonArray(listOf(buildJsonObject {
                        put("points", JsonArray(listOf(
                            buildJsonObject {
                                put("x", 20); put("y", 30); put("pressure", 0.4); put("futurePoint", "keep")
                            },
                            buildJsonObject { put("x", 120); put("y", 90); put("pressure", 0.8) },
                        )))
                        put("futureStroke", "keep")
                    })))
                })
            },
            markdownBlock("body"),
        ))

        val reopened = NoteDocument.decode(note.source.toString())
        val block = reopened.blocks.first()
        val ink = inkOf(block)
        assertEquals(INK_DEFAULT_WIDTH, ink.strokes.single().width)
        assertEquals(INK_LEGACY_COLOR, ink.strokes.single().color)
        assertEquals(null, ink.strokes.single().id)
        assertEquals("keep", reopened.source.string("futureRoot"))
        assertEquals("body", reopened.blocks[1].string("text"))

        val encoded = withInk(block, ink)
        assertEquals("keep", encoded.string("futureBlock"))
        val encodedInk = encoded.getValue("ink").jsonObject
        assertEquals(7, encodedInk.getValue("futureInk").jsonObject.getValue("x").jsonPrimitive.content.toInt())
        val encodedStroke = encodedInk.getValue("strokes").jsonArray.single().jsonObject
        assertEquals("keep", encodedStroke.string("futureStroke"))
        assertEquals("keep", encodedStroke.getValue("points").jsonArray.first().jsonObject.string("futurePoint"))
        val reopenedWritten = inkOf(encoded)
        assertEquals(INK_DEFAULT_WIDTH, reopenedWritten.strokes.single().width)
        assertEquals(INK_LEGACY_COLOR, reopenedWritten.strokes.single().color)
        assertNotNull(reopenedWritten.strokes.single().id)
    }

    @Test fun pointStrokeAndStyleExtensionFieldsSurviveANonDestructiveReopen() {
        val id = newNoteId()
        val block = withInk(inkBlock(320f), InkData(strokes = listOf(
            InkStroke(
                points = listOf(
                    InkPoint(20f, 30f, extras = buildJsonObject { put("futurePoint", "keep") }),
                    InkPoint(120f, 90f),
                ),
                width = INK_WIDTH_COARSE,
                color = INK_COLOR_RED,
                id = id,
                extras = buildJsonObject { put("futureStroke", "keep") },
                styleExtras = buildJsonObject { put("futureStyle", "keep") },
            ),
        )))
        val note = NoteDocument.create().withBlocks(listOf(block))

        val reopened = NoteDocument.decode(note.source.toString())
        val ink = inkOf(reopened.blocks.single())
        assertEquals("keep", ink.strokes.single().extras.string("futureStroke"))
        assertEquals("keep", ink.strokes.single().styleExtras.string("futureStyle"))
        assertEquals("keep", ink.strokes.single().points.first().extras.string("futurePoint"))

        val encoded = withInk(reopened.blocks.single(), ink)
        assertEquals("keep", inkOf(encoded).strokes.single().styleExtras.string("futureStyle"))
        val encodedStroke = encoded.getValue("ink").jsonObject.getValue("strokes").jsonArray.single().jsonObject
        val encodedPoint = encodedStroke.getValue("points").jsonArray.first().jsonObject
        assertEquals("keep", encodedPoint.string("futurePoint"))
    }

    @Test fun perStrokeStyleAndIdentityRoundTripUsesTheCrossEndShape() {
        val firstId = newNoteId()
        val secondId = newNoteId()
        val block = withInk(inkBlock(320f), InkData(strokes = listOf(
            InkStroke(
                listOf(InkPoint(20f, 30f), InkPoint(120f, 90f)),
                width = INK_WIDTH_COARSE,
                color = INK_COLOR_GREEN,
                id = firstId,
            ),
            InkStroke(
                listOf(InkPoint(20f, 160f), InkPoint(120f, 190f)),
                width = INK_WIDTH_THIN,
                color = INK_COLOR_RED,
                id = secondId,
            ),
        )))
        val note = NoteDocument.create().withBlocks(listOf(block))

        val reopened = NoteDocument.decode(note.source.toString())
        val ink = inkOf(reopened.blocks.single())
        assertEquals(listOf(INK_COLOR_GREEN, INK_COLOR_RED), ink.strokes.map { it.color })
        assertEquals(listOf(INK_WIDTH_COARSE, INK_WIDTH_THIN), ink.strokes.map { it.width })
        assertEquals(listOf(firstId, secondId), ink.strokes.map { it.id })

        val encoded = reopened.blocks.single().getValue("ink").jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(firstId), JsonPrimitive(secondId))), encoded.getValue("strokeIds"))
        assertEquals(2, encoded.getValue("strokeStyles").jsonArray.size)
        assertEquals(INK_COLOR_GREEN, encoded.getValue("strokes").jsonArray[0].jsonObject.string("color"))
        assertEquals(INK_WIDTH_COARSE, encoded.getValue("strokes").jsonArray[0].jsonObject.getValue("width").jsonPrimitive.content.toFloat())
    }

    @Test fun webD04SnapshotLoadsStorageRoundTripsAndSupportsEraseUndo() {
        val firstId = newNoteId()
        val secondId = newNoteId()
        val webInk = NoteJson.parseToJsonElement("""
            {
              "width": 320,
              "height": 240,
              "strokeIds": ["$firstId", "$secondId"],
              "strokeStyles": [
                {"color": "#b8373c", "width": 1.8, "futureStyle": "keep"},
                {"color": "#245fba", "width": 6}
              ],
              "strokes": [
                {
                  "color": "#b8373c",
                  "width": 1.8,
                  "points": [
                    {"x": 20, "y": 100, "pressure": 0.2},
                    {"x": 300, "y": 100, "pressure": 0.9}
                  ]
                },
                {
                  "color": "#245fba",
                  "width": 6,
                  "points": [
                    {"x": 20, "y": 200, "pressure": 0.4},
                    {"x": 300, "y": 200, "pressure": 0.5}
                  ]
                }
              ],
              "webInkExtension": {"keep": true}
            }
        """.trimIndent()).jsonObject
        val block = buildJsonObject {
            put("id", newNoteId()); put("kind", "ink"); put("ink", webInk)
        }
        val note = NoteDocument.create().withBlocks(listOf(block)).nextRevision()
        val root = temp.newFolder("web-d04-snapshot")
        val store = NoteFileStore(root)

        store.save(note)
        val reopened = NoteFileStore(root).read(note.id)
        val ink = inkOf(reopened.blocks.single())
        assertEquals(listOf(INK_COLOR_RED, INK_COLOR_BLUE), ink.strokes.map { it.color })
        assertEquals(listOf(INK_WIDTH_THIN, INK_WIDTH_COARSE), ink.strokes.map { it.width })
        assertEquals(listOf(firstId, secondId), ink.strokes.map { it.id })
        assertEquals(JsonPrimitive(true), ink.extras.getValue("webInkExtension").jsonObject.getValue("keep"))
        assertEquals("keep", ink.strokes.first().styleExtras.string("futureStyle"))
        val material = NoteMaterialInput(newNoteId(), 1L, note.source, emptyList())
        val materialRoundTrip = NoteJson.decodeFromString<NoteMaterialInput>(NoteJson.encodeToString(material))
        assertEquals(note.source, materialRoundTrip.note)

        val erased = ink.erasedBy(
            listOf(InkPoint(150f, 80f), InkPoint(150f, 120f)),
            InkEraserMode.Partial,
        )
        assertEquals(3, erased.strokes.size)
        assertEquals(INK_COLOR_RED, erased.strokes[0].color)
        assertEquals(INK_COLOR_RED, erased.strokes[1].color)
        assertEquals(INK_COLOR_BLUE, erased.strokes[2].color)
        assertEquals(listOf(INK_WIDTH_THIN, INK_WIDTH_THIN, INK_WIDTH_COARSE), erased.strokes.map { it.width })
        assertEquals(erased.strokes.mapNotNull { it.id }.distinct().size, erased.strokes.size)

        val undo = InkUndoStack().apply { record(ink) }
        assertEquals(ink, undo.undo())
        val reencoded = withInk(reopened.blocks.single(), ink)
        assertEquals(
            JsonPrimitive(true),
            reencoded.getValue("ink").jsonObject.getValue("webInkExtension").jsonObject.getValue("keep"),
        )
        assertEquals(JsonArray(listOf(JsonPrimitive(firstId), JsonPrimitive(secondId))),
            reencoded.getValue("ink").jsonObject.getValue("strokeIds"))
        val encodedStyles = reencoded.getValue("ink").jsonObject.getValue("strokeStyles").jsonArray
        assertEquals(2, encodedStyles.size)
        assertEquals("keep", encodedStyles[0].jsonObject.string("futureStyle"))
    }

    @Test fun invalidOrInconsistentStyleSnapshotsFailClosed() {
        fun decodeInk(ink: JsonObject): NoteDocument {
            val block = buildJsonObject {
                put("id", newNoteId()); put("kind", "ink"); put("ink", ink)
            }
            return NoteDocument.create().withBlocks(listOf(block))
        }
        val duplicate = newNoteId()
        val points = JsonArray(listOf(buildJsonObject { put("x", 20); put("y", 30) }))
        fun stroke(id: String? = null, color: String = "#245fba", width: Number = 3.4) = buildJsonObject {
            put("points", points); put("width", width); put("color", color)
            id?.let { put("id", it) }
        }

        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(decodeInk(buildJsonObject {
                put("width", 320); put("height", 240)
                put("strokeIds", JsonArray(listOf(JsonPrimitive(duplicate), JsonPrimitive(duplicate))))
                put("strokes", JsonArray(listOf(stroke(), stroke())))
            }).source.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(decodeInk(buildJsonObject {
                put("width", 320); put("height", 240)
                put("strokeIds", JsonArray(listOf(JsonPrimitive(newNoteId()))))
                put("strokes", JsonArray(listOf(stroke(), stroke())))
            }).source.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(decodeInk(buildJsonObject {
                put("width", 320); put("height", 240)
                put("strokeStyles", JsonArray(listOf(
                    buildJsonObject { put("color", "#b8373c"); put("width", 3.4) },
                )))
                put("strokes", JsonArray(listOf(stroke())))
            }).source.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(decodeInk(buildJsonObject {
                put("width", 320); put("height", 240)
                put("strokeStyles", JsonArray(listOf(
                    buildJsonObject { put("color", "#b8373c"); put("width", 3.4) },
                )))
                put("strokes", JsonArray(listOf(stroke(color = "#245fba", width = 6))))
            }).source.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteDocument.decode(decodeInk(buildJsonObject {
                put("width", 320); put("height", 240)
                put("strokes", JsonArray(listOf(buildJsonObject {
                    put("points", points); put("width", 3.4); put("color", "<svg>")
                })))
            }).source.toString())
        }
    }

    @Test fun pngAndPreviewIdentityUsePerStrokeStyleNotTheCurrentTheme() {
        val ink = InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(30f, 40f)), width = 6f, color = INK_COLOR_RED, id = newNoteId()),
        ))
        val bitmap = InkRendering.bitmap(ink)
        try {
            assertEquals(android.graphics.Color.parseColor(INK_COLOR_RED), bitmap.getPixel(30, 40))
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(1, 1))
        } finally {
            bitmap.recycle()
        }

        val block = inkBlock(320f)
        val blue = withInk(block, ink.copy(strokes = listOf(
            ink.strokes.single().copy(color = INK_COLOR_BLUE),
        )))
        val red = withInk(block, ink)
        assertNotEquals(NoteFileStore.inkPath(blue), NoteFileStore.inkPath(red))
    }

    @Test fun legacyDefaultStrokeUsesTheFrozenInkColorOnWhitePaper() {
        assertEquals("#263b85", INK_LEGACY_COLOR)
        val bitmap = InkRendering.bitmap(InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(30f, 40f))),
        )))
        try {
            assertEquals(android.graphics.Color.parseColor(INK_LEGACY_COLOR), bitmap.getPixel(30, 40))
            assertEquals(android.graphics.Color.WHITE, bitmap.getPixel(1, 1))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun savedStylesIdsAndPreviewColorsSurviveFileStoreReopen() {
        val root = temp.newFolder("ink-style-restore")
        val store = NoteFileStore(root)
        val redId = newNoteId()
        val blueId = newNoteId()
        val inkJson = withInk(inkBlock(320f), InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(30f, 40f)), width = INK_WIDTH_COARSE, color = INK_COLOR_RED, id = redId),
            InkStroke(
                listOf(InkPoint(70f, 100f), InkPoint(120f, 100f)),
                width = INK_WIDTH_THIN,
                color = INK_COLOR_BLUE,
                id = blueId,
            ),
        )))
        val note = NoteDocument.create()
            .withBlocks(listOf(markdownBlock("body"), inkJson))
            .nextRevision()

        store.save(note)
        val reopened = NoteFileStore(root).read(note.id)
        val reopenedBlock = reopened.blocks.single { it.string("kind") == "ink" }
        val ink = inkOf(reopenedBlock)
        assertEquals(listOf(INK_COLOR_RED, INK_COLOR_BLUE), ink.strokes.map { it.color })
        assertEquals(listOf(INK_WIDTH_COARSE, INK_WIDTH_THIN), ink.strokes.map { it.width })
        assertEquals(listOf(redId, blueId), ink.strokes.map { it.id })

        val png = File(root, "${note.id}/${NoteFileStore.inkPath(reopenedBlock)}")
        assertTrue(png.isFile)
        val bitmap = BitmapFactory.decodeFile(png.path)
        try {
            val decoded = checkNotNull(bitmap)
            assertEquals(android.graphics.Color.parseColor(INK_COLOR_RED), decoded.getPixel(30, 40))
            assertEquals(android.graphics.Color.parseColor(INK_COLOR_BLUE), decoded.getPixel(80, 100))
        } finally {
            bitmap?.recycle()
        }
    }

    @Test fun oneEraserGestureIsOneUndoStepAndCancellationKeepsOneUndoableResult() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val view = InkInputView(application).apply {
            layout(0, 0, 640, 480); drawingEnabled = true; eraserMode = InkEraserMode.Partial
        }
        val initial = InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(50f, 100f), InkPoint(350f, 100f)), id = newNoteId()),
            InkStroke(listOf(InkPoint(50f, 200f), InkPoint(350f, 200f)), id = newNoteId()),
        ))
        view.ink = initial
        val undo = InkUndoStack()
        var current = initial
        var commits = 0
        view.onInkChanged = { previous, next ->
            commits++
            assertEquals(current, previous)
            undo.record(previous)
            current = next
        }

        touch(view, MotionEvent.ACTION_DOWN, 300f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 300f, 250f)
        touch(view, MotionEvent.ACTION_MOVE, 300f, 400f)
        touch(view, MotionEvent.ACTION_UP, 300f, 460f)

        assertEquals(1, commits)
        assertEquals(1, undo.size)
        assertEquals(4, current.strokes.size)
        assertEquals(4, current.strokes.mapNotNull { it.id }.distinct().size)
        assertEquals(initial, undo.undo())

        view.ink = initial
        current = initial
        commits = 0
        touch(view, MotionEvent.ACTION_DOWN, 300f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 300f, 250f)
        touch(view, MotionEvent.ACTION_MOVE, 300f, 400f)
        touch(view, MotionEvent.ACTION_CANCEL, 300f, 460f)
        assertEquals(1, commits)
        assertTrue(current != initial)
        assertEquals(4, current.strokes.size)
        assertEquals(initial, undo.undo())
    }

    @Test fun oneWholeStrokeGestureRemovesEveryTouchedStrokeAndUndoRestoresAll() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val view = InkInputView(application).apply {
            layout(0, 0, 640, 480); drawingEnabled = true; eraserMode = InkEraserMode.WholeStroke
        }
        val initial = InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(50f, 100f), InkPoint(350f, 100f)), id = newNoteId()),
            InkStroke(listOf(InkPoint(50f, 200f), InkPoint(350f, 200f)), id = newNoteId()),
        ))
        view.ink = initial
        val undo = InkUndoStack()
        var current = initial
        var commits = 0
        view.onInkChanged = { previous, next ->
            commits++
            assertEquals(current, previous)
            undo.record(previous)
            current = next
        }

        touch(view, MotionEvent.ACTION_DOWN, 300f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 300f, 250f)
        touch(view, MotionEvent.ACTION_MOVE, 300f, 400f)
        touch(view, MotionEvent.ACTION_UP, 300f, 460f)

        assertEquals(1, commits)
        assertEquals(0, current.strokes.size)
        assertEquals(1, undo.size)
        assertEquals(initial, undo.undo())
    }

    @Test fun cancelledPenDoesNotPublishAndEveryNewStrokeFreezesItsBrushStyle() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val view = InkInputView(application).apply {
            layout(0, 0, 640, 480); drawingEnabled = true; penColor = INK_COLOR_BLUE; penWidth = INK_WIDTH_COARSE
        }
        var changes = 0
        view.onInkChanged = { _, _ -> changes++ }
        touch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 160f, 140f)
        touch(view, MotionEvent.ACTION_CANCEL, 160f, 140f)
        assertEquals(0, changes)

        var latest: InkData? = null
        view.onInkChanged = { _, next -> latest = next }
        touch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(view, MotionEvent.ACTION_MOVE, 160f, 140f)
        touch(view, MotionEvent.ACTION_UP, 200f, 180f)

        view.penColor = INK_COLOR_RED
        view.penWidth = INK_WIDTH_THIN
        touch(view, MotionEvent.ACTION_DOWN, 100f, 180f)
        touch(view, MotionEvent.ACTION_MOVE, 160f, 220f)
        touch(view, MotionEvent.ACTION_UP, 200f, 230f)

        val strokes = latest!!.strokes
        assertEquals(2, strokes.size)
        assertEquals(INK_COLOR_BLUE, strokes[0].color)
        assertEquals(INK_WIDTH_COARSE, strokes[0].width)
        assertEquals(INK_COLOR_RED, strokes[1].color)
        assertEquals(INK_WIDTH_THIN, strokes[1].width)
        assertEquals(2, strokes.mapNotNull { it.id }.distinct().size)
    }

    @Test fun onlyTabletsDefaultToStylusWhilePhonesKeepFingerInput() {
        assertTrue(defaultInkStylusOnly(600))
        assertTrue(defaultInkStylusOnly(1280))
        assertFalse(defaultInkStylusOnly(599))
    }

    @Test fun deviceBrushPreferenceRemembersLastChoiceAndFallsBackFromInvalidValues() {
        val preferences = InMemorySharedPreferences()
        val store = InkPenPreferenceStore(preferences)
        assertEquals(InkPenPreference(INK_COLOR_INK, INK_WIDTH_MEDIUM), store.load())

        store.save(InkPenPreference(INK_COLOR_GREEN, INK_WIDTH_THIN))
        assertEquals(InkPenPreference(INK_COLOR_GREEN, INK_WIDTH_THIN), InkPenPreferenceStore(preferences).load())

        preferences.edit().putString(InkPenPreferenceStore.KEY_COLOR, "<invalid>")
            .putFloat(InkPenPreferenceStore.KEY_WIDTH, 99f).commit()
        assertEquals(InkPenPreference(INK_COLOR_INK, INK_WIDTH_MEDIUM), store.load())

        preferences.edit().putString(InkPenPreferenceStore.KEY_WIDTH, "not-a-float").commit()
        assertEquals(InkPenPreference(INK_COLOR_INK, INK_WIDTH_MEDIUM), store.load())
    }

    private fun touch(view: InkInputView, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(1, 2, action, x, y, 0).also {
            view.onTouchEvent(it)
            it.recycle()
        }
    }
}

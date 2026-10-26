package com.pim.app.notes

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteDocumentTest {
    @Test fun markdownAndOpaqueExtensionRoundTripWithoutFlatteningOrDroppingFields() {
        val markdown = "# 标题\n\n- [x] task\n\n```kotlin\nval a = 1\n```\n\n| A | B |\n|---|---|\n|1|2|\n"
        val unknown = buildJsonObject { put("id", newNoteId()); put("kind", "future-diagram"); put("privatePayload", buildJsonObject { put("x", 7) }) }
        val original = NoteDocument.create().withBlocks(listOf(markdownBlock(markdown), unknown))
        val note = original.copy(source = JsonObject(original.source + ("futureRootField" to JsonPrimitive("keep"))))
        val reopened = NoteDocument.decode(note.withTitle("修改标题").nextRevision().source.toString())
        assertEquals(markdown, reopened.blocks[0].string("text"))
        assertEquals(unknown, reopened.blocks[1])
        assertEquals("keep", reopened.source.string("futureRootField"))
    }

    @Test fun insertionAtCaretPreservesBothTextHalvesAndExistingBlockIdentity() {
        val original = markdownBlock("before\n\nafter")
        val material = inkBlock(320f)
        val note = NoteDocument.create().withBlocks(listOf(original)).insert(NoteInsertion(original.string("id"), 8), material)
        assertEquals(listOf("markdown", "ink", "markdown"), note.blocks.map { it.string("kind") })
        assertEquals("before\n\n", note.blocks[0].string("text"))
        assertEquals("after", note.blocks[2].string("text"))
        assertEquals(original.string("id"), note.blocks[0].string("id"))
        assertEquals(material, note.blocks[1])
    }

    @Test fun failedInsertionDoesNotSilentlyAppendToAnotherDocumentPosition() {
        val note = NoteDocument.create()
        assertThrows(IllegalArgumentException::class.java) { note.insert(NoteInsertion(newNoteId(), 0), inkBlock(320f)) }
        assertEquals(1, note.blocks.size)
    }

    @Test fun richCaretUsesCompleteMarkdownFragmentsAndKeepsOpaqueFields() {
        val block = JsonObject(markdownBlock("**beforeafter**") + ("future" to JsonPrimitive("keep")))
        val anchor = NoteInsertion(block.string("id"), 0, "**beforeafter**", "**before**", "**after**")
        val note = NoteDocument.create().withBlocks(listOf(block)).insert(anchor, inkBlock(320f))
        assertEquals("**before**", note.blocks[0].string("text"))
        assertEquals("**after**", note.blocks[2].string("text"))
        assertEquals(block.string("id"), note.blocks[0].string("id"))
        assertEquals("keep", note.blocks[0].string("future"))
    }

    @Test fun cancelledOrStaleRichCaretCannotRewriteNewerOriginalText() {
        val original = markdownBlock("**beforeafter**")
        val anchor = NoteInsertion(original.string("id"), 0, "**beforeafter**", "**before**", "**after**")
        val note = NoteDocument.create().withBlocks(listOf(original))
        assertEquals("**beforeafter**", note.blocks.single().string("text"))
        val changed = note.replaceBlock(original.string("id")) { JsonObject(it + ("text" to JsonPrimitive("newer"))) }
        assertThrows(IllegalArgumentException::class.java) { changed.insert(anchor, inkBlock(320f)) }
        assertEquals("newer", changed.blocks.single().string("text"))
        assertThrows(IllegalArgumentException::class.java) { NoteInsertion(original.string("id"), 0, expectedText = "partial").validate() }
    }

    @Test fun growingAndShrinkingNeverStretchOrDiscardPoints() {
        val strokes = listOf(InkStroke(listOf(InkPoint(200f, 150f, 0.4f), InkPoint(220f, 170f))))
        val ink = InkData(strokes = strokes)
        val grown = ink.resized(400f, 500f, 600f)
        assertEquals(strokes, grown.strokes)
        assertEquals(400f, grown.width)
        val shrunk = grown.resized(1f, 1f, 300f)
        assertEquals(232f, shrunk.width)
        assertEquals(182f, shrunk.height)
        assertEquals(strokes, shrunk.strokes)
        val narrowerDevice = ink.resized(100f, 100f, 160f)
        assertEquals(232f, narrowerDevice.width)
        narrowerDevice.validate()
    }

    @Test fun resizingAtMaximumCoordinateDoesNotInvertBounds() {
        InkData(2048f, 4096f, listOf(InkStroke(listOf(InkPoint(2048f, 4096f)))))
            .resized(1f, 1f, 320f).validate()
    }

    @Test fun eraserHitsStrokeSegmentsNotOnlyStoredVertices() {
        val crossed = InkStroke(listOf(InkPoint(20f, 100f), InkPoint(300f, 100f)))
        val other = InkStroke(listOf(InkPoint(20f, 200f), InkPoint(300f, 200f)))
        val ink = InkData(strokes = listOf(crossed, other))
        assertEquals(listOf(other), ink.erasedBy(listOf(InkPoint(150f, 90f), InkPoint(150f, 110f))).strokes)
    }

    @Test fun rejectsTraversalDuplicateIdentityAndUnsupportedVersion() {
        val note = NoteDocument.create()
        assertThrows(IllegalArgumentException::class.java) { note.copy(source = JsonObject(note.source + ("id" to JsonPrimitive("../recordings")))).validate() }
        assertThrows(IllegalArgumentException::class.java) { note.withBlocks(note.blocks + note.blocks).validate() }
        assertThrows(IllegalArgumentException::class.java) { NoteDocument.decode(JsonObject(note.source + ("schemaVersion" to JsonPrimitive(2))).toString()) }
    }

    @Test fun invalidInkCoordinatesAreNotSavedAsValidMaterial() {
        assertThrows(IllegalArgumentException::class.java) { InkData(width = Float.NaN).validate() }
        assertThrows(IllegalArgumentException::class.java) { InkData(strokes = listOf(InkStroke(listOf(InkPoint(400f, 1f))))).validate() }
    }
}

package com.pim.app.notes

import android.app.Application
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class InkInputViewTest {
    private fun view(): InkInputView = InkInputView(ApplicationProvider.getApplicationContext<Application>()).apply {
        layout(0, 0, 640, 480); drawingEnabled = true
    }
    private fun touch(view: InkInputView, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(1, 2, action, x, y, 0).also { view.onTouchEvent(it); it.recycle() }
    }

    @Test fun inputStoresDocumentCoordinatesAndEditablePressureNotViewportPixels() {
        val view = view()
        var output: InkData? = null
        view.onInkChanged = { _, next -> output = next }
        touch(view, MotionEvent.ACTION_DOWN, 100f, 120f)
        touch(view, MotionEvent.ACTION_MOVE, 200f, 240f)
        touch(view, MotionEvent.ACTION_UP, 240f, 280f)
        val points = output!!.strokes.single().points
        assertEquals(50f, points.first().x); assertEquals(60f, points.first().y)
        assertEquals(120f, points.last().x); assertEquals(140f, points.last().y)
        assertTrue(points.all { it.pressure in 0f..1f })
    }

    @Test fun cancelledStrokeAndViewModeDoNotPublishInk() {
        val view = view()
        var count = 0
        view.onInkChanged = { _, _ -> count++ }
        touch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(view, MotionEvent.ACTION_CANCEL, 100f, 100f)
        assertEquals(0, count)
        view.drawingEnabled = false
        assertFalse(view.onTouchEvent(MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 10f, 10f, 0)))
        assertEquals(0, count)
    }

    @Test fun penOnlyModeDoesNotConvertFingerOrUnknownToolIntoInk() {
        val view = view().apply { stylusOnly = true }
        var count = 0
        view.onInkChanged = { _, _ -> count++ }
        val event = MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try { assertFalse(view.onTouchEvent(event)) } finally { event.recycle() }
        assertEquals(0, count)
    }

    @Test fun noMovePartialEraserTapSplitsOnlyTheTouchedStroke() {
        val touchedId = newNoteId()
        val otherId = newNoteId()
        val touched = InkStroke(
            listOf(InkPoint(20f, 100f), InkPoint(620f, 100f)),
            width = INK_WIDTH_COARSE,
            color = INK_COLOR_BLUE,
            id = touchedId,
        )
        val other = InkStroke(
            listOf(InkPoint(20f, 200f), InkPoint(620f, 200f)),
            width = INK_WIDTH_THIN,
            color = INK_COLOR_RED,
            id = otherId,
        )
        val initial = InkData(width = 640f, height = 480f, strokes = listOf(touched, other))
        val view = view().apply {
            ink = initial
            eraserMode = InkEraserMode.Partial
        }
        var changes = 0
        var latest: InkData? = null
        view.onInkChanged = { previous, next ->
            changes++
            assertEquals(initial, previous)
            latest = next
        }

        touch(view, MotionEvent.ACTION_DOWN, 320f, 100f)
        touch(view, MotionEvent.ACTION_UP, 320f, 100f)

        assertEquals(1, changes)
        assertEquals(3, latest!!.strokes.size)
        assertEquals(touched.color, latest!!.strokes[0].color)
        assertEquals(touched.width, latest!!.strokes[0].width)
        assertEquals(touched.color, latest!!.strokes[1].color)
        assertEquals(touched.width, latest!!.strokes[1].width)
        assertTrue(latest!!.strokes[0].points.last().x < 320f)
        assertTrue(latest!!.strokes[1].points.first().x > 320f)
        assertEquals(other, latest!!.strokes[2])
    }

    @Test fun noMoveWholeStrokeEraserTapRemovesOnlyTheTouchedStroke() {
        val touched = InkStroke(
            listOf(InkPoint(20f, 100f), InkPoint(620f, 100f)),
            width = INK_WIDTH_COARSE,
            color = INK_COLOR_BLUE,
            id = newNoteId(),
        )
        val other = InkStroke(
            listOf(InkPoint(20f, 200f), InkPoint(620f, 200f)),
            width = INK_WIDTH_THIN,
            color = INK_COLOR_RED,
            id = newNoteId(),
        )
        val initial = InkData(width = 640f, height = 480f, strokes = listOf(touched, other))
        val view = view().apply {
            ink = initial
            eraserMode = InkEraserMode.WholeStroke
        }
        var changes = 0
        var latest: InkData? = null
        view.onInkChanged = { previous, next ->
            changes++
            assertEquals(initial, previous)
            latest = next
        }

        touch(view, MotionEvent.ACTION_DOWN, 320f, 100f)
        touch(view, MotionEvent.ACTION_UP, 320f, 100f)

        assertEquals(1, changes)
        assertEquals(listOf(other), latest!!.strokes)
    }

    @Test fun newStrokeAtPointLimitIsRejectedBeforeInkChanges() {
        val initial = InkData(
            width = 640f,
            height = 480f,
            strokes = listOf(InkStroke(List(INK_MAX_POINTS) { InkPoint(1f, 1f) }, id = newNoteId())),
        )
        val view = view().apply { ink = initial }
        var changes = 0
        var rejection: InkLimitViolation? = null
        view.onInkChanged = { _, next ->
            changes++
            next.validate()
        }
        view.onInkRejected = { rejection = it }

        touch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(view, MotionEvent.ACTION_UP, 100f, 100f)

        assertEquals(0, changes)
        assertEquals(InkLimitViolation.TooManyPoints, rejection)
        assertSame(initial, view.ink)
    }

    @Test fun newStrokeAtStrokeLimitIsRejectedBeforeInkChanges() {
        val initial = InkData(
            width = 640f,
            height = 480f,
            strokes = List(INK_MAX_STROKES) { InkStroke(listOf(InkPoint(1f, 1f))) },
        )
        val view = view().apply { ink = initial }
        var changes = 0
        var rejection: InkLimitViolation? = null
        view.onInkChanged = { _, next ->
            changes++
            next.validate()
        }
        view.onInkRejected = { rejection = it }

        touch(view, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(view, MotionEvent.ACTION_UP, 100f, 100f)

        assertEquals(0, changes)
        assertEquals(InkLimitViolation.TooManyStrokes, rejection)
        assertSame(initial, view.ink)
    }

    @Test fun eraserSplitAtStrokeLimitIsRejectedBeforeInkChanges() {
        val target = InkStroke(listOf(InkPoint(20f, 100f), InkPoint(620f, 100f)))
        val initial = InkData(
            width = 640f,
            height = 480f,
            strokes = List(INK_MAX_STROKES - 1) { InkStroke(listOf(InkPoint(1f, 1f))) } + target,
        )
        val view = view().apply {
            ink = initial
            eraserMode = InkEraserMode.Partial
        }
        var changes = 0
        var rejection: InkLimitViolation? = null
        view.onInkChanged = { _, next ->
            changes++
            next.validate()
        }
        view.onInkRejected = { rejection = it }

        touch(view, MotionEvent.ACTION_DOWN, 320f, 100f)
        touch(view, MotionEvent.ACTION_UP, 320f, 100f)

        assertEquals(0, changes)
        assertEquals(InkLimitViolation.TooManyStrokes, rejection)
        assertSame(initial, view.ink)
    }
}

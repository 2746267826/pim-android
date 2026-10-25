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
        view.onInkChanged = { output = it }
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
        view.onInkChanged = { count++ }
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
        view.onInkChanged = { count++ }
        val event = MotionEvent.obtain(1, 2, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        try { assertFalse(view.onTouchEvent(event)) } finally { event.recycle() }
        assertEquals(0, count)
    }
}

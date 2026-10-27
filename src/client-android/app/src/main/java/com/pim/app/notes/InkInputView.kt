package com.pim.app.notes

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View

/** Platform input coordinates are mapped to document units, independent of density and viewport. */
class InkInputView(context: Context) : View(context) {
    var ink = InkData()
        set(value) { field = value; invalidate() }
    var drawingEnabled = false
    var eraseEnabled = false
    var stylusOnly = false
    var inkColor: Int = 0
    var onInkChanged: (InkData) -> Unit = {}
    private var pointer = -1
    private var points = mutableListOf<InkPoint>()

    init { contentDescription = "手写画布"; isFocusable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        canvas.save()
        canvas.scale(width / ink.width, width / ink.width)
        ink.strokes.forEach { InkRendering.stroke(canvas, it, inkColor) }
        if (points.isNotEmpty()) InkRendering.stroke(canvas, InkStroke(points), inkColor)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (stylusOnly && event.getToolType(0) !in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) return false
                pointer = event.getPointerId(0)
                points.clear()
                parent?.requestDisallowInterceptTouchEvent(true)
                addPoint(event, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) return false
                for (i in 0 until event.historySize) add(event.getHistoricalX(index, i), event.getHistoricalY(index, i), event.getHistoricalPressure(index, i))
                addPoint(event, index)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != pointer) return true
                addPoint(event, event.actionIndex)
                if (points.isNotEmpty()) {
                    val next = if (eraseEnabled) ink.erasedBy(points) else ink.copy(strokes = ink.strokes + InkStroke(points.toList()))
                    ink = next
                    onInkChanged(next)
                }
                resetGesture(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun addPoint(event: MotionEvent, index: Int) = add(event.getX(index), event.getY(index), event.getPressure(index))
    private fun add(x: Float, y: Float, pressure: Float) {
        if (width <= 0 || points.size >= 30_000) return
        val scale = ink.width / width
        val next = InkPoint((x * scale).coerceIn(4f, ink.width - 4f), (y * scale).coerceIn(4f, ink.height - 4f),
            if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 1f)
        val last = points.lastOrNull()
        if (last == null || kotlin.math.hypot(next.x - last.x, next.y - last.y) >= 1f) points.add(next)
    }
    private fun resetGesture() { points.clear(); pointer = -1; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
}

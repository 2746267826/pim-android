package com.pim.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import com.pim.app.ui.theme.PimLightColorScheme

internal object InkRendering {
    fun stroke(canvas: Canvas, stroke: InkStroke, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = stroke.width
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(points[0].x, points[0].y, stroke.width / 2, paint)
        } else {
            val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
            canvas.drawPath(path, paint)
        }
    }

    fun bitmap(ink: InkData): Bitmap {
        ink.validate()
        return Bitmap.createBitmap(kotlin.math.ceil(ink.width.toDouble()).toInt(), kotlin.math.ceil(ink.height.toDouble()).toInt(), Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(PimLightColorScheme.surface.toArgb())
            ink.strokes.forEach { stroke -> stroke(canvas, stroke, PimLightColorScheme.primary.toArgb()) }
        }
    }
}

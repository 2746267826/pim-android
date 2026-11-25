package com.pim.app.notes

import androidx.compose.ui.graphics.toArgb
import com.pim.app.ui.theme.PimLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkRenderingTest {
    @Test
    fun perStrokeColorRendersWhileLegacyStrokeKeepsTheExistingThemeFallback() {
        val colored = InkRendering.bitmap(InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(30f, 40f)), width = 6f, color = "#b8373c"),
        )))
        try {
            assertEquals(android.graphics.Color.parseColor("#b8373c"), colored.getPixel(30, 40))
            assertEquals(android.graphics.Color.WHITE, colored.getPixel(1, 1))
        } finally {
            colored.recycle()
        }

        val legacy = InkRendering.bitmap(InkData(strokes = listOf(
            InkStroke(listOf(InkPoint(30f, 40f))),
        )))
        try {
            assertEquals(PimLightColorScheme.primary.toArgb(), legacy.getPixel(30, 40))
        } finally {
            legacy.recycle()
        }
    }
}

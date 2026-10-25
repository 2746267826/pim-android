package com.pim.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PimThemeTokensTest {
    @Test
    fun primaryKeepsPimBlueAndSurfaceTintUsesTheSameResource() {
        assertEquals(Color(0xFF1D63D8), PimLightColorScheme.primary)
        assertEquals(PimLightColorScheme.primary, PimLightColorScheme.surfaceTint)
        assertEquals(PimDarkColorScheme.primary, PimDarkColorScheme.surfaceTint)
        assertEquals(PimLightColorScheme.primary, PimDarkColorScheme.inversePrimary)
    }

    @Test
    fun lightAndDarkTextPairsMeetNormalTextContrast() {
        assertContrast("light", PimLightColorScheme, PimLightStatusColors)
        assertContrast("dark", PimDarkColorScheme, PimDarkStatusColors)
    }

    @Test
    fun businessScreensDoNotOwnLiteralColors() {
        val sources = uiSourceDirectory().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile?.name != "theme" }
        val literal = Regex("""\bColor\s*\(|\bColor\.(?:White|Black|Red|Blue|Green|Gray|Yellow)\b""")
        val offenders = sources.filter { literal.containsMatchIn(it.readText()) }
            .map { it.relativeTo(uiSourceDirectory()).path }.toList()
        assertTrue("Move literal colors into theme resources: $offenders", offenders.isEmpty())
    }

    private fun assertContrast(name: String, colors: ColorScheme, status: PimStatusColors) {
        val pairs = mapOf(
            "primary" to (colors.onPrimary to colors.primary),
            "primaryContainer" to (colors.onPrimaryContainer to colors.primaryContainer),
            "secondary" to (colors.onSecondary to colors.secondary),
            "secondaryContainer" to (colors.onSecondaryContainer to colors.secondaryContainer),
            "tertiary" to (colors.onTertiary to colors.tertiary),
            "tertiaryContainer" to (colors.onTertiaryContainer to colors.tertiaryContainer),
            "background" to (colors.onBackground to colors.background),
            "surface" to (colors.onSurface to colors.surface),
            "surfaceVariant" to (colors.onSurfaceVariant to colors.surfaceVariant),
            "inverseSurface" to (colors.inverseOnSurface to colors.inverseSurface),
            "error" to (colors.onError to colors.error),
            "errorContainer" to (colors.onErrorContainer to colors.errorContainer),
            "success" to (status.onSuccessContainer to status.successContainer),
            "warning" to (status.onWarningContainer to status.warningContainer)
        )
        pairs.forEach { (role, pair) ->
            val contrast = contrast(pair.first, pair.second)
            assertTrue("$name/$role contrast is $contrast", contrast >= 4.5)
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(value: Float): Double = if (value <= 0.04045f) value / 12.92
                else ((value + 0.055) / 1.055).pow(2.4)
            return linear(color.red) * 0.2126 + linear(color.green) * 0.7152 + linear(color.blue) * 0.0722
        }
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun uiSourceDirectory(): File {
        var current: File? = File("").canonicalFile
        while (current != null) {
            val path = current.resolve("src/main/java/com/pim/app/ui")
            if (path.isDirectory) return path
            current = current.parentFile
        }
        error("Android UI sources not found")
    }
}

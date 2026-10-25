package com.pim.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalPimDimensions = staticCompositionLocalOf { PimDefaultDimensions }
private val LocalPimStatusColors = staticCompositionLocalOf { PimLightStatusColors }

object PimTheme {
    val dimensions: PimDimensions
        @Composable @ReadOnlyComposable get() = LocalPimDimensions.current

    val statusColors: PimStatusColors
        @Composable @ReadOnlyComposable get() = LocalPimStatusColors.current
}

@Composable
fun PimTheme(
    darkTheme: Boolean = false,
    colorScheme: ColorScheme = if (darkTheme) PimDarkColorScheme else PimLightColorScheme,
    statusColors: PimStatusColors = if (darkTheme) PimDarkStatusColors else PimLightStatusColors,
    dimensions: PimDimensions = PimDefaultDimensions,
    content: @Composable () -> Unit
) {
    // Keep the existing light default; a system-wide dark-mode setting is a separate change.
    CompositionLocalProvider(
        LocalPimDimensions provides dimensions,
        LocalPimStatusColors provides statusColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PimTypography,
            shapes = PimShapes,
            content = content
        )
    }
}

package com.pim.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFF1D63D8)
private val DarkPrimary = Color(0xFF93B7FF)
private val LightText = Color(0xFF14243B)
private val DarkText = Color(0xFFE8EEF8)

internal val PimLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Paper,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF09306F),
    inversePrimary = DarkPrimary,
    secondary = Color(0xFF4A607C),
    onSecondary = Paper,
    secondaryContainer = Color(0xFFE0EAF8),
    onSecondaryContainer = LightText,
    tertiary = Color(0xFF476A79),
    onTertiary = Paper,
    tertiaryContainer = Color(0xFFD5EAF4),
    onTertiaryContainer = Color(0xFF123341),
    background = Color(0xFFF7F9FC),
    onBackground = LightText,
    surface = Paper,
    onSurface = LightText,
    surfaceVariant = Color(0xFFE8EEF6),
    onSurfaceVariant = Color(0xFF506176),
    surfaceTint = LightPrimary,
    inverseSurface = Color(0xFF273448),
    inverseOnSurface = DarkText,
    error = Color(0xFFB42332),
    onError = Paper,
    errorContainer = Color(0xFFFFDADF),
    onErrorContainer = Color(0xFF410D16),
    outline = Color(0xFF68788C),
    outlineVariant = Color(0xFFD9E1ED),
    scrim = Color(0xFF000000)
)

internal val PimDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF0B2149),
    primaryContainer = Color(0xFF273B54),
    onPrimaryContainer = Color(0xFFDCE8FF),
    inversePrimary = LightPrimary,
    secondary = Color(0xFFB6C9E4),
    onSecondary = Color(0xFF203248),
    secondaryContainer = Color(0xFF364A63),
    onSecondaryContainer = Color(0xFFE0EAF8),
    tertiary = Color(0xFFA6CEDF),
    onTertiary = Color(0xFF123341),
    tertiaryContainer = Color(0xFF2E4D5B),
    onTertiaryContainer = Color(0xFFD5EAF4),
    background = Color(0xFF121A29),
    onBackground = DarkText,
    surface = Color(0xFF1C2738),
    onSurface = DarkText,
    surfaceVariant = Color(0xFF2A374B),
    onSurfaceVariant = Color(0xFFB4C0D2),
    surfaceTint = DarkPrimary,
    inverseSurface = Color(0xFFE8EEF6),
    inverseOnSurface = LightText,
    error = Color(0xFFFFADB4),
    onError = Color(0xFF600F20),
    errorContainer = Color(0xFF402A32),
    onErrorContainer = Color(0xFFFFDADF),
    outline = Color(0xFF91A1B8),
    outlineVariant = Color(0xFF354256),
    scrim = Color(0xFF000000)
)

@Immutable
data class PimStatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

internal val PimLightStatusColors = PimStatusColors(
    successContainer = Color(0xFFE7F4EE),
    onSuccessContainer = Color(0xFF1C6C50),
    warningContainer = Color(0xFFFFF5E6),
    onWarningContainer = Color(0xFF8A540D)
)

internal val PimDarkStatusColors = PimStatusColors(
    successContainer = Color(0xFF203D33),
    onSuccessContainer = Color(0xFF8BD3B0),
    warningContainer = Color(0xFF3C3023),
    onWarningContainer = Color(0xFFF2C37F)
)

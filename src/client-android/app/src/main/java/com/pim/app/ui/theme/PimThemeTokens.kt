package com.pim.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class PimDimensions(
    val spaceExtraSmall: Dp = 4.dp,
    val spaceSmall: Dp = 8.dp,
    val spaceMedium: Dp = 12.dp,
    val spaceLarge: Dp = 16.dp,
    val spaceExtraLarge: Dp = 24.dp,
    val sectionPadding: Dp = 16.dp,
    val sectionGap: Dp = 10.dp,
    val borderWidth: Dp = 1.dp,
    val iconSize: Dp = 18.dp,
    val minimumTouchTarget: Dp = 48.dp,
    val recordingWaveWidth: Dp = 32.dp,
    val recordingWaveHeight: Dp = 20.dp,
    val notePhotoMaxHeight: Dp = 360.dp,
    val floatingElevation: Dp = 4.dp,
    val noteDocumentMaxWidth: Dp = 760.dp,
    val noteWideBreakpoint: Dp = 1000.dp,
    val noteMaterialsRailWidth: Dp = 208.dp,
    val noteEditorMinHeight: Dp = 200.dp,
    val noteDockClearance: Dp = 88.dp,
    val noteHeaderClearance: Dp = 72.dp,
    val noteDefaultInkWidth: Dp = 320.dp,
    val inkGridSpacing: Dp = 24.dp
)

internal val PimDefaultDimensions = PimDimensions()

internal val PimShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp
)

internal val PimTypography = Typography(
    headlineLarge = textStyle(30, 42, FontWeight.Medium),
    headlineMedium = textStyle(26, 38, FontWeight.Medium),
    titleLarge = textStyle(24, 34, FontWeight.Medium),
    titleMedium = textStyle(19, 28, FontWeight.Medium),
    titleSmall = textStyle(16, 24, FontWeight.Medium),
    bodyLarge = textStyle(16, 28),
    bodyMedium = textStyle(14, 22),
    bodySmall = textStyle(12, 20),
    labelLarge = textStyle(14, 20, FontWeight.Medium),
    labelMedium = textStyle(12, 18, FontWeight.Medium),
    labelSmall = textStyle(12, 16, FontWeight.Medium)
)

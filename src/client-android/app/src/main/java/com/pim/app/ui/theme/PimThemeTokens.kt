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
    val recordingWaveWidth: Dp = 64.dp,
    val recordingWaveHeight: Dp = 20.dp,
    val notePhotoMaxHeight: Dp = 360.dp
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

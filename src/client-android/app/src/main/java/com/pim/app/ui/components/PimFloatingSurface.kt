package com.pim.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pim.app.ui.theme.PimTheme

@Composable
fun PimFloatingSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(PimTheme.dimensions.borderWidth, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = PimTheme.dimensions.floatingElevation,
        content = content
    )
}

package com.pim.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.pim.app.ui.theme.PimTheme

enum class PimStatusTone { Info, Success, Warning, Error }

@Composable
fun PimStatusBanner(
    text: String,
    tone: PimStatusTone,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val status = PimTheme.statusColors
    val dimensions = PimTheme.dimensions
    val (container, foreground) = when (tone) {
        PimStatusTone.Info -> scheme.primaryContainer to scheme.onPrimaryContainer
        PimStatusTone.Success -> status.successContainer to status.onSuccessContainer
        PimStatusTone.Warning -> status.warningContainer to status.onWarningContainer
        PimStatusTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    val icon = when (tone) {
        PimStatusTone.Info -> Icons.Filled.Info
        PimStatusTone.Success -> Icons.Filled.CheckCircle
        PimStatusTone.Warning -> Icons.Filled.WarningAmber
        PimStatusTone.Error -> Icons.Filled.ErrorOutline
    }

    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = foreground
    ) {
        Row(
            modifier = Modifier.padding(dimensions.spaceMedium),
            horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(dimensions.iconSize))
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

package com.pim.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pim.app.recording.RecordingPhase
import com.pim.app.recording.RecordingState
import com.pim.app.recording.recordingDuration
import com.pim.app.ui.theme.PimTheme

@Composable
fun PimRecordingPill(state: RecordingState, onPauseOrResume: () -> Unit, modifier: Modifier = Modifier) {
    val session = state.session ?: return
    val dimensions = PimTheme.dimensions
    val paused = session.phase == RecordingPhase.Paused
    val waveformColor = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    Surface(modifier.testTag("recording-pill"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(start = dimensions.spaceMedium).defaultMinSize(minHeight = dimensions.minimumTouchTarget),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSmall)) {
            Canvas(Modifier.size(dimensions.recordingWaveWidth, dimensions.recordingWaveHeight)
                .semantics { contentDescription = if (paused) "录音已暂停" else "实际音频输入波形" }) {
                drawLine(waveformColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                state.peaks.forEachIndexed { index, peak ->
                    val x = size.width * (index + 0.5f) / 32
                    val height = size.height * peak.coerceIn(0f, 1f)
                    drawLine(waveformColor, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                        strokeWidth = size.width / 64)
                }
            }
            Text("${if (paused) "已暂停" else "第 ${session.currentSegment} 段"}\n${recordingDuration(session.durationMillis)}",
                style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onPauseOrResume, modifier = Modifier.testTag("recording-pause-resume")) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "继续录音" else "暂停录音")
            }
        }
    }
}

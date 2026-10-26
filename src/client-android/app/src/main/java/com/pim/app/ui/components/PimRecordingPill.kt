package com.pim.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.pim.app.recording.RecordingPhase
import com.pim.app.recording.RecordingState
import com.pim.app.recording.recordingDuration
import com.pim.app.ui.theme.PimTheme

@Composable
fun PimRecordingPill(
    state: RecordingState, onPauseOrResume: () -> Unit, modifier: Modifier = Modifier,
    onDetails: (() -> Unit)? = null
) {
    val session = state.session ?: return
    val d = PimTheme.dimensions
    val paused = session.phase == RecordingPhase.Paused
    val waveformColor = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    PimFloatingSurface(modifier.testTag("recording-pill")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false)
                .then(if (onDetails != null) Modifier.clickable(onClickLabel = "查看本篇录音", onClick = onDetails) else Modifier)
                .padding(horizontal = d.spaceMedium).defaultMinSize(minHeight = d.minimumTouchTarget),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
                Canvas(Modifier.size(d.recordingWaveWidth, d.recordingWaveHeight)
                    .semantics { contentDescription = if (paused) "录音已暂停" else "实际音频输入波形" }) {
                    val bars = 12
                    val peaks = if (paused) emptyList() else state.peaks.takeLast(bars)
                    repeat(bars) { index ->
                        val x = size.width * (index + 0.5f) / bars
                        val height = maxOf(d.borderWidth.toPx(), size.height * (peaks.getOrNull(index) ?: 0f).coerceIn(0f, 1f))
                        drawLine(waveformColor, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2),
                            strokeWidth = size.width / (bars * 2), cap = StrokeCap.Round)
                    }
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(recordingDuration(session.durationMillis), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text(if (paused) "已暂停" else "录音中 · 第 ${session.currentSegment} 段",
                        color = waveformColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onPauseOrResume, modifier = Modifier.size(d.minimumTouchTarget).testTag("recording-pause-resume")) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "继续录音" else "暂停录音")
            }
        }
    }
}

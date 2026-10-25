package com.pim.app.recording

import kotlinx.serialization.Serializable

@Serializable
enum class RecordingPhase { Recording, Paused, Completed, Interrupted }

@Serializable
enum class RecordingProblem(val message: String) {
    Permission("未允许麦克风权限，没有开始录音"),
    Foreground("系统未允许启动前台录音，请回到页面后重试"),
    Input("音频采集中断，已保存的材料保留在本机"),
    Silenced("系统已将麦克风静音，录音已中断，请检查其他应用或权限"),
    Storage("本地写入失败，原始文件保留在本机，请检查存储空间"),
    ProcessEnded("上次录音已中断，没有自动重新开启麦克风"),
    Damaged("部分录音材料无法读取，原始文件已保留，不能视为完整记录"),
    Empty("没有采集到有效音频，不能视为录音已保存")
}

@Serializable
data class RecordingChunk(val segment: Int, val index: Int, val samples: Long = 0) {
    val fileName: String get() = "s%04d-c%06d.wav".format(java.util.Locale.ROOT, segment, index)
}

@Serializable
data class RecordingSession(
    val id: String,
    val createdAtMillis: Long,
    val phase: RecordingPhase = RecordingPhase.Recording,
    val currentSegment: Int = 1,
    val chunks: List<RecordingChunk> = emptyList(),
    val problem: RecordingProblem? = null,
    val schemaVersion: Int = 1
) {
    val samples: Long get() = chunks.sumOf { it.samples }
    val durationMillis: Long get() = samples * 1000 / RecordingPolicy.SAMPLE_RATE
    val currentSegmentSamples: Long get() = chunks.filter { it.segment == currentSegment }.sumOf { it.samples }
    val active: Boolean get() = phase == RecordingPhase.Recording || phase == RecordingPhase.Paused
}

data class RecordingPolicy(
    val chunkSamples: Long = SAMPLE_RATE * 60L,
    val segmentSamples: Long = SAMPLE_RATE * 3L * 60 * 60,
    val checkpointSamples: Long = SAMPLE_RATE.toLong()
) {
    init { require(chunkSamples > 0 && segmentSamples > 0 && checkpointSamples > 0) }
    companion object { const val SAMPLE_RATE = 16_000 }
}

data class RecordingArchive(val id: String, val session: RecordingSession?, val damaged: Boolean = false)

data class RecordingState(
    val session: RecordingSession? = null,
    val peaks: List<Float> = emptyList(),
    val preparing: Boolean = false,
    val problem: RecordingProblem? = null,
    val archives: List<RecordingArchive> = emptyList()
) {
    val active: Boolean get() = session?.active == true
}

fun recordingDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
}

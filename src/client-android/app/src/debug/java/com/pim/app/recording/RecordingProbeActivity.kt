package com.pim.app.recording

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pim.app.ui.components.PimButton
import com.pim.app.ui.components.PimRecordingPill
import com.pim.app.ui.components.PimSection
import com.pim.app.ui.components.PimStatusBanner
import com.pim.app.ui.components.PimStatusTone
import com.pim.app.ui.theme.PimTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecordingProbeActivity : ComponentActivity() {
    @Inject lateinit var repository: RecordingRepository
    private var notificationsVisible by mutableStateOf(true)
    private var playingId by mutableStateOf<String?>(null)
    private var playbackFailed by mutableStateOf(false)
    private var player: MediaPlayer? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshNotificationVisibility()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            RecordingController.start(this)?.let { repository.report(it) }
        } else repository.report(RecordingProblem.Permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            try { repository.initialize() } catch (_: Exception) { repository.report(RecordingProblem.Storage) }
        }
        setContent {
            val state by repository.state.collectAsStateWithLifecycle()
            PimTheme {
                val dimensions = PimTheme.dimensions
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().padding(dimensions.spaceLarge)) {
                        Column(Modifier.fillMaxSize().padding(bottom = dimensions.minimumTouchTarget + dimensions.spaceLarge)
                            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(dimensions.spaceMedium)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("录音验证 · 本地", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (state.active) PimButton(onClick = {
                                    state.session?.let { RecordingController.finish(this@RecordingProbeActivity, it.id)?.let(repository::report) }
                                }, modifier = Modifier.testTag("recording-finish")) { Text("结束") }
                            }
                            PimStatusBanner("开发验证入口，不是笔记首版。音频仅保存在本机，没有上传、转写或AI分析。",
                                PimStatusTone.Info)
                            Text("16 kHz 单声道 WAV，约 115 MB/小时；请留足空间。内部分片约一分钟，不改变用户录音段。",
                                style = MaterialTheme.typography.bodySmall)
                            if (!notificationsVisible) PimStatusBanner("系统通知未允许或渠道已关闭，后台状态可能不可见，请到系统设置检查。", PimStatusTone.Warning)
                            state.problem?.let { PimStatusBanner(it.message, PimStatusTone.Error, Modifier.testTag("recording-problem")) }
                            if (!state.active) PimButton(onClick = {
                                stopPlayback()
                                permissions.launch(if (Build.VERSION.SDK_INT >= 33)
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                else arrayOf(Manifest.permission.RECORD_AUDIO))
                            }, enabled = !state.preparing, modifier = Modifier.testTag("recording-start")) { Text("开始真实录音") }
                            if (state.preparing) Text("正在准备录音")
                            if (state.session?.phase == RecordingPhase.Completed) Text("本地保存完成，未上传", Modifier.testTag("recording-saved"))
                            Text("本机验证记录", style = MaterialTheme.typography.titleSmall)
                            if (state.archives.isEmpty()) Text("暂无已保存录音")
                            state.archives.forEach { archive ->
                                val session = archive.session
                                PimSection(session?.let {
                                    Instant.ofEpochMilli(it.createdAtMillis).atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                                } ?: "无法读取的记录（原始文件保留）") {
                                    if (session != null) {
                                        Text("${if (session.phase == RecordingPhase.Completed) "已保存" else "已中断"} · ${recordingDuration(session.durationMillis)} · ${session.chunks.map { it.segment }.distinct().size} 段")
                                        session.problem?.let { Text(it.message) }
                                        if (!archive.damaged && session.samples > 0) PimButton(onClick = {
                                            if (playingId == session.id) stopPlayback()
                                            else play(session.id, repository.audioFiles(session))
                                        }, enabled = !state.active) { Text(if (playingId == session.id) "停止回放" else "回放全部段") }
                                    }
                                }
                            }
                            if (playbackFailed) Text("回放失败，原始文件仍保留在本机", color = MaterialTheme.colorScheme.error)
                        }
                        if (state.active) PimRecordingPill(state, onPauseOrResume = {
                            state.session?.let {
                                val problem = if (it.phase == RecordingPhase.Paused) RecordingController.resume(this@RecordingProbeActivity, it.id)
                                    else RecordingController.pause(this@RecordingProbeActivity, it.id)
                                problem?.let(repository::report)
                            }
                        }, modifier = Modifier.align(Alignment.BottomStart))
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); refreshNotificationVisibility() }
    override fun onStop() { stopPlayback(); super.onStop() }

    private fun refreshNotificationVisibility() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        notificationsVisible = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (manager.getNotificationChannel("pim_recording_v1")?.importance != android.app.NotificationManager.IMPORTANCE_NONE)
    }

    private fun play(id: String, files: List<File>) {
        stopPlayback()
        playbackFailed = false
        playingId = id
        fun next(index: Int) {
            if (playingId != id || index >= files.size) { stopPlayback(); return }
            try {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(files[index].absolutePath)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { next(index + 1) }
                    setOnErrorListener { _, _, _ -> playbackFailed = true; stopPlayback(); true }
                    prepareAsync()
                }
            } catch (_: Exception) { playbackFailed = true; stopPlayback() }
        }
        next(0)
    }

    private fun stopPlayback() { player?.release(); player = null; playingId = null }
}

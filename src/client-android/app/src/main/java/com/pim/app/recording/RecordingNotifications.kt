package com.pim.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pim.app.MainActivity
import com.pim.app.notes.NativeNotesActivity

internal object RecordingNotifications {
    const val ID = 8201
    private const val CHANNEL = "pim_recording_v1"
    const val PROBE_ACTIVITY = "com.pim.app.recording.RecordingProbeActivity"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "录音状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "用户主动开始的录音、暂停及中断状态"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun build(context: Context, state: RecordingState, interrupted: Boolean = false): Notification {
        val session = state.session
        val title = when {
            interrupted -> "录音已中断"
            session?.phase == RecordingPhase.Paused -> "录音已暂停"
            session?.phase == RecordingPhase.Recording -> "正在录音"
            else -> "正在准备录音"
        }
        val text = if (interrupted) (state.problem ?: RecordingProblem.Input).message
            else "第 ${session?.currentSegment ?: 1} 段 · ${recordingDuration(session?.durationMillis ?: 0)} · 仅本机保存"
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, 82010, openIntent(context, session?.noteId), FLAGS))
            .setOnlyAlertOnce(true).setOngoing(!interrupted).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).build())
        if (session?.active == true && !interrupted) {
            val paused = session.phase == RecordingPhase.Paused
            val action = if (paused) RecordingController.RESUME else RecordingController.PAUSE
            builder.addAction(0, if (paused) "继续" else "暂停", PendingIntent.getService(
                context, 82011, RecordingController.intent(context, action, session.id), FLAGS))
            builder.addAction(0, "结束录音", PendingIntent.getService(
                context, 82012, RecordingController.intent(context, RecordingController.FINISH, session.id), FLAGS))
        }
        return builder.build()
    }

    fun openIntent(context: Context, noteId: String? = null): Intent {
        if (noteId != null) return NativeNotesActivity.intent(context, noteId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val probe = ComponentName(context.packageName, PROBE_ACTIVITY)
        val exists = runCatching { context.packageManager.getActivityInfo(probe, 0) }.isSuccess
        return (if (exists) Intent().setComponent(probe) else Intent(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}

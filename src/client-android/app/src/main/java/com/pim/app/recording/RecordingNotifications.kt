package com.pim.app.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pim.app.MainActivity
import com.pim.app.notes.NativeNotesActivity

internal object RecordingNotifications {
    const val ID = 8201
    internal const val CHANNEL = "pim_recording_v1"
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
        val publicText = "${recordingDuration(session?.durationMillis ?: 0)} · 第 ${session?.currentSegment ?: 1} 段"
        val publicVersion = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(publicText)
            .build()
        val activeSession = session?.takeIf { it.active && !interrupted }
        if (activeSession != null && Build.VERSION.SDK_INT >= 36 &&
            RecordingLiveUpdateCapability.isAvailable(context)
        ) {
            return buildPromoted(context, activeSession, title, text, publicVersion)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, 82010, openIntent(context, session?.noteId), FLAGS))
            .setOnlyAlertOnce(true).setOngoing(!interrupted).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        if (activeSession != null) {
            actions(context, activeSession).forEach { (label, intent) -> builder.addAction(0, label, intent) }
        }
        return builder.build()
    }

    @SuppressLint("NewApi")
    private fun buildPromoted(
        context: Context,
        session: RecordingSession,
        title: String,
        text: String,
        publicVersion: Notification,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, 82010, openIntent(context, session.noteId), FLAGS))
            .setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        actions(context, session).forEach { (label, intent) ->
            builder.addAction(Notification.Action.Builder(null, label, intent).build())
        }
        try {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText("${session.currentSegment}段")
        } catch (_: LinkageError) {
        }
        return builder.build()
    }

    private fun actions(context: Context, session: RecordingSession): List<Pair<String, PendingIntent>> {
        val paused = session.phase == RecordingPhase.Paused
        val action = if (paused) RecordingController.RESUME else RecordingController.PAUSE
        return listOf(
            (if (paused) "继续" else "暂停") to PendingIntent.getService(
                context, 82011, RecordingController.intent(context, action, session.id), FLAGS),
            "结束录音" to PendingIntent.getService(
                context, 82012, RecordingController.intent(context, RecordingController.FINISH, session.id), FLAGS),
        )
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

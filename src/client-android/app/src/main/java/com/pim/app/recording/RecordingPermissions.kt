package com.pim.app.recording

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class RecordingPermissions(
    val microphoneGranted: Boolean = true,
    val notificationPermissionGranted: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val channelEnabled: Boolean = true,
) {
    val notificationsReady: Boolean
        get() = notificationPermissionGranted && notificationsEnabled && channelEnabled

    val canStart: Boolean
        get() = microphoneGranted && notificationsReady

    val missingLabels: List<String>
        get() = buildList {
            if (!microphoneGranted) add("麦克风权限")
            if (!notificationPermissionGranted) add("通知权限")
            else if (!notificationsEnabled) add("系统通知总开关")
            if (!channelEnabled) add("录音状态通知渠道")
        }

    val message: String
        get() = "还不能开始录音：${missingLabels.joinToString("、")}。" +
            "笔记仍保存在本机；完成授权或打开设置后，回到这里再点「开始录音」。"

    fun startProblem(): RecordingProblem? = when {
        !microphoneGranted -> RecordingProblem.Permission
        !notificationPermissionGranted || !notificationsEnabled -> RecordingProblem.Notifications
        !channelEnabled -> RecordingProblem.NotificationChannel
        else -> null
    }
}

internal object RecordingPermissionsInspector {
    fun inspect(context: Context): RecordingPermissions {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RecordingNotifications.CHANNEL)
        return RecordingPermissions(
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
            notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = manager.areNotificationsEnabled(),
            channelEnabled = channel == null ||
                channel.importance != NotificationManager.IMPORTANCE_NONE,
        )
    }
}

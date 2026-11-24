package com.pim.app.recording

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

internal object RecordingLiveUpdateCapability {
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (!supportsLiveUpdates(Build.VERSION.SDK_INT) { Api36.atLeastBaklava1() }) return false
        return try {
            context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        } catch (_: LinkageError) {
            false
        }
    }

    internal fun supportsLiveUpdates(majorSdk: Int, fullSdkCheck: () -> Boolean): Boolean {
        if (majorSdk < 36) return false
        if (majorSdk > 36) return true
        return try {
            fullSdkCheck()
        } catch (_: LinkageError) {
            false
        }
    }

    @RequiresApi(36)
    @SuppressLint("NewApi")
    private object Api36 {
        fun atLeastBaklava1(): Boolean =
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
    }
}

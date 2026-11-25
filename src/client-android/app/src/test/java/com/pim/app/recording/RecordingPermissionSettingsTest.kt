package com.pim.app.recording

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class RecordingPermissionSettingsTest {
    @Test fun settingsEntriesTargetTheExactApplicationNotificationAndChannelScreens() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val app = RecordingPermissionSetting.AppSettings.createIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.action)
        assertEquals("package", app.data?.scheme)
        assertEquals(context.packageName, app.data?.schemeSpecificPart)

        val notifications = RecordingPermissionSetting.NotificationSettings.createIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(context.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        val channel = RecordingPermissionSetting.RecordingChannelSettings.createIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals(context.packageName, channel.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(RecordingNotifications.CHANNEL, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun requestCopyAndRuntimePermissionsNameOnlyWhatIsMissing() {
        val microphoneOnly = RecordingPermissions(microphoneGranted = false)
        assertEquals("允许麦克风", microphoneOnly.requestActionLabel)
        assertEquals(listOf("麦克风权限"), microphoneOnly.missingLabels)
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            microphoneOnly.runtimePermissionsToRequest(34),
        )

        val notificationsOnly = RecordingPermissions(notificationPermissionGranted = false)
        assertEquals("允许使用通知", notificationsOnly.requestActionLabel)
        assertEquals(listOf("通知权限"), notificationsOnly.missingLabels)
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            notificationsOnly.runtimePermissionsToRequest(34),
        )
        assertTrue(notificationsOnly.runtimePermissionsToRequest(32).isEmpty())

        val both = RecordingPermissions(
            microphoneGranted = false,
            notificationPermissionGranted = false,
        )
        assertEquals("允许麦克风和使用通知", both.requestActionLabel)
        assertArrayEquals(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            both.runtimePermissionsToRequest(34),
        )
        assertNull(RecordingPermissions().requestActionLabel)
    }
}

package com.pim.app.recording

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class RecordingBoundaryTest {
    @Test fun serviceIsPrivateMicrophoneOnlyAndDoesNotReplaceLocationService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS)
        val services = info.services.orEmpty()
        val permissions = info.requestedPermissions.orEmpty()
        val microphone = services.single { it.name == RecordingService::class.java.name }
        assertFalse(microphone.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, microphone.foregroundServiceType)
        assertTrue(services.any { it.name.endsWith("ForegroundLocationService") })
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertFalse(permissions.contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
    }

    @Test fun permissionDenialDoesNotLaunchAnyRecordingService() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(activity.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(RecordingProblem.Permission, RecordingController.start(activity))
        assertNull(shadowOf(activity.application).nextStartedService)
    }

    @Test fun notificationContainsTruthfulPauseStateAndNoSessionIdentityInVisibleText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RecordingNotifications.createChannel(app)
        val session = RecordingSession("private-session-identity", 1, phase = RecordingPhase.Paused,
            chunks = listOf(RecordingChunk(1, 1, 16_000)))
        val notification = RecordingNotifications.build(app, RecordingState(session))
        assertEquals("录音已暂停", notification.extras.getString("android.title"))
        val text = notification.extras.getString("android.text")!!
        assertTrue(text.contains("00:00:01"))
        assertFalse(text.contains(session.id))
        assertEquals("继续", notification.actions[0].title)
        assertEquals("结束录音", notification.actions[1].title)
        assertEquals("录音已暂停", notification.publicVersion.extras.getString("android.title"))
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel("pim_recording_v1")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test fun durationDoesNotWrapAfterOneDayOrBecomeNegative() {
        assertEquals("25:00:00", recordingDuration(25 * 60 * 60 * 1000L))
        assertEquals("00:00:00", recordingDuration(-1))
    }

    @Test fun newNotificationCannotRetargetPreviouslyIssuedPendingIntentToNewSession() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RecordingNotifications.createChannel(app)
        val first = RecordingNotifications.build(app, RecordingState(RecordingSession("first-session", 1)))
        val oldStop = first.actions.last().actionIntent
        RecordingNotifications.build(app, RecordingState(RecordingSession("second-session", 2)))
        assertEquals("first-session", shadowOf(oldStop).savedIntent.getStringExtra(RecordingController.SESSION))
    }
}

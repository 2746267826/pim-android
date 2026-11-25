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

    @Test fun globalNotificationSwitchBlocksRecordingService() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val app = activity.application
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)

        assertEquals(RecordingProblem.Notifications, RecordingController.start(activity))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun notificationPermissionDenialBlocksRecordingService() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val app = activity.application
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertEquals(RecordingProblem.Notifications, RecordingController.start(activity))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun disabledRecordingChannelBlocksRecordingService() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val app = activity.application
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        RecordingNotifications.createChannel(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                RecordingNotifications.CHANNEL,
                "录音状态",
                NotificationManager.IMPORTANCE_NONE,
            )
        )

        val permissions = RecordingPermissionsInspector.inspect(app)
        assertFalse(permissions.channelEnabled)
        assertEquals(RecordingProblem.NotificationChannel, RecordingController.start(activity))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun onResumeStyleInspectionReadsCurrentNotificationState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        RecordingNotifications.createChannel(app)
        val manager = app.getSystemService(NotificationManager::class.java)

        assertTrue(RecordingPermissionsInspector.inspect(app).canStart)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(RecordingPermissionsInspector.inspect(app).canStart)
        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(RecordingPermissionsInspector.inspect(app).canStart)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                RecordingNotifications.CHANNEL,
                "录音状态",
                NotificationManager.IMPORTANCE_NONE,
            )
        )
        assertFalse(RecordingPermissionsInspector.inspect(app).canStart)
    }

    @Test fun missingPrerequisitesAreNamedInUserFacingOrder() {
        val permissions = RecordingPermissions(
            microphoneGranted = false,
            notificationPermissionGranted = false,
            channelEnabled = false,
        )

        assertEquals(listOf("麦克风权限", "通知权限", "录音状态通知渠道"), permissions.missingLabels)
        assertFalse(permissions.canStart)
        assertTrue(permissions.canRequest)
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

    @Test fun lockScreenPublicVersionShowsDurationAndSegmentWithoutNoteIdentity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        RecordingNotifications.createChannel(app)
        val session = RecordingSession(
            "private-session-identity", 1, currentSegment = 2,
            chunks = listOf(RecordingChunk(1, 1, 16_000 * 60), RecordingChunk(2, 1, 16_000 * 30)),
            noteId = "private-note-secret"
        )
        val public = RecordingNotifications.build(app, RecordingState(session)).publicVersion!!
        assertEquals("正在录音", public.extras.getString("android.title"))
        assertEquals("00:01:30 · 第 2 段", public.extras.getString("android.text"))
        assertFalse(public.extras.toString().contains(session.id))
        assertFalse(public.extras.toString().contains("private-note-secret"))
        assertTrue(public.actions.orEmpty().isEmpty())
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

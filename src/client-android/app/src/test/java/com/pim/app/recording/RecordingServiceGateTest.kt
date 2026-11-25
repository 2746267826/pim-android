package com.pim.app.recording

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class RecordingServiceGateTest {
    @Test fun startCommandWithoutIntentIsRejectedBeforeAnyServiceWork() {
        val service = recordingService()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(service.repository.state.value.problem)
    }

    @Test fun startCommandGatesMicrophoneIndependentlyOfTheCaller() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = recordingService()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(service), 0, 1))
        assertEquals(RecordingProblem.Permission, service.repository.state.value.problem)
    }

    @Test fun startCommandGatesNotificationPermissionAndGlobalSwitchIndependently() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val permissionDenied = recordingService()

        assertEquals(Service.START_NOT_STICKY, permissionDenied.onStartCommand(startIntent(permissionDenied), 0, 1))
        assertEquals(RecordingProblem.Notifications, permissionDenied.repository.state.value.problem)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        val switchDisabled = recordingService()

        assertEquals(Service.START_NOT_STICKY, switchDisabled.onStartCommand(startIntent(switchDisabled), 0, 1))
        assertEquals(RecordingProblem.Notifications, switchDisabled.repository.state.value.problem)
    }

    @Test fun startCommandGatesRecordingNotificationChannelIndependently() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        RecordingNotifications.createChannel(app)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(
                RecordingNotifications.CHANNEL,
                "录音状态",
                NotificationManager.IMPORTANCE_NONE,
            )
        )
        val service = recordingService()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(service), 0, 1))
        assertEquals(RecordingProblem.NotificationChannel, service.repository.state.value.problem)
    }

    private fun recordingService(): RecordingService {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return Robolectric.buildService(RecordingService::class.java).get().apply {
            repository = RecordingRepository(app)
        }
    }

    private fun startIntent(service: RecordingService) =
        Intent(service, RecordingService::class.java).setAction(RecordingController.START)
}

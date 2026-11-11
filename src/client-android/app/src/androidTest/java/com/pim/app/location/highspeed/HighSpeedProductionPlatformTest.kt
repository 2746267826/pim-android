package com.pim.app.location.highspeed

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.MainActivity
import com.pim.app.location.service.ForegroundLocationController
import com.pim.app.location.service.ForegroundLocationService
import com.pim.app.settings.TrackingSettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real app/foreground service/Fused/Room chain with synthetic platform positions; no account. */
@RunWith(AndroidJUnit4::class)
class HighSpeedProductionPlatformTest {
    @Test fun stationarySpikesThenCoherentTravelUpdateRealNotificationAndStopCleanly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = TrackingSettingsStore(context.getSharedPreferences("pim_tracking", Context.MODE_PRIVATE))
        val originalSettings = settings.read()
        val manager = context.getSystemService(LocationManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val automation = instrumentation.uiAutomation
        automation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}").close()
        automation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}").close()
        automation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.ACCESS_BACKGROUND_LOCATION}").close()
        automation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").close()
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        settings.write(originalSettings.copy(continuousCollectionEnabled = false, sprintEnabled = false))
        automation.adoptShellPermissionIdentity("android.permission.ACCESS_MOCK_LOCATION")
        try {
            @Suppress("DEPRECATION")
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            ContextCompat.startForegroundService(context, Intent(context, ForegroundLocationService::class.java)
                .setAction(ForegroundLocationController.ACTION_START_COLLECTION))

            fun inject(east: Double, speed: Float) {
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 0.0; longitude = east / 111_194.927; altitude = 10.0
                    accuracy = 5f; this.speed = speed; bearing = 90f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }

            // Fixes also satisfy warmup/automatic stream. Fixed coordinates plus
            // sustained speed spikes must never put the real runtime into Active.
            repeat(16) { inject(0.0, 20f); SystemClock.sleep(2_500); assertFalse(ForegroundLocationService.runtimeState.value.highSpeedActive) }
            assertTrue("existing foreground service must remain running", ForegroundLocationService.runtimeState.value.isRunning)
            assertNotNull("collection notification is present", notifications.activeNotifications.firstOrNull { it.id == 7101 })

            // Restart collection for a separate coherent-travel episode; this
            // also exercises the real controller teardown/restart boundary.
            context.startService(Intent(context, ForegroundLocationService::class.java)
                .setAction(ForegroundLocationController.ACTION_STOP_COLLECTION))
            SystemClock.sleep(1_000)
            ContextCompat.startForegroundService(context, Intent(context, ForegroundLocationService::class.java)
                .setAction(ForegroundLocationController.ACTION_START_COLLECTION))
            repeat(20) { index -> inject(index * 25.0, 10f); SystemClock.sleep(2_500) }
            assertTrue("coherent fresh platform positions activate production runtime", ForegroundLocationService.runtimeState.value.highSpeedActive)
            val notification = notifications.activeNotifications.firstOrNull { it.id == 7101 }?.notification
            assertNotNull(notification)
            assertTrue(notification!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("高速轨迹记录中"))
            context.startService(Intent(context, ForegroundLocationService::class.java)
                .setAction(ForegroundLocationController.ACTION_STOP_COLLECTION))
            SystemClock.sleep(1_000)
            assertFalse(ForegroundLocationService.runtimeState.value.highSpeedActive)
        } finally {
            context.startService(Intent(context, ForegroundLocationService::class.java)
                .setAction(ForegroundLocationController.ACTION_STOP_COLLECTION))
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            settings.write(originalSettings.copy(continuousCollectionEnabled = false))
            automation.dropShellPermissionIdentity()
        }
    }
}

package com.pim.app.location.lab

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class DevLocationExperimentTest {
    private lateinit var context: Application
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("local-location-lab", 0).edit().clear().commit()
        LocationLabStore(context).csvFile.delete()
    }

    @Test fun blockedLocationStillAcknowledgesForegroundStartBeforeStopping() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        val host = Robolectric.buildService(ExperimentHost::class.java).create().get()
        val store = LocationLabStore(context)
        val experiment = DevLocationExperiment(context, store)
        val result = experiment.onStartCommand(host, Intent(DevLocationExperiment.ACTION_START), 1)
        assertEquals(Service.START_NOT_STICKY, result)
        assertNotNull("启动被前置条件阻断时也必须确认 Android 的前台服务启动请求，避免超时杀进程", shadowOf(host).lastForegroundNotification)
        assertFalse(store.requested)
        assertTrue(store.latestStatus.contains("已停止"))
    }

    @Test fun expiredSessionAcknowledgesForegroundStartBeforeRejectingOldClock() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = Robolectric.buildService(ExperimentHost::class.java).create().get()
        val store = LocationLabStore(context)
        store.begin(android.os.SystemClock.elapsedRealtime() - LocationLabStore.MAX_SESSION_MILLIS)
        val result = DevLocationExperiment(context, store).onStartCommand(host, Intent(DevLocationExperiment.ACTION_START), 1)
        assertEquals(Service.START_NOT_STICKY, result)
        assertNotNull(shadowOf(host).lastForegroundNotification)
        assertFalse(store.requested)
    }

    class ExperimentHost : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }
}

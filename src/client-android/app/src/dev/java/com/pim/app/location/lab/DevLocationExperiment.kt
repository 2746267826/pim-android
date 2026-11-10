package com.pim.app.location.lab

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pim.app.location.service.ForegroundLocationService
import com.pim.app.location.service.LocalCollectionExperiment
import com.pim.locationtest.DemoRuntime
import com.pim.locationtest.LocationSample
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevLocationExperiment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LocationLabStore
) : LocalCollectionExperiment {
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var sessionToken = 0L
    @Volatile private var stopping = false
    private var restartAfterStop: Pair<Service, Int>? = null
    private var handler: Handler? = null
    private var sensors: JointSensorCollector? = null
    private var callback: LocationCallback? = null
    private var receiver: BroadcastReceiver? = null
    private var service: Service? = null
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    override fun onStartCommand(service: Service, intent: Intent?, startId: Int): Int? {
        val action = intent?.action
        this.service = service
        // This isolated variant never routes a normal PIM command into the
        // acquisition/upload pipeline, even on a null sticky restart without a session.
        if (action !in setOf(ACTION_START, ACTION_STOP) && !(action == null && store.requested)) {
            if (worker != null) return Service.START_STICKY
            service.stopSelf(startId)
            return Service.START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            restartAfterStop = null
            if (stopping) return Service.START_NOT_STICKY
            shutdown("用户停止", stopRequested = true, stopService = true)
            return Service.START_NOT_STICKY
        }
        if (stopping) {
            restartAfterStop = service to startId
            return Service.START_STICKY
        }
        if (worker != null) return Service.START_STICKY
        sessionToken++
        val now = SystemClock.elapsedRealtime()
        try {
            if (!store.requested) store.begin(now)
            if (!store.sameBoot || now < store.origin || now - store.origin >= LocationLabStore.MAX_SESSION_MILLIS) {
                shutdown("上次联测已超过一小时或设备已重启，请开始新一轮", stopRequested = true, stopService = true)
                return Service.START_NOT_STICKY
            }
            val prerequisite = prerequisiteIssue()
            if (prerequisite != null) {
                shutdown(prerequisite, stopRequested = true, stopService = true)
                return Service.START_NOT_STICKY
            }
            service.startForeground(NOTIFICATION_ID, notification())
            val thread = HandlerThread("PimJointLocationLab").also { it.start() }
            worker = thread
            val workHandler = Handler(thread.looper).also { handler = it }
            workHandler.post {
                try {
                    store.attach(SystemClock.elapsedRealtime())
                    DemoRuntime.begin("实时定位（PIM Dev 本地联测）", true)
                    sensors = JointSensorCollector(context, workHandler, SystemClock.elapsedRealtime()).also { it.start() }
                    registerScreenReceiver(workHandler)
                    startLocations(thread)
                    workHandler.post(tick)
                } catch (error: Exception) { fail(error) }
            }
            return Service.START_STICKY
        } catch (error: Exception) {
            fail(error)
            return Service.START_NOT_STICKY
        }
    }

    private fun prerequisiteIssue(): String? = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "精确定位权限未授予"
        !context.getSystemService(LocationManager::class.java).isLocationEnabled -> "系统定位未开启"
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS -> "Google Play Services 不可用"
        else -> null
    }

    @SuppressLint("MissingPermission")
    private fun startLocations(thread: HandlerThread) {
        val locationCallback = object : LocationCallback() {
            override fun onLocationAvailability(value: LocationAvailability) {
                if (worker !== thread) return
                DemoRuntime.setLocationAvailability(value.isLocationAvailable)
            }
            override fun onLocationResult(result: LocationResult) {
                if (worker !== thread) return
                try {
                    result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { location ->
                        val now = SystemClock.elapsedRealtime()
                        val sample = location.toSample()
                        // Ignore pre-segment cached fixes; never restore coordinates from disk.
                        DemoRuntime.accept(sample)
                        record(now, "location", sample.elapsedRealtimeMillis)
                    }
                } catch (error: Exception) { fail(error) }
            }
        }
        callback = locationCallback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_500L)
            .setMinUpdateIntervalMillis(1_000L).setMaxUpdateAgeMillis(0).build()
        client.requestLocationUpdates(request, locationCallback, thread.looper)
            .addOnFailureListener { if (worker === thread) fail(it) }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                if (!store.requested || now - store.origin >= LocationLabStore.MAX_SESSION_MILLIS) {
                    shutdown("联测已达到一小时上限", stopRequested = true, stopService = true)
                    return
                }
                DemoRuntime.advance(now)
                record(now, "heartbeat")
                handler?.postDelayed(this, 5_000)
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun record(now: Long, kind: String, fixAt: Long? = null) {
        val collector = sensors ?: return
        store.record(now, kind, collector.snapshot(now), collector.status(), fixAt)
    }

    private fun registerScreenReceiver(workHandler: Handler) {
        val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try { store.event(SystemClock.elapsedRealtime(), intent?.action.orEmpty()) }
                catch (error: Exception) { fail(error) }
            }
        }
        receiver = screenReceiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, screenReceiver, filter, null, workHandler, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun fail(error: Exception) {
        val message = "联测失败：${error.javaClass.simpleName}"
        val sourceWorker = worker
        val sourceToken = sessionToken
        android.os.Handler(context.mainLooper).post {
            if (worker !== sourceWorker || sessionToken != sourceToken) return@post
            DemoRuntime.setIssue(message)
            shutdown(message, stopRequested = true, stopService = true)
        }
    }

    private fun shutdown(reason: String, stopRequested: Boolean, stopService: Boolean = false) {
        stopping = true
        val host = service
        val workHandler = handler
        if (workHandler != null && Looper.myLooper() !== workHandler.looper) {
            workHandler.post { shutdownOnWorker(host, reason, stopRequested, stopService) }
        } else shutdownOnWorker(host, reason, stopRequested, stopService)
    }

    private fun shutdownOnWorker(host: Service?, reason: String, stopRequested: Boolean, stopService: Boolean) {
        val closedToken = ++sessionToken
        // Cancel registrations before retiring their callback looper.
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        sensors?.stop()
        sensors = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        worker?.quitSafely()
        worker = null
        DemoRuntime.setRunning(false)
        runCatching {
            val wasAttached = store.attached
            if (stopRequested && store.requested) store.stop(SystemClock.elapsedRealtime(), reason)
            if (wasAttached) store.detach(SystemClock.elapsedRealtime())
        }.onFailure { android.util.Log.e("PimLocationLab", "Cannot persist session stop", it) }
        android.os.Handler(context.mainLooper).post {
            if (sessionToken != closedToken) return@post
            host?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopping = false
            val restart = restartAfterStop
            restartAfterStop = null
            if (restart != null) {
                onStartCommand(restart.first, Intent(ACTION_START), restart.second)
            } else if (stopService) host?.stopSelf()
        }
    }

    override fun onDestroy(service: Service) {
        shutdown("服务被系统销毁", stopRequested = false)
        this.service = null
    }

    private fun notification(): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "本地定位联测", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, LocationLabActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, ForegroundLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PIM Dev 静止联测运行中").setContentText("同步记录定位与惯性传感器 · 仅本机保存")
            .setContentIntent(open).addAction(0, "停止联测", stop).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun Location.toSample() = LocationSample(
        elapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000L, latitude = latitude, longitude = longitude,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        speedAccuracyMetersPerSecond = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        provider = provider ?: "unknown", locationTimeMillis = time, hasAltitude = hasAltitude(),
        altitudeMeters = if (hasAltitude()) altitude else null,
        verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
        hasBearing = hasBearing(), bearingDegrees = if (hasBearing()) bearing else null,
        bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
        isMock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider
    )

    companion object {
        const val ACTION_START = "com.pim.app.dev.locationlab.START"
        const val ACTION_STOP = "com.pim.app.dev.locationlab.STOP"
        private const val CHANNEL_ID = "pim_local_location_lab"
        private const val NOTIFICATION_ID = 7390
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DevLocationExperimentModule {
    @Binds @IntoSet abstract fun experiment(implementation: DevLocationExperiment): LocalCollectionExperiment
}

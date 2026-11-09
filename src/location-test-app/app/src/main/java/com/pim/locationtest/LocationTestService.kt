package com.pim.locationtest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationTestService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null

    private val tick = object : Runnable {
        override fun run() {
            DemoRuntime.advance(SystemClock.elapsedRealtime())
            updateNotification()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("正在等待定位"))
        if (callback == null) startLocationUpdates()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            DemoRuntime.setIssue("定位权限未授予")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
            .build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationAvailability(availability: LocationAvailability) {
                DemoRuntime.setLocationAvailability(availability.isLocationAvailable)
            }

            override fun onLocationResult(result: LocationResult) {
                result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { location ->
                    DemoRuntime.accept(location.toSample())
                }
                updateNotification()
            }
        }
        callback = locationCallback
        try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener { error -> DemoRuntime.setIssue(error.message ?: "定位请求失败") }
        } catch (error: SecurityException) {
            DemoRuntime.setIssue(error.message ?: "定位权限未授予")
            stopSelf()
        }
    }

    private fun Location.toSample(): LocationSample = LocationSample(
        elapsedRealtimeMillis = if (elapsedRealtimeNanos > 0L) elapsedRealtimeNanos / 1_000_000L else SystemClock.elapsedRealtime(),
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        speedAccuracyMetersPerSecond = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
            speedAccuracyMetersPerSecond
        } else {
            null
        },
        provider = provider ?: "unknown",
        locationTimeMillis = time,
        hasAltitude = hasAltitude(),
        altitudeMeters = altitude.takeIf { hasAltitude() },
        verticalAccuracyMeters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()) {
            verticalAccuracyMeters
        } else {
            null
        },
        hasBearing = hasBearing(),
        bearingDegrees = bearing.takeIf { hasBearing() },
        bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) {
            bearingAccuracyDegrees
        } else {
            null
        },
        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider,
        elapsedRealtimeUncertaintyMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasElapsedRealtimeUncertaintyNanos()
        ) {
            elapsedRealtimeUncertaintyNanos / 1_000_000.0
        } else {
            null
        }
    )

    private fun updateNotification() {
        val snapshot = DemoRuntime.snapshot()
        val speed = snapshot.latest?.sample?.speedMetersPerSecond?.times(3.6f)
            ?.let { "%.1f km/h".format(java.util.Locale.US, it) } ?: "速度未知"
        val state = if (snapshot.proposedMode == ProposedMode.Active) "候选高速档" else "采样分析中"
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("$state · $speed"))
    }

    private fun notification(content: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定位算法实测", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("定位高速判定对照")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        DemoRuntime.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.pim.locationtest.START"
        const val ACTION_STOP = "com.pim.locationtest.STOP"
        private const val CHANNEL_ID = "location-test"
        private const val NOTIFICATION_ID = 7304
        private const val FIX_INTERVAL_MILLIS = 2_500L
        private const val MIN_UPDATE_INTERVAL_MILLIS = 1_000L
    }
}

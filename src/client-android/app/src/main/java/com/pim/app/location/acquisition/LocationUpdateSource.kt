package com.pim.app.location.acquisition

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.pim.app.location.LocationSnapshot
import com.pim.app.mobile.logs.StructuredLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class LocationUpdateRequest(
    val priority: Int,
    val durationMillis: Long,
    val intervalMillis: Long = 1_000L,
    val minUpdateIntervalMillis: Long = 800L
)

sealed interface LocationUpdateEvent {
    data class Candidate(val location: LocationSnapshot) : LocationUpdateEvent
    data class Availability(val available: Boolean) : LocationUpdateEvent
}

interface LocationUpdateSource {
    fun updates(request: LocationUpdateRequest): Flow<LocationUpdateEvent>
}

@Singleton
class FusedLocationUpdateSource @Inject constructor(
    @ApplicationContext context: Context,
    private val logs: StructuredLogRepository
) : LocationUpdateSource {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val deduplicator = LocationCallbackDeduplicator()

    @SuppressLint("MissingPermission")
    override fun updates(request: LocationUpdateRequest): Flow<LocationUpdateEvent> = callbackFlow {
        val traceScope = this
        val builder = LocationRequest.Builder(request.priority, request.intervalMillis)
            .setMinUpdateIntervalMillis(request.minUpdateIntervalMillis)
        if (request.durationMillis > 0L) {
            builder.setDurationMillis(request.durationMillis)
        }
        // AC-5.6 提醒：冲刺走的是**独立**注册（LocationSprintController），
        // 它有自己的 interval/duration，绝不通过改写主流注册来实现 —— 那会把下一拍
        // 从「改回时刻」重新计时，把周期锚点拖长。
        val locationRequest = builder.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val dedupResult = deduplicator.filterForDelivery(
                    result.locations.map { it.toSnapshot() }
                )
                dedupResult.delivered.forEach { snapshot ->
                    trySend(LocationUpdateEvent.Candidate(snapshot))
                }
                if (dedupResult.suppressions.isNotEmpty()) {
                    traceScope.traceSuppressions(dedupResult)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                trySend(LocationUpdateEvent.Availability(availability.isLocationAvailable))
            }
        }

        val task = fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )

        task.addOnFailureListener { exception ->
            close(exception)
        }

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.traceSuppressions(
        result: LocationCallbackDedupResult
    ) {
        val reasonCounts = result.suppressions
            .groupingBy { it.reason.code }
            .eachCount()
            .entries
            .joinToString(separator = ",") { (reason, count) -> "$reason=$count" }
        val suppressedProviders = result.suppressions
            .map { it.suppressed.provider.lowercase() }
            .distinct()
            .sorted()
            .joinToString(separator = ",")
        val retainedProviders = result.suppressions
            .map { it.retained.provider.lowercase() }
            .distinct()
            .sorted()
            .joinToString(separator = ",")
        launch {
            runCatching {
                logs.info(
                    operation = "location-callback-dedup",
                    message = "Suppressed duplicate platform location callbacks",
                    details = mapOf(
                        "suppressedThisResult" to result.suppressions.size,
                        "cumulativeSuppressedCount" to result.cumulativeSuppressedCount,
                        "reasonCounts" to reasonCounts,
                        "suppressedProviders" to suppressedProviders,
                        "retainedProviders" to retainedProviders
                    )
                )
            }
        }
    }

    private companion object {
        private fun Location.toSnapshot(): LocationSnapshot = LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
            provider = provider ?: "unknown",
            source = "realtime",
            altitudeMeters = if (hasAltitude()) altitude else null,
            speedMetersPerSecond = if (hasSpeed()) speed else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            timeMillis = time,
            elapsedRealtimeNanos = elapsedRealtimeNanos
        )
    }
}

package com.pim.app.mobile.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pim.core.contract.DaemonHeartbeatRequest
import com.pim.core.contract.DaemonSourceState
import com.pim.core.network.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MobileHeartbeatReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService
) {
    suspend fun report(
        deviceId: String,
        serverUrl: String,
        usagePermissionGranted: Boolean,
        state: MobileSyncState
    ) {
        val statusJson = JSONObject()
            .put("brand", Build.BRAND ?: "")
            .put("manufacturer", Build.MANUFACTURER ?: "")
            .put("model", Build.MODEL ?: "")
            .put("androidVersion", Build.VERSION.RELEASE ?: "")
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("appVersion", appVersionName(context))
            .put("usagePermissionGranted", usagePermissionGranted)
            .put("preciseLocationPermissionGranted", hasPreciseLocationPermission())
            .put("lastUsageSyncResult", state.phase)
            .put("lastGapCheckWindowCount", state.gapWindowCount)
            .put("pendingQueueCount", state.pendingQueueCount)
            .put("acceptedCount", state.acceptedCount)
            .put("skippedCount", state.skippedCount)
            .put("rejectedCount", state.rejectedCount)
            .put("failedCount", state.failedCount)
            .put("lastError", state.lastError ?: JSONObject.NULL)
            .put("locationCapability", locationCapabilitySummary())
            .toString()

        api.sendHeartbeat(
            DaemonHeartbeatRequest(
                deviceId = deviceId,
                daemonKind = "android",
                version = appVersionName(context),
                serverUrl = serverUrl,
                lastSuccessfulUploadAt = state.lastSuccessfulUploadAt,
                lastAttemptedUploadAt = state.lastAttemptedUploadAt,
                lastError = state.lastError,
                uploadQueueCount = state.pendingQueueCount,
                // 语义保持不变：当前没有可信的采集状态来源，仍上报契约内的 UNKNOWN
                // （不臆造 Available/Unavailable/Paused —— 见审计 F-B-06 观察）。
                activityWatchState = DaemonSourceState.UNKNOWN,
                keyStatsState = DaemonSourceState.UNKNOWN,
                collectionPaused = !usagePermissionGranted,
                statusJson = statusJson
            )
        )
    }

    private fun hasPreciseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationCapabilitySummary(): String {
        return if (hasPreciseLocationPermission()) {
            "fine-location-granted"
        } else {
            "fine-location-missing"
        }
    }
}

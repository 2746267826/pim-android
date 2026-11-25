package com.pim.app.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.mobile.usage.UsageAccessState
import com.pim.app.status.CollectionPermissionKind
import com.pim.app.status.PermissionStatusSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionStatusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageAccessChecker: UsageAccessChecker
) {
    fun snapshot(): PermissionStatusSnapshot {
        val failedChecks = mutableSetOf<CollectionPermissionKind>()
        val restricted = mutableMapOf<CollectionPermissionKind, String>()

        fun checked(kind: CollectionPermissionKind, block: () -> Boolean?): Boolean {
            val result = try {
                block()
            } catch (_: Exception) {
                null
            }
            if (result == null) {
                failedChecks += kind
                return false
            }
            return result
        }

        val preciseLocationGranted = checked(CollectionPermissionKind.PRECISE_LOCATION) {
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!preciseLocationGranted) {
            val coarseLocationGranted = checked(CollectionPermissionKind.PRECISE_LOCATION) {
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (coarseLocationGranted) {
                restricted[CollectionPermissionKind.PRECISE_LOCATION] = "仅允许大致位置"
            }
        }

        val notificationGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            checked(CollectionPermissionKind.NOTIFICATION) {
                isGranted(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val notificationsEnabled = if (notificationGranted) {
            checked(CollectionPermissionKind.NOTIFICATION) {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        } else {
            true
        }
        if (notificationGranted && !notificationsEnabled) {
            restricted[CollectionPermissionKind.NOTIFICATION] = "系统通知已关闭"
        }

        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checked(CollectionPermissionKind.BACKGROUND_LOCATION) {
                isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            preciseLocationGranted
        }

        val usageAccessState = try {
            usageAccessChecker.checkAccess()
        } catch (_: Exception) {
            UsageAccessState.CheckFailed
        }
        if (usageAccessState == UsageAccessState.CheckFailed) {
            failedChecks += CollectionPermissionKind.USAGE_ACCESS
        }

        val activityRecognitionGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            true
        } else {
            checked(CollectionPermissionKind.ACTIVITY_RECOGNITION) {
                isGranted(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        val batteryOptimizationGranted = checked(CollectionPermissionKind.BATTERY_OPTIMIZATION) {
            batteryOptimizationExemption()
        }

        return PermissionStatusSnapshot(
            notificationGranted = notificationGranted,
            preciseLocationGranted = preciseLocationGranted,
            backgroundLocationGranted = backgroundLocationGranted,
            usageAccessGranted = usageAccessState == UsageAccessState.Granted,
            activityRecognitionGranted = activityRecognitionGranted,
            batteryOptimizationGranted = batteryOptimizationGranted,
            // 工单 04 E3.3：日程静音需要的勿扰访问权限（只能在系统设置里授予）
            doNotDisturbAccessGranted = hasDoNotDisturbAccess(),
            restrictedPermissions = restricted,
            failedPermissionChecks = failedChecks,
        )
    }

    private fun batteryOptimizationExemption(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasDoNotDisturbAccess(): Boolean {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.isNotificationPolicyAccessGranted ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

package com.pim.app.status

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object StatusPermissionNavigator {
    fun open(
        context: Context,
        issue: StatusIssue,
        access: CollectionPermissionAccess? = null,
    ) = tryStartActivity(context, intentFor(context, issue, access))

    fun open(
        context: Context,
        issueCode: String,
        access: CollectionPermissionAccess? = null,
    ) = tryStartActivity(context, intentFor(context, issueCode, access))

    private fun tryStartActivity(context: Context, intent: Intent): Boolean {
        if (tryOpen(context, intent)) return true

        fallbackIntents(context, intent).forEach { fallback ->
            if (tryOpen(context, fallback)) return true
        }
        return false
    }

    private fun fallbackIntents(context: Context, primary: Intent): List<Intent> = buildList {
        if (primary.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        add(appDetailsIntent(context))
    }.filter { fallback ->
        fallback.action != primary.action || fallback.data != primary.data
    }

    private fun tryOpen(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    fun intentFor(
        context: Context,
        issue: StatusIssue,
        access: CollectionPermissionAccess? = null,
    ): Intent = intentFor(context, issue.code, access)

    fun intentFor(
        context: Context,
        issueCode: String,
        access: CollectionPermissionAccess? = null,
    ): Intent = when (issueCode) {
        "usage-access-missing" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // 工单 04 E3.3：勿扰访问权限只能在这个系统页面里授予
        "dnd-access-missing" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        "notification-permission-missing" -> notificationSettingsIntent(context)
        "battery-optimization-missing" -> if (access == CollectionPermissionAccess.GRANTED) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        "foreground-location-missing",
        "background-location-missing",
        "activity-recognition-missing" -> appDetailsIntent(context)
        else -> appDetailsIntent(context)
    }

    private fun notificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsIntent(context)
        }
    }

    private fun appDetailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }
}

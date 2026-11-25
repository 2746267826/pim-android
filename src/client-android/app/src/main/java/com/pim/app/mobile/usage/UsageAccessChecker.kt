package com.pim.app.mobile.usage

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class UsageAccessState {
    Granted,
    Denied,
    CheckFailed,
}

@Singleton
class UsageAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasUsageAccess(): Boolean = checkAccess() == UsageAccessState.Granted

    @Suppress("DEPRECATION")
    fun checkAccess(): UsageAccessState {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return UsageAccessState.CheckFailed
            val mode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            if (mode == AppOpsManager.MODE_ALLOWED) {
                UsageAccessState.Granted
            } else {
                UsageAccessState.Denied
            }
        } catch (_: Exception) {
            UsageAccessState.CheckFailed
        }
    }
}

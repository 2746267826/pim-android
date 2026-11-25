package com.pim.app.mobile.usage

import android.app.usage.UsageStatsManager
import android.content.Context
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
    fun hasUsageAccess(
        nowUtc: Long = System.currentTimeMillis(),
        lookbackMs: Long = DEFAULT_LOOKBACK_MS
    ): Boolean = checkAccess(nowUtc, lookbackMs) == UsageAccessState.Granted

    fun checkAccess(
        nowUtc: Long = System.currentTimeMillis(),
        lookbackMs: Long = DEFAULT_LOOKBACK_MS
    ): UsageAccessState {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return UsageAccessState.CheckFailed
        val beginUtc = (nowUtc - lookbackMs).coerceAtLeast(0L)

        return try {
            val hasRecentUsage = usageStatsManager
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginUtc, nowUtc)
                .orEmpty()
                .isNotEmpty()
            if (hasRecentUsage) UsageAccessState.Granted else UsageAccessState.Denied
        } catch (_: SecurityException) {
            UsageAccessState.CheckFailed
        } catch (_: Exception) {
            UsageAccessState.CheckFailed
        }
    }

    companion object {
        private const val DEFAULT_LOOKBACK_MS = 24 * 60 * 60 * 1000L
    }
}

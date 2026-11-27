package com.pim.app.ui.today

import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.status.StatusCenterState
import com.pim.app.status.unreadyCollectionPermissionCount

data class TodayLocalStatusSummary(
    val pendingCount: Int = 0,
    val checking: Boolean = false,
) {
    val hasPendingItems: Boolean get() = pendingCount > 0

    val label: String
        get() = when {
            hasPendingItems -> "本机状态 · $pendingCount 项待处理"
            checking -> "本机状态 · 检查中"
            else -> "本机状态 · 采集已就绪"
        }

    companion object {
        fun from(
            state: StatusCenterState,
            keepAliveAlert: String?,
        ): TodayLocalStatusSummary = TodayLocalStatusSummary(
            pendingCount = if (state.isLoading) {
                0
            } else {
                state.snapshot.permissions.unreadyCollectionPermissionCount()
            } + keepAliveAlertCount(keepAliveAlert),
            checking = state.isLoading,
        )

        fun from(
            state: TodayUiState,
            keepAliveAlert: String?,
        ): TodayLocalStatusSummary = TodayLocalStatusSummary(
            pendingCount = state.missingLocalPermissionCount + keepAliveAlertCount(keepAliveAlert),
            checking = state.localPermissionsChecking,
        )
    }
}

internal fun todayLocalStatusSummary(
    permissions: PermissionStatusSnapshot,
    keepAliveAlert: String?,
    permissionsReady: Boolean,
): TodayLocalStatusSummary = TodayLocalStatusSummary(
    pendingCount = (if (permissionsReady) permissions.unreadyCollectionPermissionCount() else 0) +
        keepAliveAlertCount(keepAliveAlert),
    checking = !permissionsReady,
)

private fun keepAliveAlertCount(keepAliveAlert: String?): Int =
    if (keepAliveAlert.isNullOrBlank()) 0 else 1

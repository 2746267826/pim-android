package com.pim.app.ui.today

import com.pim.app.status.ApiConnectionSnapshot
import com.pim.app.status.AuthStatusSnapshot
import com.pim.app.status.CollectionPermissionKind
import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.status.StatusCenterSnapshot
import com.pim.app.status.StatusCenterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TodayLocalStatusSummaryTest {

    @Test
    fun `all ready permissions show a healthy summary without a pending count`() {
        val summary = todayLocalStatusSummary(
            permissions = allGrantedPermissions(),
            keepAliveAlert = null,
            permissionsReady = true,
        )

        assertEquals(0, summary.pendingCount)
        assertFalse(summary.hasPendingItems)
        assertEquals("本机状态 · 采集已就绪", summary.label)
    }

    @Test
    fun `missing restricted and failed checks are all counted as pending`() {
        val permissions = allGrantedPermissions().copy(
            notificationGranted = false,
            preciseLocationGranted = false,
            restrictedPermissions = mapOf(
                CollectionPermissionKind.PRECISE_LOCATION to "仅允许大致位置",
            ),
            failedPermissionChecks = setOf(CollectionPermissionKind.USAGE_ACCESS),
            usageAccessGranted = false,
        )

        val summary = todayLocalStatusSummary(
            permissions = permissions,
            keepAliveAlert = null,
            permissionsReady = true,
        )

        assertEquals(3, summary.pendingCount)
        assertEquals("本机状态 · 3 项待处理", summary.label)
    }

    @Test
    fun `keepalive alert is included once alongside permission gaps`() {
        val summary = todayLocalStatusSummary(
            permissions = allGrantedPermissions().copy(backgroundLocationGranted = false),
            keepAliveAlert = "系统清空了保活闹钟",
            permissionsReady = true,
        )

        assertEquals(2, summary.pendingCount)
        assertEquals("本机状态 · 2 项待处理", summary.label)
    }

    @Test
    fun `loading state is explicit and does not claim permissions are ready`() {
        val summary = TodayLocalStatusSummary.from(
            StatusCenterState.empty(),
            keepAliveAlert = null,
        )

        assertEquals(0, summary.pendingCount)
        assertEquals("本机状态 · 检查中", summary.label)
    }

    @Test
    fun `default today state does not claim permissions are ready on the first frame`() {
        val summary = TodayLocalStatusSummary.from(TodayUiState(), keepAliveAlert = null)

        assertEquals(0, summary.pendingCount)
        assertEquals("本机状态 · 检查中", summary.label)
    }

    @Test
    fun `known keepalive alert stays visible while permission checks are loading`() {
        val summary = TodayLocalStatusSummary.from(
            StatusCenterState.empty(),
            keepAliveAlert = "连续多次未能拉起采集服务",
        )

        assertEquals(1, summary.pendingCount)
        assertEquals("本机状态 · 1 项待处理", summary.label)
    }

    @Test
    fun `today ui state carries loading and pending counts to the summary`() {
        val summary = TodayLocalStatusSummary.from(
            TodayUiState(
                missingLocalPermissionCount = 2,
                localPermissionsChecking = false,
            ),
            keepAliveAlert = "系统清空了保活闹钟",
        )

        assertEquals(3, summary.pendingCount)
        assertEquals("本机状态 · 3 项待处理", summary.label)
    }

    @Test
    fun `local permission health does not depend on login or server identity`() {
        val permissions = allGrantedPermissions().copy(
            notificationGranted = false,
            batteryOptimizationGranted = false,
        )
        val snapshot = StatusCenterSnapshot(
            permissions = permissions,
            api = ApiConnectionSnapshot(
                address = "https://old.example/api/v1/",
                isValid = false,
                reasonCode = "invalid",
                warnings = emptySet(),
            ),
            auth = AuthStatusSnapshot(hasAccessToken = false, isExpired = false),
            service = StatusCenterState.empty().snapshot.service,
            tracking = StatusCenterState.empty().snapshot.tracking,
            queues = StatusCenterState.empty().snapshot.queues,
            diagnostics = StatusCenterState.empty().snapshot.diagnostics,
        )
        val state = StatusCenterState(
            snapshot = snapshot,
            issues = emptyList(),
            isLoading = false,
        )

        val summary = TodayLocalStatusSummary.from(state, keepAliveAlert = null)

        assertEquals(2, summary.pendingCount)
        assertEquals("本机状态 · 2 项待处理", summary.label)
        assertFalse(summary.label.contains("old.example"))
        assertFalse(summary.label.contains("token", ignoreCase = true))
    }

    private fun allGrantedPermissions() = PermissionStatusSnapshot(
        notificationGranted = true,
        preciseLocationGranted = true,
        backgroundLocationGranted = true,
        usageAccessGranted = true,
        activityRecognitionGranted = true,
        batteryOptimizationGranted = true,
        doNotDisturbAccessGranted = true,
    )
}

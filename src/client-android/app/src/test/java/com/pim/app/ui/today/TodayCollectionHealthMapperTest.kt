package com.pim.app.ui.today

import com.pim.app.status.ApiConnectionSnapshot
import com.pim.app.status.AuthStatusSnapshot
import com.pim.app.status.CollectionPermissionKind
import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.status.StatusCenterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TodayCollectionHealthMapperTest {

    @Test
    fun `mapper marks permissions as loading until the status snapshot is ready`() {
        val loading = TodayStatusMapper.fromStatus(
            baseState(isLoading = true),
            TodayPageReport.EMPTY,
        )
        val ready = TodayStatusMapper.fromStatus(
            baseState(isLoading = false),
            TodayPageReport.EMPTY,
        )

        assertEquals(true, loading.localPermissionsChecking)
        assertEquals(0, loading.missingLocalPermissionCount)
        assertEquals(false, ready.localPermissionsChecking)
        assertEquals(6, ready.missingLocalPermissionCount)
    }

    @Test
    fun `restricted and failed checks are counted as pending without exposing reason codes`() {
        val state = baseState(isLoading = false).copy(
            snapshot = baseState(isLoading = false).snapshot.copy(
                permissions = allGranted().copy(
                    preciseLocationGranted = false,
                    restrictedPermissions = mapOf(
                        CollectionPermissionKind.PRECISE_LOCATION to "仅允许大致位置",
                    ),
                    failedPermissionChecks = setOf(CollectionPermissionKind.BATTERY_OPTIMIZATION),
                    batteryOptimizationGranted = false,
                ),
            ),
        )

        val ui = TodayStatusMapper.fromStatus(state, TodayPageReport.EMPTY)
        val summary = TodayLocalStatusSummary.from(ui, keepAliveAlert = null)

        assertEquals(2, ui.missingLocalPermissionCount)
        assertEquals("本机状态 · 2 项待处理", summary.label)
        assertFalse(summary.label.contains("battery-optimization-missing"))
        assertFalse(summary.label.contains("ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `not logged in still shows local permission gaps`() {
        val state = baseState(isLoading = false).copy(
            snapshot = baseState(isLoading = false).snapshot.copy(
                permissions = allGranted().copy(notificationGranted = false),
                auth = AuthStatusSnapshot(hasAccessToken = false, isExpired = false),
                api = ApiConnectionSnapshot("", isValid = false, reasonCode = "missing", warnings = emptySet()),
            ),
        )

        val ui = TodayStatusMapper.fromStatus(state, TodayPageReport.EMPTY)
        val summary = TodayLocalStatusSummary.from(ui, keepAliveAlert = null)

        assertEquals(1, ui.missingLocalPermissionCount)
        assertEquals("本机状态 · 1 项待处理", summary.label)
    }

    @Test
    fun `server switch drops stale page errors but keeps local permission health`() {
        val oldServer = "https://old.example/api/v1/"
        val newServer = "https://new.example/api/v1/"
        val staleReport = TodayPageReportEnvelope(
            serverIdentity = oldServer,
            report = TodayPageReport(error = "old access-secret"),
        )
        val resolved = resolveReportFromEnvelope(staleReport, newServer)
        val state = baseState(isLoading = false).copy(
            snapshot = baseState(isLoading = false).snapshot.copy(
                permissions = allGranted().copy(backgroundLocationGranted = false),
            ),
        )

        val ui = TodayStatusMapper.fromStatus(
            state = state,
            pageReport = resolved,
            currentServerIdentity = newServer,
        )
        val summary = TodayLocalStatusSummary.from(ui, keepAliveAlert = null)

        assertEquals(1, ui.missingLocalPermissionCount)
        assertFalse(ui.statusTitle.contains("old access-secret"))
        assertEquals("本机状态 · 1 项待处理", summary.label)
    }

    private fun baseState(isLoading: Boolean): StatusCenterState {
        val empty = StatusCenterState.empty()
        return StatusCenterState(
            snapshot = empty.snapshot,
            issues = emptyList(),
            isLoading = isLoading,
        )
    }

    private fun allGranted() = PermissionStatusSnapshot(
        notificationGranted = true,
        preciseLocationGranted = true,
        backgroundLocationGranted = true,
        usageAccessGranted = true,
        activityRecognitionGranted = true,
        batteryOptimizationGranted = true,
        doNotDisturbAccessGranted = true,
    )
}

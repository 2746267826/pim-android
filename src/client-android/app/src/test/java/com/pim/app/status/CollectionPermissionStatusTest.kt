package com.pim.app.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionPermissionStatusTest {

    @Test
    fun `collection health exposes exactly the six capture permissions`() {
        val states = allGranted().collectionPermissions()

        assertEquals(6, states.size)
        assertEquals(
            listOf(
                CollectionPermissionKind.NOTIFICATION,
                CollectionPermissionKind.PRECISE_LOCATION,
                CollectionPermissionKind.BACKGROUND_LOCATION,
                CollectionPermissionKind.USAGE_ACCESS,
                CollectionPermissionKind.ACTIVITY_RECOGNITION,
                CollectionPermissionKind.BATTERY_OPTIMIZATION,
            ),
            states.map { it.kind },
        )
        assertFalse(states.any { it.kind.issueCode == "dnd-access-missing" })
        assertTrue(states.all { it.access == CollectionPermissionAccess.GRANTED })
        assertEquals(0, allGranted().unreadyCollectionPermissionCount())
    }

    @Test
    fun `missing permissions are reported as unready with actionable Chinese labels`() {
        val snapshot = allGranted().copy(
            notificationGranted = false,
            backgroundLocationGranted = false,
        )

        val notification = snapshot.collectionPermission(CollectionPermissionKind.NOTIFICATION)
        val background = snapshot.collectionPermission(CollectionPermissionKind.BACKGROUND_LOCATION)

        assertEquals(CollectionPermissionAccess.MISSING, notification.access)
        assertEquals("未就绪", notification.statusText)
        assertEquals("去授权", notification.actionLabel)
        assertEquals(CollectionPermissionAccess.MISSING, background.access)
        assertEquals("去授权", background.actionLabel)
        assertEquals(2, snapshot.unreadyCollectionPermissionCount())
    }

    @Test
    fun `restricted permission keeps its reason and remains actionable`() {
        val snapshot = allGranted().copy(
            preciseLocationGranted = false,
            restrictedPermissions = mapOf(
                CollectionPermissionKind.PRECISE_LOCATION to "仅允许大致位置",
            ),
        )

        val precise = snapshot.collectionPermission(CollectionPermissionKind.PRECISE_LOCATION)

        assertEquals(CollectionPermissionAccess.RESTRICTED, precise.access)
        assertEquals("受限：仅允许大致位置", precise.statusText)
        assertEquals("去授权", precise.actionLabel)
        assertEquals(1, snapshot.unreadyCollectionPermissionCount())
    }

    @Test
    fun `check failure is distinct from denial and offers a settings route`() {
        val snapshot = allGranted().copy(
            usageAccessGranted = false,
            failedPermissionChecks = setOf(CollectionPermissionKind.USAGE_ACCESS),
        )

        val usage = snapshot.collectionPermission(CollectionPermissionKind.USAGE_ACCESS)

        assertEquals(CollectionPermissionAccess.CHECK_FAILED, usage.access)
        assertEquals("检查失败", usage.statusText)
        assertEquals("去设置", usage.actionLabel)
        assertEquals(1, snapshot.unreadyCollectionPermissionCount())
    }

    @Test
    fun `check failure takes precedence over a stale granted boolean`() {
        val snapshot = allGranted().copy(
            failedPermissionChecks = setOf(CollectionPermissionKind.NOTIFICATION),
        )

        assertEquals(
            CollectionPermissionAccess.CHECK_FAILED,
            snapshot.collectionPermission(CollectionPermissionKind.NOTIFICATION).access,
        )
    }

    @Test
    fun `granted permissions offer a read-only settings entry`() {
        val item = allGranted().collectionPermission(CollectionPermissionKind.ACTIVITY_RECOGNITION)

        assertEquals("已就绪", item.statusText)
        assertEquals("查看设置", item.actionLabel)
    }

    @Test
    fun `permission issue codes stay aligned with existing navigation routes`() {
        assertEquals(
            listOf(
                "notification-permission-missing",
                "foreground-location-missing",
                "background-location-missing",
                "usage-access-missing",
                "activity-recognition-missing",
                "battery-optimization-missing",
            ),
            CollectionPermissionKind.entries.map { it.issueCode },
        )
        assertTrue(CollectionPermissionKind.NOTIFICATION.required)
        assertTrue(CollectionPermissionKind.PRECISE_LOCATION.required)
        assertTrue(CollectionPermissionKind.BACKGROUND_LOCATION.required)
        assertFalse(CollectionPermissionKind.USAGE_ACCESS.required)
        assertFalse(CollectionPermissionKind.ACTIVITY_RECOGNITION.required)
        assertFalse(CollectionPermissionKind.BATTERY_OPTIMIZATION.required)
    }

    @Test
    fun `status planner keeps restricted and failed checks distinct from denial`() {
        val base = StatusCenterState.empty().snapshot
        val snapshot = base.copy(
            permissions = allGranted().copy(
                preciseLocationGranted = false,
                restrictedPermissions = mapOf(
                    CollectionPermissionKind.PRECISE_LOCATION to "仅允许大致位置",
                ),
                usageAccessGranted = false,
                failedPermissionChecks = setOf(CollectionPermissionKind.USAGE_ACCESS),
            ),
            api = ApiConnectionSnapshot(
                address = "https://pim.example/api/v1/",
                isValid = true,
                reasonCode = null,
                warnings = emptySet(),
            ),
            auth = AuthStatusSnapshot(hasAccessToken = true, isExpired = false),
        )

        val issues = StatusIssuePlanner.plan(snapshot).associateBy { it.code }
        val location = issues.getValue("foreground-location-missing")
        val usage = issues.getValue("usage-access-missing")

        assertEquals("前台定位受限", location.title)
        assertTrue(location.message.contains("仅允许大致位置"))
        assertEquals("使用情况检查失败", usage.title)
        assertEquals("去设置", usage.actionLabel)
    }

    private fun PermissionStatusSnapshot.collectionPermission(
        kind: CollectionPermissionKind,
    ): CollectionPermissionState = collectionPermissions().single { it.kind == kind }

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

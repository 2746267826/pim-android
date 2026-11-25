package com.pim.app.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.status.CollectionPermissionAccess
import com.pim.app.status.CollectionPermissionKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class PermissionStatusRepositoryTest {
    @Test
    fun batteryOptimizationIsFalseWhenNotExempted() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repo = PermissionStatusRepository(context, UsageAccessChecker(context))
        val snapshot = repo.snapshot()

        assertFalse(snapshot.batteryOptimizationGranted)
    }

    @Test
    fun batteryOptimizationIsTrueWhenExempted() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true)
        val repo = PermissionStatusRepository(context, UsageAccessChecker(context))
        val snapshot = repo.snapshot()

        assertTrue(snapshot.batteryOptimizationGranted)
    }

    @Test
    fun preciseLocationIsRestrictedWhenOnlyCoarseIsGranted() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val repo = PermissionStatusRepository(context, UsageAccessChecker(context))

        val snapshot = repo.snapshot()

        assertFalse(snapshot.preciseLocationGranted)
        assertTrue(CollectionPermissionKind.PRECISE_LOCATION in snapshot.restrictedPermissions)
        assertTrue(
            snapshot.collectionPermissions()
                .single { it.kind == CollectionPermissionKind.PRECISE_LOCATION }
                .statusText
                .contains("仅允许大致位置")
        )
        assertTrue(CollectionPermissionKind.PRECISE_LOCATION in snapshot.unreadyKinds())
    }

    @Test
    fun permissionCheckFailuresAreReportedWithoutCrashing() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(base) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                    throw SecurityException("permission check restricted")
                }
                return super.checkPermission(permission, pid, uid)
            }
        }
        val repo = PermissionStatusRepository(context, UsageAccessChecker(context))

        val snapshot = repo.snapshot()

        assertTrue(CollectionPermissionKind.PRECISE_LOCATION in snapshot.failedPermissionChecks)
        assertTrue(
            snapshot.collectionPermissions()
                .single { it.kind == CollectionPermissionKind.PRECISE_LOCATION }
                .access == CollectionPermissionAccess.CHECK_FAILED
        )
    }

    @Test
    fun snapshotRefreshesAfterPermissionGrant() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repo = PermissionStatusRepository(context, UsageAccessChecker(context))
        assertFalse(repo.snapshot().preciseLocationGranted)

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertTrue(repo.snapshot().preciseLocationGranted)
    }

    private fun com.pim.app.status.PermissionStatusSnapshot.unreadyKinds(): Set<CollectionPermissionKind> =
        collectionPermissions()
            .filter { it.access != CollectionPermissionAccess.GRANTED }
            .map { it.kind }
            .toSet()
}

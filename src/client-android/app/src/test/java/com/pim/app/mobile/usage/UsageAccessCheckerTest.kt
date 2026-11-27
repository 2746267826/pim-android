package com.pim.app.mobile.usage

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class UsageAccessCheckerTest {
    @Test
    fun grantedAppOpDoesNotDependOnRecentUsageStats() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_ALLOWED,
        )

        assertEquals(UsageAccessState.Granted, UsageAccessChecker(context).checkAccess())
    }

    @Test
    fun deniedAppOpIsDenied() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_IGNORED,
        )

        assertEquals(UsageAccessState.Denied, UsageAccessChecker(context).checkAccess())
    }

    @Test
    fun defaultAppOpIsDenied() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_DEFAULT,
        )

        assertEquals(UsageAccessState.Denied, UsageAccessChecker(context).checkAccess())
    }

    @Test
    fun unavailableAppOpsServiceIsACheckFailure() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.APP_OPS_SERVICE) null else super.getSystemService(name)
        }

        assertEquals(UsageAccessState.CheckFailed, UsageAccessChecker(context).checkAccess())
    }
}

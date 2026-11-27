package com.pim.app.mobile.usage

import android.app.AppOpsManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppOpsManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = TestPimApp::class,
    shadows = [UsageAccessCheckerFailureTest.ThrowingAppOpsManagerShadow::class],
)
class UsageAccessCheckerFailureTest {
    @Test
    fun appOpsExceptionIsACheckFailure() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        assertEquals(UsageAccessState.CheckFailed, UsageAccessChecker(context).checkAccess())
    }

    @Implements(AppOpsManager::class)
    class ThrowingAppOpsManagerShadow : ShadowAppOpsManager() {
        @Implementation
        override fun checkOpNoThrow(op: String, uid: Int, packageName: String): Int {
            throw SecurityException("app ops restricted")
        }
    }
}

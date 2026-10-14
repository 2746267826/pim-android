package com.pim.app.mute

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工单 04（E3.2 / E3.3）的清单契约。
 *
 * 与 `ExactAlarmManifestTest` 同样的理由：必须对着**打包后的清单**断言 ——
 * 源码里写了不等于装出来的包里有（清单合并可能丢掉条目）。
 * 这一条直接对应工单里「PimDaemonService 从未注册、整个 daemon 包运行时不可达」那个坑：
 * 新加的接收器与权限必须真的在包里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class EventMuteManifestTest {

    private fun context(): Application = ApplicationProvider.getApplicationContext()

    private fun requestedPermissions(): List<String> {
        val info = context().packageManager.getPackageInfo(
            context().packageName,
            PackageManager.GET_PERMISSIONS
        )
        return (info.requestedPermissions ?: emptyArray()).toList()
    }

    @Test
    fun `清单声明 ACCESS_NOTIFICATION_POLICY（勿扰访问，E3_3 的前提）`() {
        assertTrue(
            "必须声明 android.permission.ACCESS_NOTIFICATION_POLICY，否则无法引导用户授予勿扰访问",
            requestedPermissions().contains("android.permission.ACCESS_NOTIFICATION_POLICY")
        )
    }

    @Test
    fun `日程静音接收器已在清单注册且不导出`() {
        val info = context().packageManager.getReceiverInfo(
            ComponentName(context(), EventMuteAlarmReceiver::class.java),
            0
        )
        assertFalse("闹钟广播只由系统派发，不得导出", info.exported)
    }

    @Test
    fun `日程静音的 action 与 keepalive 的叫醒 action 不同`() {
        assertTrue(EventMuteAlarmScheduler.ACTION_EVENT_MUTE.isNotBlank())
        assertTrue(
            "两套闹钟必须用不同 action，否则同一 PendingIntent 会互相覆盖",
            EventMuteAlarmScheduler.ACTION_EVENT_MUTE !=
                com.pim.app.keepalive.KeepAliveAlarmScheduler.ACTION_KEEPALIVE_ALARM
        )
    }
}

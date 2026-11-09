package com.pim.app.mute

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AndroidEventMuteDevice] 的 Robolectric 通路验证（Issue #72 / #25）。
 *
 * 现成 shadow 对铃声模式的写入与读回总是一致，因此测试验证权限门禁、
 * 铃声快照恢复，以及操作期间勿扰过滤器的保留。
 *
 * **无法覆盖（仅真机可验，NOT-VERIFIED）**：真实机型上系统调用「不抛异常但状态不生效」
 * 的场景（部分 ROM 静默失败）。
 * 媒体播放、音量键及 ROM 联动勿扰的行为同样需要设备验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidEventMuteDeviceTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var subject: AndroidEventMuteDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        subject = AndroidEventMuteDevice(context)
    }

    private fun arrangePreState() {
        // 进入前：振动 + 通知全放行（都不是本功能的静音档案）
        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test
    fun `未授权勿扰访问时restore返回Blocked且铃声与过滤器都不被改动`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        // 还原目标 = 进入前快照（真实链路里就是快照状态，而非静音档案）
        val result = subject.restore(DeviceMuteState(AudioManager.RINGER_MODE_VIBRATE))

        assertTrue("未授权必须返回 Blocked，不得假报 Restored", result is RestoreResult.Blocked)
        assertTrue(
            "Blocked 原因必须指向勿扰权限",
            (result as RestoreResult.Blocked).reason?.contains("勿扰") == true
        )
        assertEquals("铃声模式不得被改动", AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(
            "勿扰过滤器不得被改动",
            NotificationManager.INTERRUPTION_FILTER_NONE,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun `已静音时各勿扰档位均保持铃声与过滤器`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        for (filter in listOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS
        )) {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            notificationManager.setInterruptionFilter(filter)

            assertTrue("授权后 applyMuted 必须确认成功，filter=$filter", subject.applyMuted())
            assertEquals(DeviceMuteState(AudioManager.RINGER_MODE_SILENT, filter), subject.currentState())
            assertEquals("铃声已静音", AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
            assertEquals("保留当前勿扰过滤器", filter, notificationManager.currentInterruptionFilter)
        }
    }

    @Test
    fun `已开启勿扰时保留用户响铃或振动模式并报告未应用`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        for (ringerMode in listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE)) {
            for (filter in listOf(
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                NotificationManager.INTERRUPTION_FILTER_NONE
            )) {
                audioManager.ringerMode = ringerMode
                notificationManager.setInterruptionFilter(filter)

                assertFalse(subject.applyMuted())
                assertEquals(ringerMode, audioManager.ringerMode)
                assertEquals(filter, notificationManager.currentInterruptionFilter)
            }
        }
    }

    @Test
    fun `授权时restore分别恢复响铃振动和静音快照`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        for (ringerMode in listOf(
            AudioManager.RINGER_MODE_NORMAL,
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT
        )) {
            audioManager.ringerMode = ringerMode
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            val preState = subject.currentState()
            assertTrue(subject.applyMuted())
            // 模拟用户或系统在日程期间改变勿扰过滤器。
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

            val result = subject.restore(preState)

            assertTrue("铃声快照恢复成功，ringerMode=$ringerMode", result is RestoreResult.Restored)
            assertEquals("铃声回到进入前模式", ringerMode, audioManager.ringerMode)
            assertEquals(
                "保留日程期间改变的勿扰过滤器",
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                notificationManager.currentInterruptionFilter
            )
        }
    }

    @Test
    fun `未授权时applyMuted返回false且不写任何系统值`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        arrangePreState()

        assertFalse("未授权 applyMuted 必须失败", subject.applyMuted())
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audioManager.ringerMode)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            notificationManager.currentInterruptionFilter
        )
    }
}

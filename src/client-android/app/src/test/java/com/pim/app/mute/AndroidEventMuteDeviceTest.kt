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
 * [AndroidEventMuteDevice] 的 Robolectric 通路验证（Issue #25 / 工单 04 E3.3 / E3.5）。
 *
 * 现成 shadow（ShadowAudioManager / ShadowNotificationManager）对 setRingerMode /
 * setInterruptionFilter 是「无条件写值、readback 一致」，因此本文件只能证明：
 * - 未授权时系统值确实不被改动、restore 不假报成功；
 * - 授权时写入后 readback 与目标档案一致。
 *
 * **无法覆盖（仅真机可验，NOT-VERIFIED）**：真实机型上系统调用「不抛异常但状态不生效」
 * 的场景（部分 ROM 静默失败）。现成 shadow 不存在让 setter 不生效的开关，
 * 按约定不自创 shadow / 不扩架构，该分支的生产代码（readback 比对返回 Blocked）留待实机验证。
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

    private fun currentReadback() = DeviceMuteState(
        ringerMode = audioManager.ringerMode,
        interruptionFilter = notificationManager.currentInterruptionFilter
    )

    @Test
    fun `未授权勿扰访问时restore返回Blocked且铃声与过滤器都不被改动`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        // 还原目标 = 进入前快照（真实链路里就是快照状态，而非静音档案）
        val result = subject.restore(DeviceMuteState(AudioManager.RINGER_MODE_VIBRATE, NotificationManager.INTERRUPTION_FILTER_ALL))

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
    fun `授权时applyMuted成功且readback等于本功能静音档案`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        arrangePreState()

        assertTrue("授权后 applyMuted 必须确认成功", subject.applyMuted())

        assertEquals("readback：铃声已静音", subject.mutedState().ringerMode, audioManager.ringerMode)
        assertEquals(
            "readback：过滤器已到 NONE",
            subject.mutedState().interruptionFilter,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun `授权时restore按进入前快照还原且readback一致`() {
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        arrangePreState()
        val preState = currentReadback()
        assertTrue(subject.applyMuted())

        val result = subject.restore(preState)

        assertTrue("授权且状态一致时必须 Restored", result is RestoreResult.Restored)
        assertEquals("readback：铃声回到进入前模式", preState.ringerMode, audioManager.ringerMode)
        assertEquals(
            "readback：过滤器回到进入前设置",
            preState.interruptionFilter,
            notificationManager.currentInterruptionFilter
        )
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

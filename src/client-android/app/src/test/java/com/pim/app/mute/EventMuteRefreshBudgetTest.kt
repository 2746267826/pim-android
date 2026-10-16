package com.pim.app.mute

import com.pim.app.keepalive.KeepAliveAlarmReceiver
import com.pim.app.recovery.StartupRecoveryReceiver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 04（E3.2）广播窗口预算的护栏（独立审查 I-3）。
 *
 * 背景：日程静音的计划刷新被挂在**广播接收器**里（叫醒周期 + 开机恢复），
 * 而 `goAsync()` 的窗口约 10 秒。刷新要拉日历本 + 最多 5 页日程，网络慢时必须主动超时放弃，
 * 否则接收器会被系统判为未完成广播，连带影响既有保活链（工单要求「不能因进程被冻结/杀死而漏开或漏恢复」）。
 *
 * 这条用例把「超时值必须留在系统窗口之内」钉住：以后有人把它调到 30s 会立刻变红。
 */
class EventMuteRefreshBudgetTest {

    /** 广播 goAsync 窗口的经验上限（Android 文档口径约 10s）。 */
    private val broadcastWindowMs = 10_000L

    @Test
    fun `叫醒链路的静音刷新超时必须落在广播窗口内`() {
        val timeout = KeepAliveAlarmReceiver.MUTE_REFRESH_TIMEOUT_MS
        assertTrue("超时必须为正数", timeout > 0)
        assertTrue("超时 $timeout ms 必须小于广播窗口 $broadcastWindowMs ms", timeout < broadcastWindowMs)
    }

    @Test
    fun `开机恢复的静音刷新超时必须落在广播窗口内`() {
        val timeout = StartupRecoveryReceiver.MUTE_REFRESH_TIMEOUT_MS
        assertTrue("超时必须为正数", timeout > 0)
        assertTrue("超时 $timeout ms 必须小于广播窗口 $broadcastWindowMs ms", timeout < broadcastWindowMs)
    }
}

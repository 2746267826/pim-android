package com.pim.app.mute

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工单 04（E3.2 / E3.3 / E3.5）到点执行的硬规则。
 *
 * 用 Robolectric 只是为了拿到一个可用的 Context 去构造日志仓库（规则本身不依赖 Android API）；
 * 设备与存储在 [FakeDevice] / [FakeSettings] / [FakeSessions] 里替身化，
 * 因此每条规则都能直接断言「有没有调用系统能力、写没写会话」。
 *
 * 每一条都对应工单里明写的要求或验收项，因此禁止削弱：
 * - AC-7：设备级开关关闭 → 手机完全不动；
 * - AC-5：未授权勿扰访问 → 不改系统状态且留下可见提示；
 * - AC-3：结束时**恢复进入前的状态**（不是一律响铃）；
 * - 重叠日程不能互相把对方的静音状态恢复错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventMuteExecutorTest {

    private val now = 1_700_000_000_000L

    private class FakeDevice(
        var access: Boolean = true,
        var state: DeviceMuteState = DeviceMuteState(ringerMode = 2, interruptionFilter = 1)
    ) : EventMuteDevicePort {
        var applyCalls = 0
        var restoreCalls = 0
        var lastRestored: DeviceMuteState? = null
        var muted = false

        override fun hasDoNotDisturbAccess(): Boolean = access
        override fun currentState(): DeviceMuteState = state
        override fun applyMuted(): Boolean {
            applyCalls++
            if (!access) return false
            muted = true
            state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4)
            return true
        }

        override fun restore(state: DeviceMuteState) {
            restoreCalls++
            lastRestored = state
            muted = false
            this.state = state
        }
    }

    private class FakeSettings(var enabled: Boolean = true) : EventMuteSettingsAccessor {
        var lastError: String? = null
        var refreshCalls = 0

        override fun isFollowRuleEnabled(): Boolean = enabled
        override fun setFollowRuleEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun readPersistedStatus(): PersistedEventMuteStatus = PersistedEventMuteStatus(
            plannedAlarmCount = 0,
            nextAlarmAtUtcMillis = null,
            activeMuteEventIds = emptySet(),
            lastRefreshAtUtcMillis = null,
            lastError = lastError
        )

        override fun recordRefresh(
            planned: Int,
            nextAlarmAtUtcMillis: Long?,
            atUtcMillis: Long,
            error: String?
        ) {
            refreshCalls++
            lastError = error
        }

        override fun recordError(message: String?) {
            lastError = message
        }
    }

    private class FakeSessions(var session: MuteSession? = null) : MuteSessionStore {
        override fun read(): MuteSession? = session
        override fun write(session: MuteSession?) {
            this.session = session
        }
    }

    private fun logs(): StructuredLogRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = TrackingSettingsStore(
            context.getSharedPreferences("event-mute-executor-test", Context.MODE_PRIVATE)
        )
        return StructuredLogRepository(context, settings) { now }
    }

    private fun executor(
        device: FakeDevice,
        settings: FakeSettings,
        sessions: FakeSessions
    ) = EventMuteExecutor(settings, sessions, device, logs()) { now }

    @Test
    fun `设备级开关关闭时完全不动：不拍快照、不调用系统、也不恢复`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings(enabled = false)
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START)
        subject.onAlarm("e1", MuteAction.END)

        assertEquals(0, device.applyCalls)
        assertEquals(0, device.restoreCalls)
        assertNull(sessions.session)
    }

    @Test
    fun `未授权勿扰访问：不改系统状态，并记录可见提示（AC-5）`() = runTest {
        val device = FakeDevice(access = false)
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START)

        assertEquals(0, device.applyCalls)
        assertNull("未授权时不应产生静音会话", sessions.session)
        assertTrue(
            "必须留下可见提示而不是静默失效",
            settings.lastError?.contains("勿扰访问") == true
        )
    }

    @Test
    fun `开始时先拍快照再静音，结束按快照恢复（AC-3）`() = runTest {
        // 用户进入前是「振动」模式，恢复时必须回到振动而不是响铃
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START)
        assertEquals(1, device.applyCalls)
        assertEquals(DeviceMuteState(1, 1), sessions.session?.state)
        assertTrue(device.muted)

        subject.onAlarm("e1", MuteAction.END)
        assertEquals(1, device.restoreCalls)
        assertEquals(DeviceMuteState(ringerMode = 1, interruptionFilter = 1), device.lastRestored)
        assertNull(sessions.session)
    }

    @Test
    fun `重复的开始动作是幂等的：不覆盖已记录的进入前状态`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START)
        // 第二次触发时设备已经处于静音状态：若再拍快照，快照就会变成「静音」，结束时就恢复不回去
        subject.onAlarm("e1", MuteAction.START)

        assertEquals(1, device.applyCalls)
        assertEquals(DeviceMuteState(1, 1), sessions.session?.state)
    }

    @Test
    fun `重叠日程共享会话：最后一个结束的才恢复`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START)
        subject.onAlarm("e2", MuteAction.START) // e2 在 e1 静音期间开始

        assertEquals(1, device.applyCalls)
        assertEquals(setOf("e1", "e2"), sessions.session?.eventIds)
        assertEquals("重叠时不得重新拍快照", DeviceMuteState(1, 1), sessions.session?.state)

        subject.onAlarm("e2", MuteAction.END)
        assertEquals("还有日程在静音中，不能恢复", 0, device.restoreCalls)

        subject.onAlarm("e1", MuteAction.END)
        assertEquals(1, device.restoreCalls)
        assertEquals(DeviceMuteState(1, 1), device.lastRestored)
    }

    @Test
    fun `结束时没有快照：不碰设备（不能覆盖用户自己的设置）`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions(session = null)
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.END)

        assertEquals(0, device.restoreCalls)
    }

    @Test
    fun `结束动作只对自己会话内的事件生效`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions(
            session = MuteSession(setOf("e1"), DeviceMuteState(1, 1), now)
        )
        val subject = executor(device, settings, sessions)

        subject.onAlarm("other", MuteAction.END)

        assertEquals(0, device.restoreCalls)
        assertEquals(setOf("e1"), sessions.session?.eventIds)
    }
}

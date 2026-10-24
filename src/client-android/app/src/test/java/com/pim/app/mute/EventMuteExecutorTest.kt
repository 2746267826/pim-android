package com.pim.app.mute

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val hour = 3_600_000L

    private class FakeDevice(
        var access: Boolean = true,
        var state: DeviceMuteState = DeviceMuteState(ringerMode = 2, interruptionFilter = 1),
        var restoreOutcome: RestoreResult = RestoreResult.Restored
    ) : EventMuteDevicePort {
        var applyCalls = 0
        var restoreCalls = 0
        var lastRestored: DeviceMuteState? = null
        var muted = false
        var blockedState: DeviceMuteState? = null
        var applyOutcome = true
        var applyFailureState: DeviceMuteState? = null
        var readFails = false

        override fun hasDoNotDisturbAccess(): Boolean = access
        override fun currentState(): DeviceMuteState {
            check(!readFails) { "device state unavailable" }
            return state
        }
        override fun mutedState(): DeviceMuteState = DeviceMuteState(ringerMode = 0, interruptionFilter = 4)
        override fun applyMuted(): Boolean {
            applyCalls++
            if (!access) return false
            if (!applyOutcome) {
                applyFailureState?.let { state = it }
                return false
            }
            muted = true
            state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4)
            return true
        }

        override fun restore(state: DeviceMuteState): RestoreResult {
            restoreCalls++
            lastRestored = state
            if (restoreOutcome == RestoreResult.Restored) {
                muted = false
                this.state = state
            } else {
                blockedState?.let { this.state = it }
            }
            return restoreOutcome
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

        subject.onAlarm("e1", MuteAction.START, now + hour)
        assertEquals(1, device.applyCalls)
        assertEquals(DeviceMuteState(1, 1), sessions.session?.state)
        assertEquals(DeviceMuteState(0, 4), sessions.session?.appliedState)
        assertEquals(now + hour, sessions.session?.eventEndsUtcMillis?.get("e1"))
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

    // ---- Issue #25 路径 1：关闭跟随也要收尾已进入的静音会话 ----

    @Test
    fun `开关关闭后 END 仍按快照恢复并清会话`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START)
        assertTrue(device.muted)

        settings.enabled = false
        subject.onAlarm("e1", MuteAction.END)

        assertEquals(1, device.restoreCalls)
        assertEquals(DeviceMuteState(1, 1), device.lastRestored)
        assertNull(sessions.session)
    }

    @Test
    fun `恢复失败时保留会话与可见提示，不假成功`() = runTest {
        // 旧会话没有 appliedState：按本功能固定静音档案保守比对，设备确实处于 (0,4)
        val device = FakeDevice(
            state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4),
            restoreOutcome = RestoreResult.Blocked("缺少「勿扰访问」权限，无法还原")
        )
        val settings = FakeSettings()
        val sessions = FakeSessions(
            session = MuteSession(setOf("e1"), DeviceMuteState(1, 1), now)
        )
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.END)

        assertEquals(1, device.restoreCalls)
        assertNotNull("恢复失败不能清快照", sessions.session)
        assertEquals(setOf("e1"), sessions.session?.eventIds)
        assertEquals(
            "缺少「勿扰访问」权限，无法还原",
            sessions.session?.pendingRestoreReason
        )
        assertTrue(
            "必须留下可见提示",
            settings.lastError?.contains("勿扰访问") == true
        )
    }

    @Test
    fun `恢复失败后重试成功则清会话并清提示`() = runTest {
        val device = FakeDevice(
            state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4),
            restoreOutcome = RestoreResult.Blocked("系统还原调用未成功")
        )
        val settings = FakeSettings()
        val sessions = FakeSessions(
            session = MuteSession(setOf("e1"), DeviceMuteState(1, 1), now)
        )
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.END)
        assertNotNull(sessions.session)

        device.restoreOutcome = RestoreResult.Restored
        subject.onAlarm("e1", MuteAction.END)
        assertNull(sessions.session)
        assertNull(settings.lastError)
    }

    // ---- Issue #25 路径 2：真实 end 对账（TTL 已废除）----

    private fun sessionWithEnds(
        eventIds: Set<String>,
        ends: Map<String, Long>,
        appliedState: DeviceMuteState? = DeviceMuteState(0, 4)
    ) = MuteSession(
        eventIds = eventIds,
        state = DeviceMuteState(1, 1),
        enteredAtUtcMillis = now - hour,
        eventEndsUtcMillis = ends,
        appliedState = appliedState
    )

    @Test
    fun `真实结束时刻已过才离线恢复；未结束或缺end都不动`() = runTest {
        // 会话标记 appliedState=(0,4)：设备此刻应处于本功能写入的静音档案
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4))
        val settings = FakeSettings()
        val sessions = FakeSessions(sessionWithEnds(setOf("e1"), mapOf("e1" to now + 1000L)))
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()
        assertEquals("事件还没结束，不能恢复", 0, device.restoreCalls)

        // 超过 72 小时但事件仍未结束：同样不动（不再有 TTL）
        sessions.write(
            sessionWithEnds(setOf("e1"), mapOf("e1" to now + 1000L))
                .copy(enteredAtUtcMillis = now - 80 * hour)
        )
        subject.reconcileExpiredSession()
        assertEquals("长日程未结束不能被 TTL 误恢复", 0, device.restoreCalls)

        sessions.write(sessionWithEnds(setOf("e1"), mapOf("e1" to now - 1000L)))
        subject.reconcileExpiredSession()
        assertEquals(1, device.restoreCalls)
        assertEquals(DeviceMuteState(1, 1), device.lastRestored)
        assertNull(sessions.session)
    }

    @Test
    fun `重叠逐事件：有一个未结束就继续持有`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions(
            sessionWithEnds(setOf("e1", "e2"), mapOf("e1" to now - 1000L, "e2" to now + 1000L))
        )
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()

        assertEquals(0, device.restoreCalls)
        assertEquals(setOf("e1", "e2"), sessions.session?.eventIds)
    }

    @Test
    fun `缺end的旧会话不猜测：离线不动，刷新核实后恢复`() = runTest {
        // 旧会话没有 appliedState，按本功能固定静音档案保守比对：设备确实处于 (0,4)
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4))
        val settings = FakeSettings()
        val sessions = FakeSessions(
            MuteSession(setOf("old"), DeviceMuteState(1, 1), now - 80 * hour)
        )
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()
        assertEquals("没有真实 end 不能猜", 0, device.restoreCalls)

        subject.reconcileWithKnownEnds(mapOf("old" to now - 2000L))
        assertEquals(1, device.restoreCalls)
        assertNull(sessions.session)
    }

    @Test
    fun `刷新只补会话内事件且部分核实已结束仍持有未结束的`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions(
            MuteSession(setOf("e1", "e2"), DeviceMuteState(1, 1), now - hour)
        )
        val subject = executor(device, settings, sessions)

        subject.reconcileWithKnownEnds(mapOf("e1" to now - 1000L, "unrelated" to now - 1000L))

        assertEquals("e2 还没核实，不能恢复", 0, device.restoreCalls)
        assertEquals(mapOf("e1" to now - 1000L), sessions.session?.eventEndsUtcMillis)
        assertEquals(setOf("e1", "e2"), sessions.session?.eventIds)
    }

    @Test
    fun `刷新核实无任何已知end时保留会话并提示`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions(
            MuteSession(setOf("old"), DeviceMuteState(1, 1), now - hour)
        )
        val subject = executor(device, settings, sessions)

        subject.reconcileWithKnownEnds(mapOf("other" to now - 1000L))

        assertEquals(0, device.restoreCalls)
        assertNotNull(sessions.session)
        assertTrue(settings.lastError?.contains("未能取得其结束时间") == true)
    }

    @Test
    fun `对账恢复失败同样保留会话与提示`() = runTest {
        val device = FakeDevice(
            state = DeviceMuteState(ringerMode = 0, interruptionFilter = 4),
            restoreOutcome = RestoreResult.Blocked("权限被撤销")
        )
        val settings = FakeSettings()
        val sessions = FakeSessions(sessionWithEnds(setOf("e1"), mapOf("e1" to now - 1000L)))
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()

        assertNotNull(sessions.session)
        assertEquals("权限被撤销", sessions.session?.pendingRestoreReason)
        assertTrue(settings.lastError?.contains("下次刷新重试") == true)
    }

    @Test
    fun `旧会话无所有权凭据且设备不在静音档案：宁可不动`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions(
            MuteSession(setOf("old"), DeviceMuteState(1, 1), now - hour)
        )
        val subject = executor(device, settings, sessions)

        subject.reconcileWithKnownEnds(mapOf("old" to now - 1000L))

        assertEquals("旧会话没有凭据，状态又对不上本功能静音档案，不得恢复", 0, device.restoreCalls)
        assertNull("与「用户手动改过」一致：退出接管，保留用户当前状态", sessions.session)
    }

    @Test
    fun `部分还原失败后按实测状态重试并成功恢复`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(0, 4), restoreOutcome = RestoreResult.Blocked("partial failure"))
        device.blockedState = DeviceMuteState(1, 4)
        val settings = FakeSettings()
        val sessions = FakeSessions(sessionWithEnds(setOf("e1"), mapOf("e1" to now - 1000L)))
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()
        assertEquals(DeviceMuteState(1, 4), sessions.session?.appliedState)
        assertNotNull(sessions.session?.pendingRestoreReason)
        device.restoreOutcome = RestoreResult.Restored
        subject.reconcileExpiredSession()

        assertEquals(2, device.restoreCalls)
        assertEquals(DeviceMuteState(1, 1), device.lastRestored)
        assertNull("重试成功后清会话", sessions.session)
    }

    @Test
    fun `部分恢复失败后用户再改设置不能被重试覆盖`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(0, 4), restoreOutcome = RestoreResult.Blocked("partial failure"))
        device.blockedState = DeviceMuteState(1, 4)
        val settings = FakeSettings()
        val sessions = FakeSessions(sessionWithEnds(setOf("e1"), mapOf("e1" to now - 1000L)))
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()
        device.state = DeviceMuteState(2, 1)
        device.restoreOutcome = RestoreResult.Restored
        subject.reconcileExpiredSession()

        assertEquals(1, device.restoreCalls)
        assertEquals(DeviceMuteState(2, 1), device.state)
        assertNull(sessions.session)
    }

    @Test
    fun `静音只应用一半失败后可离线回滚而非遗留勿扰`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(1, 1))
        device.applyOutcome = false
        device.applyFailureState = DeviceMuteState(1, 4)
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START, now + hour)
        assertEquals(DeviceMuteState(1, 4), sessions.session?.appliedState)
        assertNotNull(sessions.session?.pendingRestoreReason)
        subject.reconcileExpiredSession()

        assertEquals(DeviceMuteState(1, 1), device.state)
        assertNull(sessions.session)
    }

    @Test
    fun `结束动作删掉旧end不阻塞剩余事件的漏END恢复`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(0, 4))
        val settings = FakeSettings()
        val sessions = FakeSessions(sessionWithEnds(setOf("a", "b"), mapOf("a" to now + hour, "b" to now - 1)))
        val subject = executor(device, settings, sessions)

        subject.onAlarm("a", MuteAction.END)
        subject.reconcileExpiredSession()

        assertEquals(1, device.restoreCalls)
        assertNull(sessions.session)
    }

    @Test
    fun `读取系统状态失败时不伪造响铃快照也不静音`() = runTest {
        val device = FakeDevice().also { it.readFails = true }
        val settings = FakeSettings()
        val sessions = FakeSessions()

        executor(device, settings, sessions).onAlarm("e1", MuteAction.START, now + hour)

        assertEquals(0, device.applyCalls)
        assertNull(sessions.session)
        assertTrue(settings.lastError?.contains("无法读取") == true)
    }

    @Test
    fun `原始快照写入失败绝不能先静音`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = object : MuteSessionStore {
            override fun read(): MuteSession? = null
            override fun write(session: MuteSession?) { error("disk write failed") }
        }

        val result = runCatching {
            EventMuteExecutor(settings, sessions, device, logs()) { now }.onAlarm("e1", MuteAction.START, now + hour)
        }

        assertTrue(result.isFailure)
        assertEquals(0, device.applyCalls)
    }

    @Test
    fun `已经结束的日程迟到START不得重新静音`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions()

        executor(device, settings, sessions).onAlarm("e1", MuteAction.START, now)

        assertEquals(0, device.applyCalls)
        assertNull(sessions.session)
    }

    @Test
    fun `上次还原未完成时新START不能加入失败会话或清掉错误`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(0, 4), restoreOutcome = RestoreResult.Blocked("restore blocked"))
        val settings = FakeSettings()
        val sessions = FakeSessions(sessionWithEnds(setOf("old"), mapOf("old" to now - 1))
            .copy(pendingRestoreReason = "restore blocked"))

        executor(device, settings, sessions).onAlarm("new", MuteAction.START, now + hour)

        assertEquals(setOf("old"), sessions.session?.eventIds)
        assertEquals(0, device.applyCalls)
        assertTrue(settings.lastError?.contains("下次刷新重试") == true)
    }

    @Test
    fun `恢复前检测到用户手动改过状态：放弃接管并保留用户设置`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START, now + hour)
        assertTrue(device.muted)
        // 用户在静音期间手动改回（不再是本功能写的静音档案 0/4）
        device.state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1)

        subject.onAlarm("e1", MuteAction.END)

        assertEquals("用户已改状态，不得回写快照", 0, device.restoreCalls)
        assertNull("但会话应退出接管", sessions.session)
    }

    @Test
    fun `关闭开关立即收尾：不等真实end`() = runTest {
        val device = FakeDevice(state = DeviceMuteState(ringerMode = 1, interruptionFilter = 1))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START, now + 10 * hour)
        assertTrue(device.muted)

        subject.finishNow()

        assertEquals(1, device.restoreCalls)
        assertNull(sessions.session)
    }

    @Test
    fun `关闭后收尾失败保留会话，刷新对账可重试`() = runTest {
        val device = FakeDevice(restoreOutcome = RestoreResult.Blocked("权限被撤销"))
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.onAlarm("e1", MuteAction.START, now + hour)
        subject.finishNow()
        assertNotNull(sessions.session)
        assertTrue(settings.lastError?.contains("下次刷新重试") == true)

        device.restoreOutcome = RestoreResult.Restored
        subject.reconcileWithKnownEnds(mapOf("e1" to now + hour))
        assertNull("后续刷新对账可完成收尾", sessions.session)
    }

    @Test
    fun `无会话时对账是空操作`() = runTest {
        val device = FakeDevice()
        val settings = FakeSettings()
        val sessions = FakeSessions()
        val subject = executor(device, settings, sessions)

        subject.reconcileExpiredSession()

        assertEquals(0, device.restoreCalls)
    }
}

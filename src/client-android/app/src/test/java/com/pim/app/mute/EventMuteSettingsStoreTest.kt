package com.pim.app.mute

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工单 04（E3.4 / E3.5）设备级开关与静音会话的持久化。
 *
 * 两条要点：
 * - 默认**关闭**：装上新版本不得自动改用户手机的铃声行为；
 * - 会话必须落盘并且能原样读回（进程被杀/重启后仍要按进入前状态恢复）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventMuteSettingsStoreTest {

    private fun store(name: String = "event-mute-store-test"): EventMuteSettingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        return EventMuteSettingsStore(context)
    }

    @Test
    fun `默认关闭：未开启前本机不动`() {
        val subject = store("default-off")

        assertFalse(subject.isFollowRuleEnabled())
        assertFalse(subject.readPersistedStatus().activeMuteEventIds.isNotEmpty())
    }

    @Test
    fun `开关可打开可关闭并持久化`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("toggle", Context.MODE_PRIVATE).edit().clear().commit()
        val subject = EventMuteSettingsStore(context)

        subject.setFollowRuleEnabled(true)
        assertTrue(subject.isFollowRuleEnabled())
        assertTrue(EventMuteSettingsStore(context).isFollowRuleEnabled())

        subject.setFollowRuleEnabled(false)
        assertFalse(EventMuteSettingsStore(context).isFollowRuleEnabled())
    }

    @Test
    fun `静音会话原样往返（含进入前的铃声模式与免打扰过滤器）`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("session-roundtrip", Context.MODE_PRIVATE).edit().clear().commit()
        val writer = EventMuteSettingsStore(context)
        writer.write(
            MuteSession(
                eventIds = setOf("e1", "e2"),
                state = DeviceMuteState(ringerMode = 1, interruptionFilter = 2),
                enteredAtUtcMillis = 1_700_000_000_000L
            )
        )

        // 换一个实例读取（不清库）：证明会话真的落盘了，而不是只活在内存里
        val reader = EventMuteSettingsStore(context)
        val session = reader.read()
        assertEquals(setOf("e1", "e2"), session?.eventIds)
        assertEquals(DeviceMuteState(1, 2), session?.state)
        assertEquals(1_700_000_000_000L, session?.enteredAtUtcMillis)
    }

    @Test
    fun `事件end与实际写入状态随会话往返`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("session-ends", Context.MODE_PRIVATE).edit().clear().commit()
        val writer = EventMuteSettingsStore(context)
        writer.write(
            MuteSession(
                eventIds = setOf("e1", "e2"),
                state = DeviceMuteState(1, 2),
                enteredAtUtcMillis = 1_700_000_000_000L,
                eventEndsUtcMillis = mapOf("e1" to 1_700_000_360_000L, "e2" to 1_700_000_720_000L),
                appliedState = DeviceMuteState(0, 4)
            )
        )

        val reader = EventMuteSettingsStore(context)
        val session = reader.read()
        assertEquals(
            mapOf("e1" to 1_700_000_360_000L, "e2" to 1_700_000_720_000L),
            session?.eventEndsUtcMillis
        )
        assertEquals(DeviceMuteState(0, 4), session?.appliedState)
    }

    @Test
    fun `待还原原因随会话落盘并往返`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("session-pending", Context.MODE_PRIVATE).edit().clear().commit()
        val writer = EventMuteSettingsStore(context)
        writer.write(
            MuteSession(
                eventIds = setOf("e1"),
                state = DeviceMuteState(1, 2),
                enteredAtUtcMillis = 1_700_000_000_000L,
                pendingRestoreReason = "缺少「勿扰访问」权限，无法还原"
            )
        )

        val reader = EventMuteSettingsStore(context)
        assertEquals("缺少「勿扰访问」权限，无法还原", reader.read()?.pendingRestoreReason)
    }

    @Test
    fun `旧版本会话没有待还原字段时按正常会话读取`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(EventMuteSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // 旧版本写入的 JSON：没有 pending_restore_reason 字段
        prefs.edit()
            .putString(
                "mute_session",
                """{"event_ids":["e1"],"ringer_mode":1,"interruption_filter":2,"entered_at":1700000000000}"""
            )
            .commit()

        val session = EventMuteSettingsStore(context).read()

        assertEquals(setOf("e1"), session?.eventIds)
        assertEquals(DeviceMuteState(1, 2), session?.state)
        assertNull("旧会话不能被误判为恢复失败", session?.pendingRestoreReason)
    }

    @Test
    fun `清空会话后读回 null`() {
        val subject = store("session-clear")
        subject.write(MuteSession(setOf("e1"), DeviceMuteState(0, 4), 1L))

        subject.write(null)

        assertNull(subject.read())
    }

    @Test
    fun `刷新状态可持久化并在读取时反映出来`() {
        val subject = store("status-refresh")

        subject.recordRefresh(planned = 3, nextAlarmAtUtcMillis = 1_700_000_600_000L, atUtcMillis = 99L, error = null)
        val status = subject.readPersistedStatus()

        assertEquals(3, status.plannedAlarmCount)
        assertEquals(1_700_000_600_000L, status.nextAlarmAtUtcMillis)
        assertEquals(99L, status.lastRefreshAtUtcMillis)
        assertNull(status.lastError)

        subject.recordRefresh(planned = 0, nextAlarmAtUtcMillis = null, atUtcMillis = 100L, error = "缺少权限")
        val failed = subject.readPersistedStatus()
        assertEquals(0, failed.plannedAlarmCount)
        assertNull(failed.nextAlarmAtUtcMillis)
        assertEquals("缺少权限", failed.lastError)
    }
}

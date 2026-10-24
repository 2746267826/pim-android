package com.pim.app.mute

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.keepalive.KeepAliveAlarmScheduler
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 工单 04（E3.2）日程静音的闹钟登记。
 *
 * 两条硬要求：
 * 1. **只用 `setExactAndAllowWhileIdle`**（与既有 keepalive 链一致的省电语义，不用 setAlarmClock）；
 * 2. **与既有 keepalive 登记不冲突**：action 不同、requestCode 区间不同，
 *    同一时刻不会出现两套 AlarmManager 登记互相覆盖。
 *
 * 断言打在 Robolectric 的 ShadowAlarmManager 上（即真实调用到的系统 API），
 * 而不是「源码里有没有某个字符串」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventMuteAlarmSchedulerTest {

    private val now = 1_700_000_000_000L

    private fun logs(): StructuredLogRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = TrackingSettingsStore(
            context.getSharedPreferences("event-mute-scheduler-test", Context.MODE_PRIVATE)
        )
        return StructuredLogRepository(context, settings) { now }
    }

    private fun scheduler(permission: Boolean = true): Pair<EventMuteAlarmScheduler, AlarmManager> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val instance = EventMuteAlarmScheduler(
            alarmManager = manager,
            appContext = context,
            logs = logs(),
            canScheduleExactAlarms = { permission }
        )
        return instance to manager
    }

    private fun plan(): List<PlannedMuteAlarm> = listOf(
        PlannedMuteAlarm("event-1", MuteAction.START, now + 60_000L),
        PlannedMuteAlarm("event-1", MuteAction.END, now + 180_000L)
    )

    @Test
    fun `按计划登记精确闹钟，触发时刻与计划一致`() = runTest {
        val (subject, manager) = scheduler()

        val result = subject.schedule(plan())

        assertTrue(result is EventMuteAlarmScheduler.Result.Scheduled)
        assertEquals(2, (result as EventMuteAlarmScheduler.Result.Scheduled).count)
        assertEquals(now + 60_000L, result.firstAtUtcMillis)

        val scheduled = shadowOf(manager).scheduledAlarms
        assertEquals(2, scheduled.size)
        assertTrue(
            "必须用 setExactAndAllowWhileIdle（RTC_WAKEUP）",
            scheduled.all { it.type == AlarmManager.RTC_WAKEUP }
        )
        assertEquals(
            setOf(now + 60_000L, now + 180_000L),
            scheduled.map { it.triggerAtTime }.toSet()
        )
    }

    @Test
    fun `START广播携带真实事件结束时刻用于离线恢复`() = runTest {
        val (subject, manager) = scheduler()
        val end = now + 180_000L
        subject.schedule(listOf(PlannedMuteAlarm("event-end", MuteAction.START, now + 60_000L, end)))

        val intent = shadowOf(shadowOf(manager).scheduledAlarms.single().operation).savedIntent
        assertEquals("event-end", intent.getStringExtra(EventMuteAlarmScheduler.EXTRA_EVENT_ID))
        assertEquals(MuteAction.START.name, intent.getStringExtra(EventMuteAlarmScheduler.EXTRA_ACTION))
        assertEquals(end, intent.getLongExtra(EventMuteAlarmScheduler.EXTRA_EVENT_END, -1L))
    }

    @Test
    fun `与 keepalive 的登记不冲突：action 与 requestCode 都不同`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (subject, manager) = scheduler()

        subject.schedule(plan())

        val actions = shadowOf(manager).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent.action }
            .toSet()
        assertTrue(
            "日程静音与 keepalive 必须是两个 action，否则会互相覆盖",
            actions.contains(EventMuteAlarmScheduler.ACTION_EVENT_MUTE)
        )
        assertTrue(
            "不得复用 keepalive 的叫醒 action",
            !actions.contains(KeepAliveAlarmScheduler.ACTION_KEEPALIVE_ALARM)
        )

        val requestCodes = (0 until EventMuteAlarmScheduler.MAX_SLOTS * 2)
            .map { EventMuteAlarmScheduler.REQUEST_CODE_BASE + it }
            .toSet()
        assertTrue(
            "keepalive 的固定 requestCode 必须落在日程静音的保留区间之外",
            !requestCodes.contains(KeepAliveAlarmScheduler.REQUEST_CODE)
        )
        assertNotEquals(EventMuteAlarmScheduler.REQUEST_CODE_BASE, KeepAliveAlarmScheduler.REQUEST_CODE)
        // 同一计划的 START/END 用不同槽位
        assertNotEquals(
            EventMuteAlarmScheduler.requestCodeFor(0, MuteAction.START),
            EventMuteAlarmScheduler.requestCodeFor(0, MuteAction.END)
        )
        assertEquals(
            "槽位基址 + 下标×2（START/END 各占一个）",
            EventMuteAlarmScheduler.REQUEST_CODE_BASE,
            EventMuteAlarmScheduler.requestCodeFor(0, MuteAction.START)
        )
        assertEquals(
            EventMuteAlarmScheduler.REQUEST_CODE_BASE + 3,
            EventMuteAlarmScheduler.requestCodeFor(1, MuteAction.END)
        )
    }

    @Test
    fun `重复登记会整体替换，不残留上一份计划`() = runTest {
        val (subject, manager) = scheduler()

        subject.schedule(plan())
        subject.schedule(listOf(PlannedMuteAlarm("event-2", MuteAction.START, now + 900_000L)))

        val scheduled = shadowOf(manager).scheduledAlarms
        assertEquals("旧计划的槽位必须被取消", 1, scheduled.size)
        assertEquals(now + 900_000L, scheduled.first().triggerAtTime)
    }

    @Test
    fun `取消全部登记`() = runTest {
        val (subject, manager) = scheduler()
        subject.schedule(plan())

        subject.cancelAll()

        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `未授予精确闹钟权限：不登记任何闹钟并明确返回 PermissionMissing`() = runTest {
        val (subject, manager) = scheduler(permission = false)

        val result = subject.schedule(plan())

        assertEquals(EventMuteAlarmScheduler.Result.PermissionMissing, result)
        assertTrue(
            "不允许退回不精确的 set()：宁可明确不可用，也不能悄悄漂移",
            shadowOf(manager).scheduledAlarms.isEmpty()
        )
    }

    @Test
    fun `空计划不报错`() = runTest {
        val (subject, manager) = scheduler()

        val result = subject.schedule(emptyList())

        assertTrue(result is EventMuteAlarmScheduler.Result.Scheduled)
        assertTrue(shadowOf(manager).scheduledAlarms.isEmpty())
    }
}

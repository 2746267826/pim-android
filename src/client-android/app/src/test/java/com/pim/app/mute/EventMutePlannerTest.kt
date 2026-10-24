package com.pim.app.mute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 04（E3.2）静音计划规则（AC-3 / AC-4 的计划侧）。
 *
 * 这些规则决定「到点会不会触发」以及「漏触发防护」，必须在纯 JVM 上钉死，
 * 不能只靠真机观察（真机验收由维护者执行，见 PR）。
 */
class EventMutePlannerTest {

    private val now = 1_700_000_000_000L // 固定时刻，避免用系统时钟造成不稳定断言
    private val hour = 3_600_000L

    private fun event(
        id: String,
        startOffsetHours: Long,
        durationHours: Long = 1,
        muted: Boolean = true
    ) = UpcomingMuteEvent(
        eventId = id,
        title = id,
        startUtcMillis = now + startOffsetHours * hour,
        endUtcMillis = now + (startOffsetHours + durationHours) * hour,
        effectiveMuted = muted
    )

    @Test
    fun `生效值：日程显式值优先，未指定跟随日历本默认`() {
        assertTrue(EventMutePlanner.isEffectivelyMuted(eventIsMuted = true, calendarDefaultMuted = false))
        assertFalse(EventMutePlanner.isEffectivelyMuted(eventIsMuted = false, calendarDefaultMuted = true))
        assertTrue(EventMutePlanner.isEffectivelyMuted(eventIsMuted = null, calendarDefaultMuted = true))
        assertFalse(EventMutePlanner.isEffectivelyMuted(eventIsMuted = null, calendarDefaultMuted = false))
        assertFalse(EventMutePlanner.isEffectivelyMuted(eventIsMuted = null, calendarDefaultMuted = null))
    }

    @Test
    fun `只有生效值为静音的日程会登记闹钟`() {
        val plan = EventMutePlanner.plan(
            events = listOf(
                event("muted", 1, muted = true),
                event("not-muted", 1, muted = false)
            ),
            nowUtcMillis = now
        )

        assertEquals(listOf("muted", "muted"), plan.map { it.eventId })
    }

    @Test
    fun `未来日程：开始与结束各登记一条，时刻与日程一致`() {
        val plan = EventMutePlanner.plan(listOf(event("e1", 2)), now)

        assertEquals(2, plan.size)
        assertEquals(MuteAction.START, plan[0].action)
        assertEquals(now + 2 * hour, plan[0].triggerAtUtcMillis)
        assertEquals(MuteAction.END, plan[1].action)
        assertEquals(now + 3 * hour, plan[1].triggerAtUtcMillis)
    }

    @Test
    fun `已经开始但未结束的日程：立即补一次静音（进程被杀重启后不漏开）`() {
        val plan = EventMutePlanner.plan(listOf(event("running", -1, durationHours = 3)), now)

        val start = plan.single { it.action == MuteAction.START }
        assertEquals("补开的触发时刻取当前时刻，不回填到过去的开始时刻", now, start.triggerAtUtcMillis)
        assertEquals(now + 2 * hour, plan.single { it.action == MuteAction.END }.triggerAtUtcMillis)
    }

    @Test
    fun `已结束的日程不进计划`() {
        val plan = EventMutePlanner.plan(listOf(event("past", -3, durationHours = 1)), now)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `每条计划都携带真实事件结束时刻（END 丢失后靠它离线收尾）`() {
        val plan = EventMutePlanner.plan(
            listOf(event("e1", 2), event("running", -1, durationHours = 3)),
            now
        )

        for (alarm in plan) {
            val expectedEnd = if (alarm.eventId == "e1") now + 3 * hour else now + 2 * hour
            assertEquals("事件 $alarm 必须携带真实 end", expectedEnd, alarm.eventEndUtcMillis)
        }
    }

    @Test
    fun `超出窗口的日程等下一次刷新，不占闹钟槽位`() {
        val plan = EventMutePlanner.plan(
            events = listOf(
                event("inside", EventMutePlanner.HORIZON_HOURS - 1),
                event("outside", EventMutePlanner.HORIZON_HOURS + 5)
            ),
            nowUtcMillis = now
        )

        assertEquals(setOf("inside"), plan.map { it.eventId }.toSet())
    }

    @Test
    fun `同一事件同动作只登记一条，且按触发时刻升序`() {
        val duplicate = event("e1", 2)
        val plan = EventMutePlanner.plan(
            events = listOf(duplicate, duplicate, event("e2", 1)),
            nowUtcMillis = now
        )

        assertEquals(4, plan.size)
        assertEquals(plan.sortedBy { it.triggerAtUtcMillis }, plan)
    }

    @Test
    fun `登记条数不超过上限，且保留最近的`() {
        val many = (0 until 80).map { event("e$it", it + 1L, durationHours = 1) }
        val plan = EventMutePlanner.plan(many, now)

        assertTrue(plan.size <= EventMutePlanner.MAX_ALARMS)
        // 最早的那条一定在（最不该丢的是最近要触发的那次静音）
        assertTrue(plan.any { it.eventId == "e0" && it.action == MuteAction.START })
    }
}

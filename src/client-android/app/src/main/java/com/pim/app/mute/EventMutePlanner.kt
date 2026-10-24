package com.pim.app.mute

/**
 * 工单 04（E3.2）：把「哪些日程需要在什么时刻静音/恢复」算成一份闹钟计划。
 *
 * 纯函数、无 Android 依赖 —— 这些规则直接对应 AC-3（到点静音、结束后恢复）与 AC-4（漏触发防护），
 * 必须能在 JVM 上逐条断言，而不是只能上真机看。
 */
object EventMutePlanner {

    /** 计划窗口：向后看 [HORIZON_HOURS] 小时。窗口外的事件等下一次刷新时再排。 */
    const val HORIZON_HOURS = 24L

    /** 向后看一点，覆盖「进程刚启动时已经开始的日程」（不允许因为晚启动就漏开）。 */
    const val LOOKBACK_HOURS = 2L

    /**
     * 单次登记的闹钟条数上限。
     *
     * 系统对每个应用的 AlarmManager 登记数量有限制（不同厂商 500 上下），
     * 而且计划本身会被既有 keepalive 链周期性重建，所以取一个远小于上限的值。
     */
    const val MAX_ALARMS = 50

    /** 已经开始但还没结束的日程：立即补一次静音（进程被杀/重启后仍要静音）。 */
    fun plan(
        events: List<UpcomingMuteEvent>,
        nowUtcMillis: Long,
        horizonHours: Long = HORIZON_HOURS
    ): List<PlannedMuteAlarm> {
        val horizonEnd = nowUtcMillis + horizonHours * 3_600_000L
        val alarms = mutableListOf<PlannedMuteAlarm>()

        for (event in events) {
            if (!event.effectiveMuted) continue
            if (event.endUtcMillis <= nowUtcMillis) continue // 已经结束
            if (event.startUtcMillis > horizonEnd) continue // 太远，等下次刷新

            val alreadyStarted = event.startUtcMillis <= nowUtcMillis
            alarms += PlannedMuteAlarm(
                eventId = event.eventId,
                action = MuteAction.START,
                // 已经开始的：立刻补开（用 now 而不是过去的开始时刻，避免 setExact 过去时间立即触发造成抖动）
                triggerAtUtcMillis = if (alreadyStarted) nowUtcMillis else event.startUtcMillis,
                eventEndUtcMillis = event.endUtcMillis
            )
            alarms += PlannedMuteAlarm(
                eventId = event.eventId,
                action = MuteAction.END,
                triggerAtUtcMillis = event.endUtcMillis,
                eventEndUtcMillis = event.endUtcMillis
            )
        }

        // 同一 (event, action) 只保留一条；按时间升序取最近的 MAX_ALARMS 条
        return alarms
            .distinctBy { it.eventId to it.action }
            .sortedWith(compareBy({ it.triggerAtUtcMillis }, { it.eventId }, { it.action.name }))
            .take(MAX_ALARMS)
    }

    /**
     * 生效值解析（与 pim-api 的三态语义一致）：日程自身设定优先，未指定(null)则跟随日历本默认。
     */
    fun isEffectivelyMuted(eventIsMuted: Boolean?, calendarDefaultMuted: Boolean?): Boolean =
        eventIsMuted ?: calendarDefaultMuted ?: false
}

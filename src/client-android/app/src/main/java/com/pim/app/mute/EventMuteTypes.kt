package com.pim.app.mute

/**
 * 工单 04（E3）「日程期间静音」的领域类型。
 *
 * 语义与 pim-api 侧一致（三态字段 isMuted + 日历本 defaultMuted）：
 * 生效值 = `event.isMuted ?: calendar.defaultMuted`；只有生效值为 true 的日程才需要在本机静音。
 */

/** 需要在本机执行的动作：日程开始 → 静音；日程结束 → 恢复进入前的状态。 */
enum class MuteAction { START, END }

/**
 * 一条待登记的精确闹钟。
 *
 * @param eventId 发生（展开后的）occurence id；系列日程按发生实例各登记一条
 * @param action START / END
 * @param triggerAtUtcMillis 触发时刻（UTC 毫秒）
 */
data class PlannedMuteAlarm(
    val eventId: String,
    val action: MuteAction,
    val triggerAtUtcMillis: Long
)

/** 规划输入：一条日程（已由 [EventMutePlanner] 判定过生效值）。 */
data class UpcomingMuteEvent(
    val eventId: String,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val effectiveMuted: Boolean
)

/** 本机静音相关状态：铃声模式 + 免打扰过滤器。两者都要还原，缺一不可。 */
data class DeviceMuteState(
    val ringerMode: Int,
    val interruptionFilter: Int
)

/**
 * 一次「静音会话」：把进入前的本机状态记下来，结束时原样还原（E3.5）。
 *
 * 为什么要 eventIds 而不是单事件：日程会重叠。B 在 A 静音期间开始，若 B 也各自记一份
 * 「进入前状态」，B 结束时就会把设备恢复成 A 静音中的状态，A 结束后反而永远静音。
 * 因此会话是**共享**的：第一个开始的事件拍快照，最后一个结束的事件负责还原。
 */
data class MuteSession(
    val eventIds: Set<String>,
    val state: DeviceMuteState,
    val enteredAtUtcMillis: Long
)

/** 持久化的那部分状态（权限是系统实时状态，不落盘，见 [EventMuteStatus]）。 */
data class PersistedEventMuteStatus(
    val plannedAlarmCount: Int,
    val nextAlarmAtUtcMillis: Long?,
    val activeMuteEventIds: Set<String>,
    val lastRefreshAtUtcMillis: Long?,
    val lastError: String?
)

/** 设置页与诊断用的状态快照 = 持久化状态 + 实时权限/开关。 */
data class EventMuteStatus(
    val followRuleEnabled: Boolean,
    val doNotDisturbAccessGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val plannedAlarmCount: Int,
    val nextAlarmAtUtcMillis: Long?,
    val activeMuteEventIds: Set<String>,
    val lastRefreshAtUtcMillis: Long?,
    val lastError: String?
) {
    /** 是否正处于「本机被本功能静音」的状态（设置页用来说明当前状态）。 */
    val isMutingNow: Boolean get() = activeMuteEventIds.isNotEmpty()
}

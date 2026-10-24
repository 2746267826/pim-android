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
    val triggerAtUtcMillis: Long,
    /**
     * 该事件的真实结束时刻（UTC 毫秒）。END 闹钟丢失/重启后被系统清掉时，
     * 会话收尾靠它离线判定「确实结束了」，不做任何 TTL 猜测。
     */
    val eventEndUtcMillis: Long? = null
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
 * 还原调用的确认结果：恢复「成功」必须以系统调用被确认执行为准，
 * 失败（如勿扰访问被撤销）时调用方必须保留会话并给出可见提示，不允许先清快照假成功。
 */
sealed interface RestoreResult {
    data object Restored : RestoreResult
    data class Blocked(val reason: String?) : RestoreResult
}

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
    val enteredAtUtcMillis: Long,
    /**
     * 恢复失败待重试时的可见原因；null 表示正常进行中（旧版本写入的会话没有该字段，
     * 读取时按正常会话处理，不猜测设备当前状态）。
     */
    val pendingRestoreReason: String? = null,
    /**
     * 会话内每个事件的真实结束时刻。旧版本会话没有该数据，只能等刷新拉到
     * 真实日程后补齐；在补齐前不做任何「过期了大概结束了」的猜测。
     */
    val eventEndsUtcMillis: Map<String, Long> = emptyMap(),
    /**
     * 本功能预期写入的状态，或部分操作失败后的实测状态（所有权凭据）。
     * 每次恢复前都比对；被用户改过就退出接管。null 是旧会话，只能按固定静音
     * 档案保守比对。不能凭此分辨用户手动设成完全相同的状态。
     */
    val appliedState: DeviceMuteState? = null
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

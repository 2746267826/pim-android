package com.pim.app.mute

/**
 * 工单 04（E3）的窄接口：把「能碰到系统的部分」与「判定规则」分开，
 * 让开始/结束的每一条硬规则都能在 JVM 上逐条断言（与 keepalive 包同样的做法）。
 */

/**
 * 本机静音能力的接口。
 *
 * 生产实现 [AndroidEventMuteDevice]：铃声模式走 `AudioManager.setRingerMode`，
 * 免打扰走 `NotificationManager.setInterruptionFilter`（需要勿扰访问权限）。
 */
interface EventMuteDevicePort {
    /** 是否已授予勿扰访问权限（`ACCESS_NOTIFICATION_POLICY`）。未授权时**不允许静默失效**（E3.3 / AC-5）。 */
    fun hasDoNotDisturbAccess(): Boolean

    /** 读取当前状态（静音前必须先拍快照）。 */
    fun currentState(): DeviceMuteState

    /** 执行静音。未授权时返回 false，调用方必须留下可见出口。 */
    fun applyMuted(): Boolean

    /** 还原到给定状态（E3.5：不是「一律调成响铃」）。 */
    fun restore(state: DeviceMuteState)
}

/** 本设备是否遵循该规则 + 运行状态（E3.4 / AC-7）。 */
interface EventMuteSettingsAccessor {
    fun isFollowRuleEnabled(): Boolean
    fun setFollowRuleEnabled(enabled: Boolean)
    fun readPersistedStatus(): PersistedEventMuteStatus
    fun recordRefresh(
        planned: Int,
        nextAlarmAtUtcMillis: Long?,
        atUtcMillis: Long,
        error: String?
    )
    fun recordError(message: String?)
}

/** 静音会话的持久化（进程被杀/重启后仍要能还原，E3.2 / E3.5）。 */
interface MuteSessionStore {
    fun read(): MuteSession?
    fun write(session: MuteSession?)
}

/** 精确闹钟登记能力（让编排逻辑可在 JVM 上断言，生产实现是 [EventMuteAlarmScheduler]）。 */
interface EventMuteSchedulePort {
    /** 登记计划（内部会写结构化日志，因此是 suspend）。 */
    suspend fun schedule(plan: List<PlannedMuteAlarm>): EventMuteAlarmScheduler.Result

    /** 取消本功能登记的全部闹钟（不影响 keepalive 的登记）。 */
    suspend fun cancelAll()

    /** 是否具备精确闹钟授权（纯查询，不写日志）。 */
    fun hasExactAlarmPermission(): Boolean
}

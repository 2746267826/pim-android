package com.pim.app.mute

import com.pim.app.mobile.logs.StructuredLogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工单 04（E3.2 / E3.3 / E3.5）：闹钟真正到点时的执行规则。
 *
 * 这里集中了本工单最容易写错、后果最直接的几条规则，因此每条都可以在 JVM 上断言
 * （依赖 [EventMuteSettingsAccessor]、[MuteSessionStore]、[EventMuteDevicePort] 三个窄接口）：
 *
 * 1. **设备级开关关闭 → 完全不动**（E3.4 / AC-7）：不看权限、不写状态，连快照都不拍。
 * 2. **未授权勿扰访问 → 不改任何系统状态并留下可见出口**（E3.3 / AC-5），不允许静默失效。
 * 3. **开始时先拍快照再静音**（E3.5）；同一事件重复触发是幂等的（广播可能重发）。
 * 4. **重叠日程共享一个会话**：第一个开始的事件拍快照，最后一个结束的事件负责还原，
 *    避免 B 结束时把设备恢复成「A 静音中」的状态。
 * 5. **结束时没有快照 → 不碰设备**：宁可不动，也不能把用户自己设的响铃/振动覆盖掉。
 */
@Singleton
class EventMuteExecutor internal constructor(
    private val settings: EventMuteSettingsAccessor,
    private val sessions: MuteSessionStore,
    private val device: EventMuteDevicePort,
    private val logs: StructuredLogRepository,
    private val nowUtcMillis: () -> Long
) {
    @Inject
    constructor(
        settings: EventMuteSettingsAccessor,
        sessions: MuteSessionStore,
        device: EventMuteDevicePort,
        logs: StructuredLogRepository
    ) : this(settings, sessions, device, logs, System::currentTimeMillis)

    suspend fun onAlarm(eventId: String, action: MuteAction) {
        if (!settings.isFollowRuleEnabled()) {
            logs.info(LOG_TAG, "本设备未开启「遵循日程静音规则」，忽略 $action（$eventId）")
            return
        }
        when (action) {
            MuteAction.START -> handleStart(eventId)
            MuteAction.END -> handleEnd(eventId)
        }
    }

    private suspend fun handleStart(eventId: String) {
        val existing = sessions.read()
        if (existing != null && existing.eventIds.contains(eventId)) {
            // 幂等：广播重发或补开时不要覆盖已记录的「进入前状态」
            logs.info(LOG_TAG, "事件已在静音会话中，忽略重复的开始动作（$eventId）")
            return
        }

        if (!device.hasDoNotDisturbAccess()) {
            // E3.3 / AC-5：必须可见，不能静默失效
            settings.recordError("未授予「勿扰访问」权限，无法在日程期间静音")
            logs.warn(LOG_TAG, "未授予勿扰访问权限，跳过静音（$eventId）")
            return
        }

        if (existing != null) {
            // 重叠：复用已有会话与快照，最后一个结束的事件负责还原
            sessions.write(existing.copy(eventIds = existing.eventIds + eventId))
            logs.info(LOG_TAG, "日程与进行中的静音会话重叠，加入会话（$eventId）")
            settings.recordError(null)
            return
        }

        val state = device.currentState()
        sessions.write(
            MuteSession(
                eventIds = setOf(eventId),
                state = state,
                enteredAtUtcMillis = nowUtcMillis()
            )
        )
        val applied = device.applyMuted()
        if (applied) {
            settings.recordError(null)
            logs.info(
                LOG_TAG,
                "日程开始：已静音（进入前：ringerMode=${state.ringerMode}，" +
                    "filter=${state.interruptionFilter}）"
            )
        } else {
            // 权限在读取与调用之间被撤销：会话保留，以便恢复时仍然按进入前状态还原
            settings.recordError("静音失败：勿扰访问权限不可用")
            logs.warn(LOG_TAG, "静音调用失败（权限不可用），已记录进入前状态以便恢复")
        }
    }

    private suspend fun handleEnd(eventId: String) {
        val session = sessions.read()
        if (session == null) {
            // 没有快照：不碰设备（否则会把用户自己的设置覆盖成响铃）
            logs.info(LOG_TAG, "没有进入前状态记录，结束时不改动本机状态（$eventId）")
            return
        }
        if (!session.eventIds.contains(eventId)) {
            logs.info(LOG_TAG, "事件不在当前静音会话中，忽略结束动作（$eventId）")
            return
        }

        val remaining = session.eventIds - eventId
        if (remaining.isNotEmpty()) {
            sessions.write(session.copy(eventIds = remaining))
            logs.info(LOG_TAG, "仍有日程处于静音会话中，暂不恢复（剩余 ${remaining.size} 条）")
            return
        }

        device.restore(session.state)
        sessions.write(null)
        settings.recordError(null)
        logs.info(
            LOG_TAG,
            "日程结束：已恢复进入前状态（ringerMode=${session.state.ringerMode}，" +
                "filter=${session.state.interruptionFilter}）"
        )
    }

    companion object {
        const val LOG_TAG = "eventmute"
    }
}

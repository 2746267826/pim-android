package com.pim.app.mute

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工单 04（E3.3 / E3.5）的本机静音实现。
 *
 * 两条系统通路都要动，缺一不可：
 * - `AudioManager.setRingerMode`：把来电/通知铃声切到静音（用户能直观看到音量键的变化）；
 * - `NotificationManager.setInterruptionFilter`：免打扰过滤器，决定通知是否打扰（需要勿扰访问权限）。
 *
 * 权限：免打扰访问（`ACCESS_NOTIFICATION_POLICY`）只能由用户在系统设置里授予，
 * 用 [hasDoNotDisturbAccess] 判定；**未授权时不写任何系统状态**，由上层给出可见提示（E3.3 / AC-5）。
 *
 * 还原（E3.5）：进入前把 [DeviceMuteState] 快照下来（铃声模式 + 过滤器），结束时原样写回 ——
 * 不做「一律调成响铃」，否则会覆盖用户自己的振动/静音偏好。
 */
@Singleton
class AndroidEventMuteDevice @Inject constructor(
    @ApplicationContext private val context: Context
) : EventMuteDevicePort {

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun hasDoNotDisturbAccess(): Boolean =
        notificationManager?.isNotificationPolicyAccessGranted ?: false

    override fun currentState(): DeviceMuteState {
        val am = checkNotNull(audioManager) { "系统未提供铃声服务" }
        val nm = checkNotNull(notificationManager) { "系统未提供通知服务" }
        val state = DeviceMuteState(am.ringerMode, nm.currentInterruptionFilter)
        check(state.ringerMode in AudioManager.RINGER_MODE_SILENT..AudioManager.RINGER_MODE_NORMAL)
        check(state.interruptionFilter in NotificationManager.INTERRUPTION_FILTER_ALL..NotificationManager.INTERRUPTION_FILTER_ALARMS)
        return state
    }

    override fun mutedState(): DeviceMuteState = DeviceMuteState(
        ringerMode = AudioManager.RINGER_MODE_SILENT,
        interruptionFilter = NotificationManager.INTERRUPTION_FILTER_NONE
    )

    override fun applyMuted(): Boolean {
        if (!hasDoNotDisturbAccess()) return false
        val nm = notificationManager
        val am = audioManager
        if (nm == null || am == null) return false
        return try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            currentState() == mutedState()
        } catch (_: SecurityException) {
            // 权限在读取与调用之间被撤销：返回 false，由上层留下可见出口
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun restore(state: DeviceMuteState): RestoreResult {
        if (!hasDoNotDisturbAccess()) return RestoreResult.Blocked("缺少「勿扰访问」权限，无法还原")
        val nm = notificationManager
        val am = audioManager
        if (nm == null || am == null) return RestoreResult.Blocked("系统未提供铃声/通知服务")
        try {
            // 先恢复铃声模式再恢复过滤器：某些机型在 DND 关闭时会重置铃声模式
            am.ringerMode = state.ringerMode
            nm.setInterruptionFilter(state.interruptionFilter)
            return if (currentState() == state) RestoreResult.Restored
            else RestoreResult.Blocked("系统状态未恢复到进入前设置")
        } catch (_: SecurityException) {
            // 未授权时不得静默改状态：什么都不做，交由上层提示
            return RestoreResult.Blocked("缺少「勿扰访问」权限，无法还原")
        } catch (_: Exception) {
            // 同上
            return RestoreResult.Blocked("系统还原调用未成功")
        }
    }
}

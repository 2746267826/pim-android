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
 * 使用 `AudioManager.setRingerMode` 切换铃声静音模式。
 *
 * Android N 起，可能联动勿扰状态的铃声模式切换需要勿扰访问权限。
 * 勿扰访问（`ACCESS_NOTIFICATION_POLICY`）只能由用户在系统设置里授予，
 * 用 [hasDoNotDisturbAccess] 判定；**未授权时不写任何系统状态**，由上层给出可见提示（E3.3 / AC-5）。
 *
 * 还原（E3.5）：进入前把 [DeviceMuteState] 的铃声模式快照下来，结束时按原值写回。
 * 勿扰过滤器作为只读接管凭据，帮助执行器识别用户在会话期间的修改。
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
        val filter = nm.currentInterruptionFilter
        val state = DeviceMuteState(am.ringerMode, filter)
        check(state.ringerMode in AudioManager.RINGER_MODE_SILENT..AudioManager.RINGER_MODE_NORMAL)
        check(filter in NotificationManager.INTERRUPTION_FILTER_ALL..NotificationManager.INTERRUPTION_FILTER_ALARMS)
        return state
    }

    override fun applyMuted(): Boolean {
        if (!hasDoNotDisturbAccess()) return false
        val am = audioManager
        if (am == null) return false
        return try {
            val ringerMode = am.ringerMode
            val filter = notificationManager?.currentInterruptionFilter
            // 保留用户已经启用的勿扰，交由上层提示手动静音。
            if (ringerMode != AudioManager.RINGER_MODE_SILENT &&
                filter != NotificationManager.INTERRUPTION_FILTER_ALL
            ) return false
            // AOSP 的 VIBRATE → 外部 SILENT 会保留内部 VIBRATE。
            // 先退出振动，再进入静音，使内部铃声模式也变成 SILENT。
            if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            am.ringerMode == AudioManager.RINGER_MODE_SILENT
        } catch (_: SecurityException) {
            // 权限在读取与调用之间被撤销：返回 false，由上层留下可见出口
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun restore(state: DeviceMuteState): RestoreResult {
        if (!hasDoNotDisturbAccess()) return RestoreResult.Blocked("缺少「勿扰访问」权限，无法还原")
        val am = audioManager
        if (am == null) return RestoreResult.Blocked("系统未提供铃声服务")
        try {
            am.ringerMode = state.ringerMode
            return if (am.ringerMode == state.ringerMode) RestoreResult.Restored
            else RestoreResult.Blocked("铃声模式未恢复到进入前设置")
        } catch (_: SecurityException) {
            // 权限在检查后被撤销，由上层保留快照并提示重试。
            return RestoreResult.Blocked("缺少「勿扰访问」权限，无法还原")
        } catch (_: Exception) {
            return RestoreResult.Blocked("系统还原调用未成功")
        }
    }
}

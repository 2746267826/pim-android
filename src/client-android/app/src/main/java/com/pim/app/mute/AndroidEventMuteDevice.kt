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
        val state = DeviceMuteState(am.ringerMode)
        check(state.ringerMode in AudioManager.RINGER_MODE_SILENT..AudioManager.RINGER_MODE_NORMAL)
        return state
    }

    override fun mutedState(): DeviceMuteState = DeviceMuteState(
        ringerMode = AudioManager.RINGER_MODE_SILENT
    )

    override fun applyMuted(): Boolean {
        if (!hasDoNotDisturbAccess()) return false
        val am = audioManager
        if (am == null) return false
        return try {
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
        val am = audioManager
        if (am == null) return RestoreResult.Blocked("系统未提供铃声服务")
        try {
            am.ringerMode = state.ringerMode
            return if (currentState() == state) RestoreResult.Restored
            else RestoreResult.Blocked("铃声模式未恢复到进入前设置")
        } catch (_: SecurityException) {
            // 权限在检查后被撤销，由上层保留快照并提示重试。
            return RestoreResult.Blocked("缺少「勿扰访问」权限，无法还原")
        } catch (_: Exception) {
            return RestoreResult.Blocked("系统还原调用未成功")
        }
    }
}

package com.pim.app.mobile.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机设备标识（端点页的另一半唯一键 `(user_id, device_id)` 里的 `device_id`）。
 *
 * @param deviceId 上报给服务端的标识（`android-<16 位 sha256>`）。
 * @param androidIdHash 原始 androidId 的哈希（只在设备档案里留痕，不上传明文）。
 */
data class MobileDeviceIdentity(val deviceId: String, val androidIdHash: String?)

/**
 * 设备标识的**唯一**来源（工单 06 / AC-4）。
 *
 * 端点心跳、`POST /api/v1/mobile/devices/register` 与各移动端上传接口必须用**同一个**
 * `deviceId`：新造一个 ID 会让同一台机器在端点页显示成两台设备，也会让"设备最后一次活着的时间"
 * 记到另一行上。因此这里的实现从 [MobileSyncCoordinator] 里搬了出来，改成两边共用一份，
 * 而不是各写一份"看起来一样"的哈希。
 */
@Singleton
class DeviceIdentityProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun current(): MobileDeviceIdentity {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val seed = androidId ?: Build.FINGERPRINT ?: "android-device"
        val hash = sha256Hex(seed)
        return MobileDeviceIdentity(
            deviceId = "android-${hash.take(16)}",
            androidIdHash = androidId?.let { sha256Hex(it) }
        )
    }
}

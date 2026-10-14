package com.pim.app.ui.permissions

import com.pim.app.status.PermissionStatusSnapshot

/**
 * 工单 04：勿扰访问权限的 issue code。
 *
 * 设置页的「日程静音」分区与「权限」清单共用这一个常量，避免两处字面量漂移导致
 * 「提示没有授权入口」或「入口点了没反应」。
 */
const val DND_ACCESS_ISSUE_CODE = "dnd-access-missing"

data class PermissionSettingRow(
    val issueCode: String,
    val title: String,
    val granted: Boolean,
    val isHardBlock: Boolean
)

fun permissionSettingRows(snapshot: PermissionStatusSnapshot): List<PermissionSettingRow> = listOf(
    PermissionSettingRow(
        issueCode = "notification-permission-missing",
        title = "通知权限",
        granted = snapshot.notificationGranted,
        isHardBlock = true
    ),
    PermissionSettingRow(
        issueCode = "foreground-location-missing",
        title = "精确定位权限",
        granted = snapshot.preciseLocationGranted,
        isHardBlock = true
    ),
    PermissionSettingRow(
        issueCode = "background-location-missing",
        title = "后台定位权限",
        granted = snapshot.backgroundLocationGranted,
        isHardBlock = true
    ),
    PermissionSettingRow(
        issueCode = "usage-access-missing",
        title = "使用情况权限",
        granted = snapshot.usageAccessGranted,
        isHardBlock = false
    ),
    PermissionSettingRow(
        issueCode = "activity-recognition-missing",
        title = "运动识别权限",
        granted = snapshot.activityRecognitionGranted,
        isHardBlock = false
    ),
    PermissionSettingRow(
        issueCode = DND_ACCESS_ISSUE_CODE,
        title = "勿扰访问权限",
        granted = snapshot.doNotDisturbAccessGranted,
        // 非采集必需：不授权只是日程静音不可用（工单 04 E3.3），不阻塞采集
        isHardBlock = false
    ),
    PermissionSettingRow(
        issueCode = "battery-optimization-missing",
        title = "电池优化豁免",
        granted = snapshot.batteryOptimizationGranted,
        isHardBlock = false
    )
)

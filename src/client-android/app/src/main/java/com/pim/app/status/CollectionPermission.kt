package com.pim.app.status

/**
 * P04：持续采集需要的六项 Android 权限。
 *
 * 勿扰访问属于日程静音的独立权限，不进入采集健康计数；录音的麦克风/通知
 * 硬门控仍由 C18 的录音流程单独处理。
 */
enum class CollectionPermissionKind(
    val issueCode: String,
    val title: String,
    val tagSuffix: String,
    val required: Boolean,
) {
    NOTIFICATION(
        issueCode = "notification-permission-missing",
        title = "通知权限",
        tagSuffix = "notification",
        required = true,
    ),
    PRECISE_LOCATION(
        issueCode = "foreground-location-missing",
        title = "前台定位",
        tagSuffix = "precise-location",
        required = true,
    ),
    BACKGROUND_LOCATION(
        issueCode = "background-location-missing",
        title = "后台定位",
        tagSuffix = "background-location",
        required = true,
    ),
    USAGE_ACCESS(
        issueCode = "usage-access-missing",
        title = "使用情况",
        tagSuffix = "usage-access",
        required = false,
    ),
    ACTIVITY_RECOGNITION(
        issueCode = "activity-recognition-missing",
        title = "运动识别",
        tagSuffix = "activity-recognition",
        required = false,
    ),
    BATTERY_OPTIMIZATION(
        issueCode = "battery-optimization-missing",
        title = "电池优化",
        tagSuffix = "battery-optimization",
        required = false,
    ),
}

enum class CollectionPermissionAccess {
    GRANTED,
    RESTRICTED,
    MISSING,
    CHECK_FAILED,
}

data class CollectionPermissionState(
    val kind: CollectionPermissionKind,
    val access: CollectionPermissionAccess,
    val restrictedReason: String? = null,
) {
    val isReady: Boolean get() = access == CollectionPermissionAccess.GRANTED

    val statusText: String
        get() = when (access) {
            CollectionPermissionAccess.GRANTED -> "已就绪"
            CollectionPermissionAccess.MISSING -> "未就绪"
            CollectionPermissionAccess.RESTRICTED -> restrictedReason
                ?.takeIf { it.isNotBlank() }
                ?.let { "受限：$it" }
                ?: "受限"
            CollectionPermissionAccess.CHECK_FAILED -> "检查失败"
        }

    val actionLabel: String
        get() = when (access) {
            CollectionPermissionAccess.GRANTED -> "查看设置"
            CollectionPermissionAccess.CHECK_FAILED -> "去设置"
            CollectionPermissionAccess.RESTRICTED,
            CollectionPermissionAccess.MISSING -> "去授权"
        }
}

fun PermissionStatusSnapshot.isGranted(kind: CollectionPermissionKind): Boolean = when (kind) {
    CollectionPermissionKind.NOTIFICATION -> notificationGranted
    CollectionPermissionKind.PRECISE_LOCATION -> preciseLocationGranted
    CollectionPermissionKind.BACKGROUND_LOCATION -> backgroundLocationGranted
    CollectionPermissionKind.USAGE_ACCESS -> usageAccessGranted
    CollectionPermissionKind.ACTIVITY_RECOGNITION -> activityRecognitionGranted
    CollectionPermissionKind.BATTERY_OPTIMIZATION -> batteryOptimizationGranted
}

fun PermissionStatusSnapshot.accessOf(kind: CollectionPermissionKind): CollectionPermissionAccess = when {
    kind in failedPermissionChecks -> CollectionPermissionAccess.CHECK_FAILED
    restrictedPermissions.containsKey(kind) -> CollectionPermissionAccess.RESTRICTED
    isGranted(kind) -> CollectionPermissionAccess.GRANTED
    else -> CollectionPermissionAccess.MISSING
}

fun PermissionStatusSnapshot.collectionPermissions(): List<CollectionPermissionState> =
    CollectionPermissionKind.entries.map { kind ->
        CollectionPermissionState(
            kind = kind,
            access = accessOf(kind),
            restrictedReason = restrictedPermissions[kind],
        )
    }

fun PermissionStatusSnapshot.unreadyCollectionPermissionCount(): Int =
    collectionPermissions().count { !it.isReady }

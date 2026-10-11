package com.pim.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 契约 `components.schemas.DaemonSourceState` 的客户端映射（I-1 / REQ-COM-02）。
 *
 * 取值与契约逐字一致（`Unknown` / `Available` / `Unavailable` / `Paused`），
 * wire 值用 `@SerialName` 固定，因此「声明一个契约外的状态」会在编译期就不可能写出来。
 * 只做类型对齐，**不**据此臆造新的采集能力 —— 现有上报点仍只发 `UNKNOWN`（见 F-B-06 观察）。
 */
@Serializable
enum class DaemonSourceState {
    @SerialName("Unknown")
    UNKNOWN,

    @SerialName("Available")
    AVAILABLE,

    @SerialName("Unavailable")
    UNAVAILABLE,

    @SerialName("Paused")
    PAUSED,
}

@Serializable
data class DaemonHeartbeatRequest(
    val deviceId: String,
    val daemonKind: String,
    val version: String,
    val serverUrl: String,
    val lastSuccessfulUploadAt: String? = null,
    val lastAttemptedUploadAt: String? = null,
    val lastError: String? = null,
    val uploadQueueCount: Int? = null,
    val activityWatchState: DaemonSourceState = DaemonSourceState.UNKNOWN,
    val keyStatsState: DaemonSourceState = DaemonSourceState.UNKNOWN,
    val collectionPaused: Boolean = false,
    val statusJson: String = "{}"
)

@Serializable
data class DaemonHeartbeatDto(
    val deviceId: String,
    val daemonKind: String,
    val version: String,
    val serverUrl: String,
    val lastSuccessfulUploadAt: String? = null,
    val lastAttemptedUploadAt: String? = null,
    val lastError: String? = null,
    val uploadQueueCount: Int? = null,
    val activityWatchState: DaemonSourceState = DaemonSourceState.UNKNOWN,
    val keyStatsState: DaemonSourceState = DaemonSourceState.UNKNOWN,
    val collectionPaused: Boolean = false,
    val statusJson: String = "{}",
    val receivedAt: String
)

package com.pim.app.nativework

import com.pim.core.contract.OperationConfirmationDto
import java.time.OffsetDateTime

/** UI-safe projection; free-form server text and private identifiers never escape. */
data class NativeOperationConfirmation(
    val id: String,
    val actionLabel: String,
    val status: Int,
    val riskLevel: Int,
    val createdAt: String,
    val expiresAt: String,
    val requiresSecondLevelConfirmation: Boolean,
    val requiresStrictConfirmation: Boolean,
)

fun nativeOperationConfirmation(dto: OperationConfirmationDto): NativeOperationConfirmation =
    NativeOperationConfirmation(
        id = dto.id,
        actionLabel = confirmationActionLabel(dto.operationType),
        status = dto.status,
        riskLevel = dto.riskLevel,
        createdAt = safeConfirmationTimestamp(dto.createdAt),
        expiresAt = safeConfirmationTimestamp(dto.expiresAt),
        requiresSecondLevelConfirmation = dto.requiresSecondLevelConfirmation == true,
        requiresStrictConfirmation = dto.requiresStrictConfirmation == true,
    )

private fun confirmationActionLabel(operationType: String): String = when (operationType) {
    "outlook.writeback" -> "写入 Outlook 日历"
    "calendar.outlook.core_diff" -> "处理 Outlook 日历变更"
    "calendar.ai_placeholder.confirm" -> "确认 AI 日程建议"
    "outlook.stop_sync" -> "停止 Outlook 同步"
    "data-center.restore" -> "恢复已审阅的数据"
    else -> "其他待确认操作"
}

private fun safeConfirmationTimestamp(raw: String): String =
    runCatching { OffsetDateTime.parse(raw).toInstant().toString() }
        .getOrDefault("未知时间")

fun confirmationStatusLabel(status: Int): String = when (status) {
    0 -> "待确认"
    1 -> "已确认"
    2 -> "已拒绝"
    3 -> "已过期"
    4 -> "已执行"
    else -> "未知状态 ($status)"
}

fun confirmationRiskLabel(risk: Int): String = when (risk) {
    0 -> "低风险"
    1 -> "中风险"
    2 -> "高风险"
    10 -> "L0 自动产物"
    11 -> "L1 低风险动作"
    12 -> "L2 事实变更"
    13 -> "L3 外部写入"
    14 -> "L4 批量或破坏性治理"
    else -> "未知风险 ($risk)"
}

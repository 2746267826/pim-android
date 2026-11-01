package com.pim.app.nativework

import com.pim.core.contract.OperationConfirmationDto

/** UI-safe projection; raw payload, previews and private object identifiers never escape. */
data class NativeOperationConfirmation(
    val id: String,
    val summary: String,
    val operationType: String,
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
        summary = safeConfirmationSummary(dto.summary),
        operationType = dto.operationType
            .replace(Regex("[^A-Za-z0-9_.-]"), "")
            .take(64)
            .ifBlank { "unknown" },
        status = dto.status,
        riskLevel = dto.riskLevel,
        createdAt = dto.createdAt,
        expiresAt = dto.expiresAt,
        requiresSecondLevelConfirmation = dto.requiresSecondLevelConfirmation == true,
        requiresStrictConfirmation = dto.requiresStrictConfirmation == true,
    )

private fun safeConfirmationSummary(raw: String): String = raw
    .replace(Regex("(?i)\\b(bearer\\s+)\\S+"), "${'$'}1[已隐藏]")
    .replace(
        Regex("(?i)\\b(graphEventId|changeKey|access_token|refresh_token|token)\\b\\s*[:=]\\s*[^\\s,;]+"),
        "${'$'}1=[已隐藏]",
    )
    .replace(Regex("(?i)https?://\\S+"), "[链接已隐藏]")
    .replace(
        Regex("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b"),
        "[标识符]",
    )
    .replace(Regex("\\b[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b"), "[邮箱]")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(160)
    .ifBlank { "无可安全显示的摘要" }

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

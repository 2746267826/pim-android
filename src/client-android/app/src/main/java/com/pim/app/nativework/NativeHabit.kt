package com.pim.app.nativework

import com.pim.core.contract.HabitRoutineDto

/** UI projection excludes free-form descriptions and source metadata. */
data class NativeHabit(
    val id: String,
    val title: String,
    val cadence: Int,
    val status: String,
)

fun nativeHabit(dto: HabitRoutineDto): NativeHabit = NativeHabit(
    id = dto.id,
    title = dto.title
        .replace(Regex("[\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(120)
        .ifBlank { "未命名习惯" },
    cadence = dto.cadence,
    status = dto.status
        .replace(Regex("[\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(40)
        .ifBlank { "unknown" },
)

fun habitCadenceLabel(cadence: Int): String = when (cadence) {
    0 -> "每日"
    1 -> "每周"
    2 -> "每月"
    3 -> "自定义"
    else -> "未知频率 ($cadence)"
}

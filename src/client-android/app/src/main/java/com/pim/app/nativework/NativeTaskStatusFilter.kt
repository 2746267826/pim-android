package com.pim.app.nativework

/**
 * R04: the task list accepts repeated `status` values as an OR set.
 *
 * Keep these rules aligned with the server: known values are matched exactly
 * (case and surrounding whitespace included), duplicates are dropped, and an
 * omitted or all-blank filter means "no status filter". A mix of blank and
 * valid values, a comma-separated value, or any unknown value is rejected as a
 * whole instead of being partially applied.
 */
object NativeTaskStatusFilter {
    const val NEEDS_ACTION = "NEEDS-ACTION"
    const val IN_PROCESS = "IN-PROCESS"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"

    val all: List<String> = listOf(NEEDS_ACTION, IN_PROCESS, COMPLETED, CANCELLED)

    fun label(status: String): String = when (status) {
        NEEDS_ACTION -> "待处理"
        IN_PROCESS -> "进行中"
        COMPLETED -> "已完成"
        CANCELLED -> "已取消"
        else -> status
    }

    /**
     * Returns the normalized OR set, or an empty list when the user wants the
     * unfiltered list. Throws [NativeTaskStatusFilterException] when any input
     * would make the server reject the whole request.
     */
    fun normalize(statuses: Iterable<String>): List<String> {
        val values = statuses.toList()
        if (values.isEmpty()) return emptyList()

        val nonBlank = values.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()
        if (nonBlank.size != values.size) throw NativeTaskStatusFilterException.mixedBlank()

        nonBlank.forEach { status ->
            if (status.contains(',')) throw NativeTaskStatusFilterException.commaSeparated(status)
            if (status !in all) throw NativeTaskStatusFilterException.unknown(status)
        }
        return nonBlank.distinct()
    }
}

class NativeTaskStatusFilterException internal constructor(
    message: String,
    val status: String? = null,
) : IllegalArgumentException(message) {
    companion object {
        internal fun mixedBlank() = NativeTaskStatusFilterException(
            "任务状态筛选不能混用空值与有效状态；请只保留需要的状态。"
        )

        internal fun commaSeparated(status: String) = NativeTaskStatusFilterException(
            "任务状态只能逐个多选，不能用逗号拼接：$status",
            status,
        )

        internal fun unknown(status: String) = NativeTaskStatusFilterException(
            "未知任务状态「$status」；可选值：${NativeTaskStatusFilter.all.joinToString("、")}",
            status,
        )
    }
}

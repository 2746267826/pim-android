package com.pim.app.ui.shell

/**
 * 嵌入 web 工作台的入口清单（工单 03 D2.3）。
 *
 * 安卓仓**没有原生任务界面**：底部的「任务」按钮直接让 WebView 打开 web 工作台的
 * `/tasks` 路由，任务状态（含「进行中」）与任务计时都由 pim-web 承载。
 * 这里把入口提成可测常量 —— 入口被改名/删掉时会有单测变红，而不是等到人工点进 app 才发现。
 */
object PimShellRoutes {
    const val Today = "/today"
    const val Tasks = "/tasks"
    const val Calendar = "/calendar"
    const val Reports = "/reports"
    const val Sync = "/sync"
    const val DataCenter = "/data-center"
    const val Confirmations = "/confirmations"

    /** 展示名 → web 路由（顺序即底部按钮顺序） */
    val all: List<Pair<String, String>> = listOf(
        "今日" to Today,
        "任务" to Tasks,
        "日历" to Calendar,
        "报告" to Reports,
        "Outlook" to Sync,
        "Data Center" to DataCenter,
        "确认" to Confirmations,
    )
}

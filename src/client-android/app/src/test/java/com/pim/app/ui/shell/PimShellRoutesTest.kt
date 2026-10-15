package com.pim.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 03 D2.3 / AC-7：安卓侧**没有原生任务界面**，「任务」入口打开的是嵌入 web 工作台的
 * `/tasks` 路由。这里把这条链路钉在单测里 —— 入口被改名、被删掉、或路由不再指向 web 工作台，
 * 都会在这里变红，而不是等人工点进 app 才发现。
 */
class PimShellRoutesTest {

    @Test
    fun `任务入口指向 web 工作台的 slash-tasks 路由`() {
        val entry = PimShellRoutes.all.firstOrNull { it.first == "任务" }
        assertEquals("安卓仓没有原生任务界面，「任务」必须指向 web 工作台路由", "/tasks", entry?.second)
    }

    @Test
    fun `所有入口都是 web 工作台绝对路由且不重复`() {
        assertTrue("入口清单不能为空", PimShellRoutes.all.isNotEmpty())
        PimShellRoutes.all.forEach { (label, route) ->
            assertTrue("入口「$label」的标签不能为空", label.isNotBlank())
            assertTrue("入口「$label」的路由必须是绝对路径：$route", route.startsWith("/"))
        }
        val routes = PimShellRoutes.all.map { it.second }
        assertEquals("入口路由不能重复：$routes", routes.size, routes.toSet().size)
    }

    @Test
    fun `任务与今日两个主入口都在清单里`() {
        val labels = PimShellRoutes.all.map { it.first }
        assertTrue("缺少「今日」入口：$labels", labels.contains("今日"))
        assertTrue("缺少「任务」入口：$labels", labels.contains("任务"))
    }
}

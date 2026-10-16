package com.pim.app.ui.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工单 03 D2.3 / AC-7：安卓侧**没有原生任务界面**，「任务」入口打开的是嵌入 web 工作台的
 * `/tasks` 路由。这里把这条链路钉在单测里 —— 入口被改名、被删掉、或路由不再指向 web 工作台，
 * 都会在这里变红，而不是等人工点进 app 才发现。
 *
 * 复审 C2 补强（此前这三条断言是「自己钉自己」：只证明 `PimShellRoutes.Tasks` 等于同文件里的字面量）：
 *  - [壳必须消费集中入口清单而不是自写列表]：钉住**消费关系** —— `PimShellScreen` 必须真的用
 *    `PimShellRoutes.all` 渲染入口，而不是另写一份列表；
 *  - [任务入口常量喂给 WebView URL 构造后落在受信 web 工作台路径上]：钉住**行为** —— 把该常量
 *    喂给真实的 `buildPimWebUrl` / `matchesTrustedRoute`，断言落点是 web 工作台的 `/tasks`
 *    且只有受信源被承认（对应 D2.3(a)「WebView 路径可用」）。
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

    @Test
    fun `壳必须消费集中入口清单而不是自写列表`() {
        val shell = shellSource()
        assertTrue(
            "PimShellScreen 必须通过 PimShellRoutes.all 渲染入口；" +
                "若改回自写列表，底部「任务」按钮与 web 工作台 /tasks 就脱钩，而旧断言仍会通过",
            shell.contains("PimShellRoutes.all")
        )
    }

    @Test
    fun `任务入口常量喂给 WebView URL 构造后落在受信 web 工作台路径上`() {
        val server = "https://pim.example/api/v1/"

        // 独立字面量：不引用实现里的任何拼接规则
        assertEquals(
            "「任务」入口必须落在 web 工作台的 /tasks 上",
            "https://pim.example/tasks",
            buildPimWebUrl(server, PimShellRoutes.Tasks)
        )
        assertTrue(
            "受信判定必须承认「任务」入口的落点",
            matchesTrustedRoute("https://pim.example/tasks", server, PimShellRoutes.Tasks)
        )
        assertFalse(
            "受信判定不得承认其它源的同一路径",
            matchesTrustedRoute("https://evil.example/tasks", server, PimShellRoutes.Tasks)
        )
    }

    @Test
    fun `清单里每个入口都能构造出受信源上的 URL`() {
        val server = "https://pim.example/api/v1/"
        PimShellRoutes.all.forEach { (label, route) ->
            val url = buildPimWebUrl(server, route)
            assertTrue("入口「$label」($route) 未落在受信源上：$url", matchesTrustedRoute(url, server, route))
        }
    }

    /** `PimShellActivity.kt` 源码（单测工作目录是 app 模块根，向上找到仓库内的相对路径） */
    private fun shellSource(): String = sourceFile(
        "src", "main", "java", "com", "pim", "app", "ui", "shell", "PimShellActivity.kt"
    ).readText()

    private fun sourceFile(vararg parts: String): File {
        var current: File? = File("").canonicalFile
        while (current != null) {
            val candidate = parts.fold(current) { dir, part -> dir.resolve(part) }
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        error("Could not find ${parts.joinToString(File.separator)}")
    }
}

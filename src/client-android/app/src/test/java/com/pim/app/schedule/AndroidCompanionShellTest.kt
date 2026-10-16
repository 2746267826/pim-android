package com.pim.app.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCompanionShellTest {
    @Test
    fun shellSourcesDeclareWebViewPermissionCenterAndRoutes() {
        val manifest = repoFile("src", "main", "AndroidManifest.xml").readText()
        val shell = repoFile("src", "main", "java", "com", "pim", "app", "ui", "shell", "PimShellActivity.kt").readText()
        val webView = repoFile("src", "main", "java", "com", "pim", "app", "ui", "shell", "PimWebViewScreen.kt").readText()
        val permissions = repoFile("src", "main", "java", "com", "pim", "app", "ui", "permissions", "PermissionCenterScreen.kt").readText()
        // 工单 03 D2.3：入口路由从 PimShellActivity 抽到了 PimShellRoutes（可单测的常量对象）。
        // 复审 C3 更正：上一轮这里写成「断言意图不变」，但实际是**放宽** —— 七入口改为
        // `shell.contains(route) || webView.contains(route) || routes.contains(route)` 后，
        // 5/7 条入口只能靠 PimShellRoutes.kt 命中，`/tasks` 在 PimShellActivity.kt 里的唯一命中
        // 还是 :104 的注释（本测试用 readText() 原样扫描、不过滤注释）。
        // 现在改为钉住**消费关系 + 声明位置**：壳必须引用 PimShellRoutes.all；
        // 每个入口必须在集中清单里以带引号的字面量声明（注释命中不再算数）。
        val routes = repoFile("src", "main", "java", "com", "pim", "app", "ui", "shell", "PimShellRoutes.kt").readText()

        assertTrue(manifest.contains("POST_NOTIFICATIONS"))
        assertTrue(manifest.contains(".ui.shell.PimShellActivity"))
        assertTrue(manifest.contains("NotificationActionReceiver"))
        assertTrue(shell.contains("PermissionCenterScreen"))
        assertTrue(shell.contains("PimWebViewScreen"))
        assertTrue(permissions.contains("collection quality"))

        assertTrue(
            "PimShellActivity 必须消费集中入口清单 PimShellRoutes.all；" +
                "若改成自写列表或删掉按钮循环，底部「任务」会与 web 工作台 /tasks 脱钩",
            shell.contains("PimShellRoutes.all")
        )
        assertTrue(
            "WebView 必须用路由构造受信 URL（D2.3(a)：WebView 路径可用）",
            webView.contains("buildPimWebUrl(serverUrl, route)")
        )

        for (route in listOf("/today", "/tasks", "/calendar", "/reports", "/sync", "/data-center", "/confirmations")) {
            assertTrue(
                "$route 必须在集中清单 PimShellRoutes.kt 里以字面量声明",
                routes.contains("\"$route\"")
            )
        }
    }

    private fun repoFile(vararg parts: String): File {
        var current: File? = File("").canonicalFile
        while (current != null) {
            val candidate = parts.fold(current) { dir, part -> dir.resolve(part) }
            if (candidate.exists()) return candidate
            current = current.parentFile
        }

        error("Could not find ${parts.joinToString(File.separator)}")
    }
}

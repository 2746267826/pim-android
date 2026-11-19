package com.pim.app.location.lab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pim.app.location.service.ForegroundLocationService
import com.pim.locationtest.LabReplayScenarios
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LocationLabActivity : ComponentActivity() {
    @Inject lateinit var store: LocationLabStore
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var permissions: TextView
    private lateinit var results: TextView
    private val refresh = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(28))
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.rgb(25, 39, 56)); setPadding(0, dp(6), 0, dp(6))
            content.addView(this)
        }
        fun button(title: String, action: () -> Unit) {
            content.addView(Button(this).apply {
                text = title; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        label("PIM Dev · 定位静止联测", 23f)
        label("免登录 · 仅本机保存 · 可与正式版并存\n建议桌面静止：亮屏 5 分钟，再锁屏 5 分钟。最长运行一小时，开始新一轮会覆盖上一轮，先导出可保留。")
        button("开始静止联测") { startTest() }
        button("停止联测并保留记录") {
            if (store.requested || store.attached) {
                startService(Intent(this, ForegroundLocationService::class.java).setAction(DevLocationExperiment.ACTION_STOP))
            } else show("联测已停止，记录仍可导出")
        }
        status = label("尚未开始", 14f)
        button("复制联测报告") { copyReport() }
        button("分享完整 CSV") { shareCsv() }
        label("后台与权限", 18f)
        permissions = label("")
        button("授权通知与运动识别（可选）") { requestOptionalPermissions() }
        button("应用设置（后台定位与电池）") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("查看电池优化设置") {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { show("请在系统应用设置中查看电池限制") }
        }
        label("算法回归", 18f)
        button("运行六项合成回归") {
            val outcome = LabReplayScenarios.run()
            results.text = "合成数据（并非真实运动实测）\n" + outcome.joinToString("\n") {
                "${if (it.passed) "PASS" else "FAIL"} · ${it.name}\n${it.detail}"
            }
        }
        results = label("合成回归可离线运行，不影响正在进行的实时联测。")
        label("证据说明", 18f)
        label("定位和加速度计、陀螺仪、线性加速度采用同一单调时钟。每个定位回调及约 5 秒一次的心跳记录近 5 秒惯性窗口、首样本等待、覆盖时长、最大缺口、投递延迟和亮灭屏状态。\n\n惯性波动较小不代表静止；采样不足显示证据不足，不用于否决匀速高速。定位不足时维持证据不足，测试不改变正式高速策略。\n\n服务由 PIM 现有定位前台服务宿主承载，测试采用固定 2.5 秒定位请求。系统重建服务时另起一段并保留此前 CSV；强制停止不会被当作正常后台存活。此模式不检验生产的闹钟恢复与同步调度。\n\n经纬度只在内存参与位移计算，报告与 CSV 不包含坐标、账号或设备唯一标识，不加入业务上传队列。系统定位提供方可能联网辅助定位。")
    }

    override fun onResume() {
        super.onResume()
        safeEvent("activity_foreground")
        main.post(refresh)
        if (store.requested && !store.attached && granted(Manifest.permission.ACCESS_FINE_LOCATION)) launchService()
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        safeEvent("activity_background")
        super.onPause()
    }

    private fun startTest() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 40)
        } else launchService()
    }

    private fun launchService() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            show("需要先授予精确定位权限")
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, ForegroundLocationService::class.java).setAction(DevLocationExperiment.ACTION_START))
        } catch (error: Exception) { show("启动失败：${error.javaClass.simpleName}") }
    }

    private fun requestOptionalPermissions() {
        val pending = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (pending.isEmpty()) show("可选权限已授予") else requestPermissions(pending.toTypedArray(), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 40) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) launchService() else show("需要选择精确定位才能开始；未授权不会采样")
        }
        render()
    }

    private fun render() {
        val snapshot = com.pim.locationtest.DemoRuntime.snapshot()
        val phase = when {
            store.attached -> "采样中；离开页面与锁屏后继续记录"
            store.requested -> "等待服务恢复；已有记录仍可导出"
            store.csvFile.exists() -> "已停止；本地记录已保留"
            else -> "尚未开始"
        }
        status.text = "$phase\n${store.latestStatus}" + (snapshot.issue?.let { "\n$it" } ?: "")
        permissions.text = permissionSummary()
    }

    private fun permissionSummary(): String {
        val power = getSystemService(PowerManager::class.java)
        return "包名：$packageName\n精确定位：${yes(granted(Manifest.permission.ACCESS_FINE_LOCATION))}" +
            "　后台定位：${yes(Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))}" +
            "\n通知：${yes(Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))}" +
            "　运动识别：${yes(Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION))}" +
            "\n已豁免电池优化：${yes(power.isIgnoringBatteryOptimizations(packageName))}\n通知与运动权限不阻止本次惯性联测。"
    }

    private fun copyReport() {
        try {
            val report = permissionSummary() + "\n\n" + store.report() + "\n\n" + results.text
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PIM Dev 静止联测报告", report))
            show("联测报告已复制；分享 CSV 可保留完整时间轴")
        } catch (error: Exception) { show("复制失败：${error.javaClass.simpleName}") }
    }

    private fun shareCsv() {
        try {
            if (!store.csvFile.exists()) { show("请先开始一轮实时联测"); return }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val export = File(store.csvFile.parentFile, "location-lab-$stamp.csv")
            synchronized(store) { store.csvFile.copyTo(export, overwrite = true) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", export)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "脱敏定位联测 CSV", uri)
            }, "分享本地联测 CSV"))
        } catch (error: Exception) { show("导出失败：${error.javaClass.simpleName}") }
    }

    private fun safeEvent(detail: String) {
        runCatching { store.event(SystemClock.elapsedRealtime(), detail) }.onFailure { show("记录写入失败：${it.javaClass.simpleName}") }
    }
    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun yes(value: Boolean) = if (value) "已授予" else "未授予"
    private fun show(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}

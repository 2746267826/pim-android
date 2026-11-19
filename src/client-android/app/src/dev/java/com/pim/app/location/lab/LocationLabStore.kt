package com.pim.app.location.lab

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.pim.locationtest.DemoRuntime
import com.pim.locationtest.SensorWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Only redacted diagnostic rows reach disk. LocationSample/coordinates never enter this store. */
@Singleton
class LocationLabStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("local-location-lab", Context.MODE_PRIVATE)
    val csvFile: File get() = File(context.filesDir, "diagnostics/exports/location-lab-current.csv")
    val requested: Boolean get() = preferences.getBoolean("requested", false)
    val origin: Long get() = preferences.getLong("origin", 0)
    val generation: Int get() = preferences.getInt("generation", 0)
    val sameBoot: Boolean get() = preferences.getInt("boot_count", -1) == Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    @Volatile var attached = false
        private set
    @Volatile var latestStatus = preferences.getString("stop_reason", null)?.let { "上次停止：$it。记录可导出。" }
        ?: "尚未开始；无需登录。"
        private set

    @Synchronized
    fun begin(now: Long) {
        csvFile.parentFile!!.mkdirs()
        csvFile.writeText(HEADERS.joinToString(",") + "\n")
        preferences.edit().clear().putBoolean("requested", true).putLong("origin", now)
            .putInt("boot_count", Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)).commit()
        latestStatus = "正在启动本地联测"
        append("session_start", now, mapOf("detail" to "live; coordinates_in_memory_only; no_business_upload"))
    }

    @Synchronized
    fun attach(now: Long) {
        val previousHeartbeat = preferences.getLong("last_heartbeat", 0)
        preferences.edit().putInt("generation", generation + 1).commit()
        attached = true
        append("service_attach", now, mapOf(
            "heartbeat_gap_ms" to if (previousHeartbeat > 0) (now - previousHeartbeat).toString() else "",
            "detail" to "ForegroundLocationService; algorithm_and_sensor_windows_reset_for_this_segment"
        ))
    }

    @Synchronized
    fun detach(now: Long) {
        attached = false
        append("service_destroy", now, mapOf("detail" to if (requested) "unexpected; restart_requested" else "stopped"))
    }

    @Synchronized
    fun stop(now: Long, reason: String) {
        preferences.edit().putBoolean("requested", false).putString("stop_reason", reason).commit()
        append("session_stop", now, mapOf("detail" to reason))
        attached = false
        latestStatus = "已停止：$reason。已保存的记录可复制或分享。"
    }

    @Synchronized
    fun record(now: Long, kind: String, windows: Map<String, SensorWindow>, sensorStatus: String, locationEventAt: Long? = null) {
        val runtime = DemoRuntime.snapshot()
        val fix = runtime.latest
        val columns = linkedMapOf(
            "sensor_registration" to sensorStatus,
            "location_elapsed_ms" to locationEventAt?.minus(origin)?.toString().orEmpty(),
            "latest_fix_age_ms" to fix?.sample?.elapsedRealtimeMillis?.let { (now - it).toString() }.orEmpty(),
            "provider" to fix?.sample?.provider.orEmpty(),
            "speed_kmh" to fix?.sample?.speedMetersPerSecond?.times(3.6)?.number().orEmpty(),
            "speed_accuracy_mps" to fix?.sample?.speedAccuracyMetersPerSecond?.toDouble()?.number().orEmpty(),
            "horizontal_accuracy_m" to fix?.sample?.horizontalAccuracyMeters?.toDouble()?.number().orEmpty(),
            "is_mock" to fix?.sample?.isMock?.toString().orEmpty(),
            "location_available" to runtime.locationAvailable?.toString().orEmpty(),
            "original_mode" to runtime.legacyMode.name,
            "candidate_mode" to runtime.proposedMode.name,
            "candidate_reason" to runtime.proposedReason,
            "displacement_m" to runtime.displacementMeters.number(),
            "derived_speed_kmh" to runtime.derivedSpeedMetersPerSecond?.times(3.6)?.number().orEmpty(),
            "position_evidence_fixes" to runtime.evidenceFixCount.toString(),
            "sample_count_segment" to runtime.sampleCount.toString(),
            "cached_samples_ignored" to runtime.preTestSampleCount.toString()
        )
        for (name in SENSOR_NAMES) {
            val window = windows[name] ?: SensorWindow()
            columns["${name}_count"] = window.count.toString()
            columns["${name}_total_count"] = window.totalCount.toString()
            columns["${name}_coverage_ms"] = window.coveredMillis.toString()
            columns["${name}_first_delay_ms"] = window.firstDelayMillis?.toString().orEmpty()
            columns["${name}_age_ms"] = window.ageMillis?.toString().orEmpty()
            columns["${name}_max_gap_ms"] = window.maxGapMillis.toString()
            columns["${name}_window_max_gap_ms"] = window.windowMaxGapMillis.toString()
            columns["${name}_max_delivery_delay_ms"] = window.maxDeliveryDelayMillis.toString()
            columns["${name}_std"] = window.standardDeviation?.number().orEmpty()
            columns["${name}_rms"] = window.rms?.number().orEmpty()
            columns["${name}_evidence"] = window.evidence.name
        }
        if (kind == "heartbeat") {
            val last = preferences.getLong("last_heartbeat", 0)
            columns["heartbeat_gap_ms"] = if (last > 0) (now - last).toString() else ""
            preferences.edit().putLong("last_heartbeat", now).apply()
        }
        append(kind, now, columns)
        latestStatus = buildString {
            append("本段定位 ${runtime.sampleCount} 条 · 服务第 $generation 段\n")
            fun label(mode: String) = when (mode) {
                "Inactive" -> "未激活"; "Accumulating" -> "累计确认中"; "Verifying" -> "验证中"; else -> "高速已确认"
            }
            append("基线：${label(runtime.legacyMode.name)}　候选：${label(runtime.proposedMode.name)}\n${runtime.proposedReason}\n")
            for (name in SENSOR_NAMES) {
                val window = windows[name] ?: SensorWindow()
                val label = when (name) { "accel" -> "加速度"; "gyro" -> "陀螺仪"; else -> "线性加速度" }
                val evidence = when (window.evidence.name) { "Quiet" -> "波动较小"; "Varying" -> "存在波动"; else -> "证据不足" }
                append("$label：$evidence，近 5 秒覆盖 ${window.coveredMillis} ms，最新年龄 ${window.ageMillis ?: "缺失"} ms\n")
            }
            append("波动较小不代表静止；没有回调也不代表静止。")
        }
    }

    @Synchronized
    fun event(now: Long, detail: String) = append("lifecycle", now, mapOf("detail" to detail))

    @Synchronized
    private fun append(kind: String, now: Long, values: Map<String, String>) {
        if (!csvFile.exists()) return
        val power = context.getSystemService(PowerManager::class.java)
        val row = values + mapOf(
            "row_kind" to kind, "elapsed_ms" to (now - origin).toString(),
            "uptime_ms" to SystemClock.uptimeMillis().toString(), "service_generation" to generation.toString(),
            "screen_interactive" to power.isInteractive.toString(), "device_idle" to power.isDeviceIdleMode.toString(),
            "power_save" to power.isPowerSaveMode.toString(),
            "battery_optimization_exempt" to power.isIgnoringBatteryOptimizations(context.packageName).toString()
        )
        csvFile.appendText(HEADERS.joinToString(",") { csv(row[it].orEmpty()) } + "\n")
    }

    @Synchronized
    fun report(): String = buildString {
        appendLine("PIM Dev 定位与惯性传感器联测")
        appendLine("joint_report_version=1")
        appendLine("package=${context.packageName}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}; android=${Build.VERSION.RELEASE}; sdk=${Build.VERSION.SDK_INT}")
        appendLine("source=live; service=ForegroundLocationService; location_request_ms=2500; sensor_request_us=20000")
        appendLine("requested=$requested; attached=$attached; service_generation=$generation")
        appendLine("last_heartbeat_elapsed_ms=${preferences.getLong("last_heartbeat", 0).takeIf { it > 0 }?.minus(origin) ?: "缺失"}")
        appendLine("stop_reason=${preferences.getString("stop_reason", "无")}")
        appendLine("$latestStatus")
        appendLine("坐标仅在内存计算；本报告与 CSV 不含坐标、累计步数、账号或设备唯一标识，不加入业务上传队列。")
        appendLine("Quiet 仅表示惯性波动较小，不否决匀速运动；Unknown 表示采样不足或过期。")
        appendLine("合成回归不能代替运动实测；本模式不验证生产闹钟恢复与同步调度。")
        appendLine("## 同步时间轴（elapsed_ms 为收到/记录时刻，location_elapsed_ms 为定位事件时刻；窗口截至本行记录时刻）")
        val contents = if (csvFile.exists()) csvFile.readText() else "尚无采样"
        if (contents.length > 180_000) {
            appendLine("clipboard_truncated=true; 完整内容请使用分享 CSV。以下保留最近记录。")
            appendLine(HEADERS.joinToString(","))
            append(contents.takeLast(175_000).substringAfter('\n'))
        } else append(contents)
    }

    private fun Double.number() = if (isFinite()) String.format(Locale.US, "%.3f", this) else ""
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ')}\""

    companion object {
        const val MAX_SESSION_MILLIS = 60 * 60_000L
        val SENSOR_NAMES = listOf("accel", "gyro", "linear")
        val HEADERS = listOf(
            "row_kind", "elapsed_ms", "uptime_ms", "service_generation", "screen_interactive", "device_idle", "power_save",
            "battery_optimization_exempt", "heartbeat_gap_ms", "sensor_registration", "location_elapsed_ms", "latest_fix_age_ms",
            "provider", "speed_kmh", "speed_accuracy_mps", "horizontal_accuracy_m", "is_mock", "location_available",
            "original_mode", "candidate_mode", "candidate_reason", "displacement_m", "derived_speed_kmh", "position_evidence_fixes",
            "sample_count_segment", "cached_samples_ignored"
        ) + SENSOR_NAMES.flatMap { name -> listOf("count", "total_count", "coverage_ms", "first_delay_ms", "age_ms", "max_gap_ms",
            "window_max_gap_ms", "max_delivery_delay_ms", "std", "rms", "evidence").map { "${name}_$it" } } + "detail"
    }
}

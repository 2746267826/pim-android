package com.pim.locationtest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Builds a shareable, coordinate-redacted report using only local Android APIs. */
object DeviceDiagnostics {
    fun collect(context: Context, onReady: (String) -> Unit) {
        val snapshot = DemoRuntime.snapshot()
        val reportHeader = buildLocalReport(context, snapshot)
        val supplements = linkedMapOf<String, String>()
        val lock = Any()
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var pending = 3

        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            val result = synchronized(lock) {
                buildString {
                    append(reportHeader)
                    supplements.forEach { (title, value) ->
                        appendLine()
                        appendLine("## $title")
                        appendLine(value)
                    }
                }
            }
            onReady(result)
        }

        fun complete(title: String, value: String) {
            val allDone = synchronized(lock) {
                supplements[title] = value
                pending--
                pending <= 0
            }
            if (allDone) finish()
        }

        val timeout = Runnable {
            synchronized(lock) {
                supplements.putIfAbsent("异步检测", "部分 Google Play Services 或传感器运行时探针未在时限内返回。")
            }
            finish()
        }
        main.postAtTime(timeout, TIMEOUT_TOKEN, SystemClock.uptimeMillis() + ASYNC_TIMEOUT_MILLIS)

        try {
            SensorRuntimeProbe.run(context) { result -> complete("传感器运行时探针", result) }
        } catch (error: Exception) {
            complete("传感器运行时探针", "调用失败：${error.javaClass.simpleName}")
        }

        try {
            val availabilityTask = LocationServices.getFusedLocationProviderClient(context).locationAvailability
            availabilityTask.addOnCompleteListener { task ->
                val value = when {
                    !task.isSuccessful -> "查询失败：${task.exception?.javaClass?.simpleName ?: "unknown"}"
                    task.result == null -> "系统未返回 LocationAvailability"
                    task.result!!.isLocationAvailable -> "可用"
                    else -> "当前不可用"
                }
                complete("Fused LocationAvailability", value)
            }
        } catch (error: Exception) {
            complete("Fused LocationAvailability", "调用失败：${error.javaClass.simpleName}")
        }

        try {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnCompleteListener { task ->
                    val value = when {
                        !task.isSuccessful -> "查询失败：${task.exception?.javaClass?.simpleName ?: "unknown"}"
                        task.result == null -> "无缓存位置"
                        else -> formatLocation(task.result!!, SystemClock.elapsedRealtime())
                    }
                    complete("Fused lastLocation", value)
                }
        } catch (error: Exception) {
            complete("Fused lastLocation", "调用失败：${error.javaClass.simpleName}")
        }
    }

    fun copyToClipboard(activity: Activity, onCopied: () -> Unit, onError: (String) -> Unit) {
        collect(activity) { report ->
            try {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("定位设备检测报告", report))
                onCopied()
            } catch (error: Exception) {
                onError("复制失败：${error.javaClass.simpleName}")
            }
        }
    }

    private fun buildLocalReport(context: Context, snapshot: DemoSnapshot): String = buildString {
        appendLine("定位设备检测报告")
        appendLine("diagnostic_report_version=$DIAGNOSTIC_REPORT_VERSION")
        appendLine("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(java.util.Date())}")
        appendLine("经纬度与设备唯一标识已省略；以下内容由本机 Android API 读取。")

        appendLine()
        appendLine("## 设备与系统")
        appendLine("manufacturer=${Build.MANUFACTURER.safeText()}")
        appendLine("brand=${Build.BRAND.safeText()}")
        appendLine("model=${Build.MODEL.safeText()}")
        appendLine("device=${Build.DEVICE.safeText()}")
        appendLine("product=${Build.PRODUCT.safeText()}")
        appendLine("android_release=${Build.VERSION.RELEASE.safeText()}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("security_patch=${Build.VERSION.SECURITY_PATCH.safeText()}")
        appendLine("build_id=${Build.ID.safeText()}")
        appendLine("supported_abis=${Build.SUPPORTED_ABIS.joinToString(",") { it.safeText() }}")
        appendLine("app_version=${appVersion(context)}")

        appendLine()
        appendLine("## 权限")
        val requested = requestedPermissions(context)
        PERMISSIONS.forEach { (label, permission) ->
            val state = when {
                permission !in requested -> "未在 Manifest 声明"
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> "已授予"
                else -> "未授予"
            }
            appendLine("$label=$state")
        }

        appendLine()
        appendLine("## 系统定位")
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        appendLine("location_enabled=${runCatching { locationManager.isLocationEnabled }.getOrElse { "unknown" }}")
        PROVIDERS.forEach { provider ->
            val exists = runCatching { locationManager.allProviders.contains(provider) }.getOrDefault(false)
            val enabled = if (exists) runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) else false
            appendLine("provider_$provider exists=$exists enabled=$enabled")
        }

        appendLine()
        appendLine("## Google Play Services")
        val gmsStatus = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrElse { Int.MIN_VALUE }
        val gmsStatusText = if (gmsStatus == Int.MIN_VALUE) {
            "检查失败"
        } else {
            val statusText = runCatching { GoogleApiAvailability.getInstance().getErrorString(gmsStatus) }
                .getOrDefault("unknown")
            "$gmsStatus ($statusText)"
        }
        appendLine("availability=$gmsStatusText")
        appendLine("package=${packageDetails(context, GMS_PACKAGE)}")

        appendLine()
        appendLine("## 传感器清单")
        val sensors = runCatching {
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
                .getSensorList(Sensor.TYPE_ALL)
                .sortedWith(compareBy<Sensor> { it.type }.thenBy { it.name })
        }.getOrDefault(emptyList())
        appendLine("sensor_count=${sensors.size}")
        if (sensors.isEmpty()) appendLine("未读取到传感器")
        sensors.forEach { sensor ->
            appendLine(
                "type=${sensor.type}(${sensorTypeName(sensor.type)})" +
                    " name=${sensor.name.safeText()} vendor=${sensor.vendor.safeText()}" +
                    " version=${sensor.version} wakeUp=${sensor.isWakeUpSensor}" +
                    " reportingMode=${sensor.reportingMode} minDelayUs=${sensor.minDelay}" +
                    " resolution=${sensor.resolution.safeNumber()} maxRange=${sensor.maximumRange.safeNumber()}" +
                    " powerMa=${sensor.power.safeNumber()}"
            )
        }

        appendLine()
        appendLine("## 当前 App 采样状态")
        appendLine("source=${snapshot.source.safeText()}")
        appendLine("running=${snapshot.running}")
        appendLine("sample_count=${snapshot.sampleCount}")
        appendLine("retained_samples=${snapshot.records.size}")
        appendLine("pre_test_cached_samples_ignored=${snapshot.preTestSampleCount}")
        appendLine("older_samples_omitted=${snapshot.droppedHistoryCount}")
        appendLine("fused_availability_callback=${snapshot.locationAvailable?.toString() ?: "尚未回调"}")
        appendLine("service_issue=${snapshot.issue?.safeText() ?: "无"}")
        appendLine("original_mode=${snapshot.legacyMode}")
        appendLine("candidate_mode=${snapshot.proposedMode}")
        appendLine("candidate_reason=${snapshot.proposedReason.safeText()}")
        appendLine("candidate_displacement_m=${snapshot.displacementMeters.safeNumber()}")
        appendLine("candidate_derived_speed_mps=${snapshot.derivedSpeedMetersPerSecond?.safeNumber() ?: "缺失"}")

        appendLine()
        appendLine("## 实时字段覆盖率与速度矛盾统计")
        val samples = snapshot.records.map { it.sample }
        appendLine("sample_count=${samples.size}")
        appendLine("speed_present=${samples.count { it.speedMetersPerSecond != null }}")
        appendLine("speed_accuracy_present=${samples.count { it.speedAccuracyMetersPerSecond != null }}")
        appendLine("horizontal_accuracy_present=${samples.count { it.horizontalAccuracyMeters != null }}")
        appendLine("horizontal_accuracy_lt_30m=${samples.count { it.horizontalAccuracyMeters?.let { accuracy -> accuracy.isFinite() && accuracy < 30f } == true }}")
        appendLine("altitude_present=${samples.count { it.hasAltitude }}")
        appendLine("vertical_accuracy_present=${samples.count { it.verticalAccuracyMeters != null }}")
        appendLine("bearing_present=${samples.count { it.hasBearing }}")
        appendLine("bearing_accuracy_present=${samples.count { it.bearingAccuracyDegrees != null }}")
        appendLine("mock_location_count=${samples.count { it.isMock }}")
        val providers = samples.groupingBy { it.provider.safeText() }.eachCount().toSortedMap()
        appendLine("provider_distribution=${if (providers.isEmpty()) "无样本" else providers.entries.joinToString("; ") { "${it.key}:${it.value}" }}")
        val gpsFastSamples = snapshot.records.filter {
            it.sample.speedMetersPerSecond?.let { speed -> speed * 3.6f >= 8f } == true
        }
        appendLine("gps_speed_ge_8kmh=${gpsFastSamples.size}")
        appendLine("gps_speed_ge_8kmh_with_speed_accuracy=${gpsFastSamples.count { it.sample.speedAccuracyMetersPerSecond != null }}")
        appendLine("gps_speed_ge_8kmh_horizontal_accuracy_lt_30m=${gpsFastSamples.count { it.sample.horizontalAccuracyMeters?.let { accuracy -> accuracy.isFinite() && accuracy < 30f } == true }}")
        appendLine("gps_speed_ge_8kmh_derived_speed_unavailable=${gpsFastSamples.count { it.derivedSpeedMetersPerSecond == null }}")
        appendLine("gps_speed_ge_8kmh_derived_speed_ge_8kmh=${gpsFastSamples.count {
            it.derivedSpeedMetersPerSecond?.let { derived -> derived * 3.6 >= 8.0 } == true
        }}")
        appendLine("gps_speed_ge_8kmh_but_derived_speed_lt_8kmh=${gpsFastSamples.count {
            it.derivedSpeedMetersPerSecond?.let { derived -> derived * 3.6 < 8.0 } == true
        }}")
        val derivedFastSamples = snapshot.records.filter {
            it.derivedSpeedMetersPerSecond?.let { derived -> derived * 3.6 >= 8.0 } == true
        }
        appendLine("gps_speed_lt_8kmh_but_derived_speed_ge_8kmh=${derivedFastSamples.count {
            it.sample.speedMetersPerSecond?.let { speed -> speed * 3.6f < 8f } == true
        }}")
        appendLine("gps_speed_missing_but_derived_speed_ge_8kmh=${derivedFastSamples.count { it.sample.speedMetersPerSecond == null }}")
        appendLine("gps_vs_derived_speed_delta_ge_5kmh=${snapshot.records.count { row ->
            val gps = row.sample.speedMetersPerSecond?.times(3.6f)?.toDouble()
            val derived = row.derivedSpeedMetersPerSecond?.times(3.6)
            gps != null && derived != null && kotlin.math.abs(gps - derived) >= 5.0
        }}")
        val intervals = snapshot.records.zipWithNext { first, second ->
            second.sample.elapsedRealtimeMillis - first.sample.elapsedRealtimeMillis
        }.filter { it > 0L }
        appendLine("callback_interval_count=${intervals.size}")
        if (intervals.isNotEmpty()) {
            appendLine("callback_interval_min_ms=${intervals.minOrNull()}")
            appendLine("callback_interval_avg_ms=${intervals.average().safeNumber()}")
            appendLine("callback_interval_max_ms=${intervals.maxOrNull()}")
        }

        appendLine()
        appendLine("## 最新实时回调字段")
        appendLine(snapshot.latest?.sample?.let(::formatSample) ?: "尚无实时回调")

        appendLine()
        appendLine("## 本次样本明细（最多保留最近 ${MAX_SAMPLE_LINES} 条；无坐标）")
        appendLine("elapsed_ms,provider,speed_mps,speed_accuracy_mps,horizontal_accuracy_m,altitude_m,vertical_accuracy_m,bearing_deg,bearing_accuracy_deg,mock,original_mode,candidate_mode,displacement_m,candidate_reason")
        snapshot.records.takeLast(MAX_SAMPLE_LINES).forEach { row ->
            val sample = row.sample
            append(sample.elapsedRealtimeMillis - (snapshot.startedAtElapsedRealtimeMillis ?: sample.elapsedRealtimeMillis)).append(',')
            append(sample.provider.csvSafe()).append(',')
            append(sample.speedMetersPerSecond?.safeNumber() ?: "").append(',')
            append(sample.speedAccuracyMetersPerSecond?.safeNumber() ?: "").append(',')
            append(sample.horizontalAccuracyMeters?.safeNumber() ?: "").append(',')
            append(sample.altitudeMeters?.safeNumber() ?: "").append(',')
            append(sample.verticalAccuracyMeters?.safeNumber() ?: "").append(',')
            append(sample.bearingDegrees?.safeNumber() ?: "").append(',')
            append(sample.bearingAccuracyDegrees?.safeNumber() ?: "").append(',')
            append(sample.isMock).append(',')
            append(row.originalMode).append(',')
            append(row.proposedMode).append(',')
            append(row.displacementMeters.safeNumber()).append(',')
            appendLine(row.proposedReason.csvSafe())
        }

        appendLine()
        appendLine("注：未读取经纬度、序列号、Android ID、电话号码或账户凭据；报告仅复制到系统剪贴板，不由本 App 上传。系统定位提供方可能使用网络辅助定位。")
    }

    private fun formatLocation(location: Location, nowElapsedRealtimeMillis: Long): String = buildString {
        val sample = location.toSample()
        appendLine("coordinates=redacted")
        appendLine("provider=${sample.provider}")
        appendLine("age_ms=${(nowElapsedRealtimeMillis - sample.elapsedRealtimeMillis).coerceAtLeast(0L)}")
        append(formatSample(sample))
    }

    private fun formatSample(sample: LocationSample): String = buildString {
        appendLine("coordinates=redacted")
        appendLine("provider=${sample.provider.safeText()}")
        appendLine("location_time_ms=${sample.locationTimeMillis ?: "缺失"}")
        appendLine("elapsed_realtime_ms=${sample.elapsedRealtimeMillis}")
        appendLine("horizontal_accuracy_m=${sample.horizontalAccuracyMeters?.safeNumber() ?: "缺失"}")
        appendLine("speed_mps=${sample.speedMetersPerSecond?.safeNumber() ?: "缺失"}")
        appendLine("speed_accuracy_mps=${sample.speedAccuracyMetersPerSecond?.safeNumber() ?: "缺失"}")
        appendLine("has_altitude=${sample.hasAltitude}")
        appendLine("altitude_m=${sample.altitudeMeters?.safeNumber() ?: "缺失"}")
        appendLine("vertical_accuracy_m=${sample.verticalAccuracyMeters?.safeNumber() ?: "缺失"}")
        appendLine("has_bearing=${sample.hasBearing}")
        appendLine("bearing_degrees=${sample.bearingDegrees?.safeNumber() ?: "缺失"}")
        appendLine("bearing_accuracy_degrees=${sample.bearingAccuracyDegrees?.safeNumber() ?: "缺失"}")
        appendLine("is_mock=${sample.isMock}")
        appendLine("elapsed_realtime_uncertainty_ms=${sample.elapsedRealtimeUncertaintyMillis?.safeNumber() ?: "缺失"}")
    }

    private fun Location.toSample(): LocationSample = LocationSample(
        elapsedRealtimeMillis = if (elapsedRealtimeNanos > 0L) elapsedRealtimeNanos / 1_000_000L else SystemClock.elapsedRealtime(),
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMetersPerSecond = if (hasSpeed()) speed else null,
        speedAccuracyMetersPerSecond = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
        provider = provider ?: "unknown",
        locationTimeMillis = time,
        hasAltitude = hasAltitude(),
        altitudeMeters = altitude.takeIf { hasAltitude() },
        verticalAccuracyMeters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()) verticalAccuracyMeters else null,
        hasBearing = hasBearing(),
        bearingDegrees = bearing.takeIf { hasBearing() },
        bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) bearingAccuracyDegrees else null,
        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider,
        elapsedRealtimeUncertaintyMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasElapsedRealtimeUncertaintyNanos()) {
            elapsedRealtimeUncertaintyNanos / 1_000_000.0
        } else null
    )

    private fun appVersion(context: Context): String = runCatching {
        val info = packageInfo(context, context.packageName)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName ?: "unknown"} ($code)"
    }.getOrDefault("unknown")

    private fun packageDetails(context: Context, packageName: String): String = runCatching {
        val info = packageInfo(context, packageName)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "installed=true enabled=${info.applicationInfo?.enabled} version=${info.versionName ?: "unknown"} code=$code"
    }.getOrElse { "installed=false (${it.javaClass.simpleName})" }

    private fun requestedPermissions(context: Context): Set<String> = runCatching {
        packageInfo(context, context.packageName).requestedPermissions?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    private fun packageInfo(context: Context, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }

    private fun sensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnetic_field"
        Sensor.TYPE_ORIENTATION -> "orientation"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "relative_humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "magnetic_field_uncalibrated"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyroscope_uncalibrated"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "significant_motion"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "geomagnetic_rotation_vector"
        Sensor.TYPE_HEART_RATE -> "heart_rate"
        else -> "other"
    }

    private fun Float.safeNumber(): String = if (isFinite()) "%.3f".format(Locale.US, this) else "invalid"
    private fun Double.safeNumber(): String = if (isFinite()) "%.3f".format(Locale.US, this) else "invalid"
    private fun String.safeText(): String = replace(Regex("[\\r\\n\\t]"), " ").take(MAX_TEXT_LENGTH)
    private fun String.csvSafe(): String = safeText().replace(",", " ")

    private val PERMISSIONS = listOf(
        "ACCESS_FINE_LOCATION" to Manifest.permission.ACCESS_FINE_LOCATION,
        "ACCESS_COARSE_LOCATION" to Manifest.permission.ACCESS_COARSE_LOCATION,
        "ACCESS_BACKGROUND_LOCATION" to Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        "ACTIVITY_RECOGNITION" to Manifest.permission.ACTIVITY_RECOGNITION,
        "POST_NOTIFICATIONS" to Manifest.permission.POST_NOTIFICATIONS,
        "FOREGROUND_SERVICE" to Manifest.permission.FOREGROUND_SERVICE,
        "FOREGROUND_SERVICE_LOCATION" to Manifest.permission.FOREGROUND_SERVICE_LOCATION
    )
    private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val DIAGNOSTIC_REPORT_VERSION = 2
    private const val MAX_SAMPLE_LINES = 1_200
    private const val MAX_TEXT_LENGTH = 120
    private const val ASYNC_TIMEOUT_MILLIS = 8_000L
    private val TIMEOUT_TOKEN = Any()
}

package com.pim.locationtest

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Short, read-only probe: records whether Android lets this app subscribe and deliver events. */
object SensorRuntimeProbe {
    fun run(context: Context, onComplete: (String) -> Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (manager == null) {
            onComplete("SensorManager unavailable")
            return
        }

        val probeThread = HandlerThread("LocationSensorProbe")
        probeThread.start()
        val probeHandler = Handler(probeThread.looper)
        val mainHandler = Handler(Looper.getMainLooper())
        val reports = LinkedHashMap<String, MutableSensorProbe>()
        val sensorsByKey = LinkedHashMap<String, Sensor>()
        val stepCounterBaselines = mutableMapOf<String, Float>()
        val lock = Any()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(lock) {
                    val key = sensorKey(event.sensor)
                    val row = reports[key] ?: return
                    row.callbackCount++
                    when (event.sensor.type) {
                        Sensor.TYPE_STEP_COUNTER -> {
                            val value = event.values.firstOrNull() ?: return
                            val baseline = stepCounterBaselines.putIfAbsent(key, value)
                            if (baseline != null && value > baseline) row.valueChanged = true
                        }
                        Sensor.TYPE_STEP_DETECTOR -> row.valueChanged = true
                        else -> row.lastValues = event.values.take(MAX_REPORTED_VALUES).map { it.safeNumber() }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor == null) return
                synchronized(lock) {
                    reports[sensorKey(sensor)]?.lastAccuracy = accuracy
                }
            }
        }

        val triggerSensor = runCatching { manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }.getOrNull()
        var triggerRequested: Boolean? = null
        val triggerFired = AtomicBoolean(false)
        val triggerListener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                triggerFired.set(event != null)
            }
        }

        val targets = mutableListOf<Pair<String, Sensor?>>()
        PROBED_SENSOR_TYPES.forEach { type ->
            val sensor = runCatching { manager.getDefaultSensor(type) }.getOrNull()
            val key = sensor?.let(::sensorKey) ?: "standard_${type}_${sensorLabel(type)}"
            targets += key to sensor
        }
        val vendorSensors = runCatching {
            manager.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
                val name = sensor.name.lowercase(Locale.US)
                val vendor = sensor.vendor.lowercase(Locale.US)
                VENDOR_SENSOR_KEYWORDS.any { it in name || it in vendor }
            }
        }.getOrDefault(emptyList())
        vendorSensors.forEach { sensor ->
            val key = sensorKey(sensor)
            if (targets.none { it.first == key }) targets += key to sensor
        }

        targets.forEach { (key, sensor) ->
            val type = sensor?.type ?: key.substringAfter("standard_").substringBefore('_').toIntOrNull() ?: -1
            val row = MutableSensorProbe(key = key, type = type, available = sensor != null)
            reports[key] = row
            if (sensor == null) return@forEach
            sensorsByKey[key] = sensor
            if (type in STEP_SENSOR_TYPES &&
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
            ) {
                row.error = "permission_not_granted:ACTIVITY_RECOGNITION"
                return@forEach
            }
            try {
                row.registered = manager.registerListener(
                    listener,
                    sensor,
                    samplingPeriodFor(type),
                    probeHandler
                )
                if (!row.registered) row.error = "registerListener=false"
            } catch (error: SecurityException) {
                row.error = "SecurityException"
            } catch (error: RuntimeException) {
                row.error = error.javaClass.simpleName
            }
        }

        val triggerReport = MutableSensorProbe(
            key = triggerSensor?.let(::sensorKey) ?: "standard_${Sensor.TYPE_SIGNIFICANT_MOTION}_significant_motion",
            type = Sensor.TYPE_SIGNIFICANT_MOTION,
            available = triggerSensor != null
        )
        if (triggerSensor != null) {
            try {
                triggerRequested = manager.requestTriggerSensor(triggerListener, triggerSensor)
                triggerReport.registered = triggerRequested == true
                if (triggerRequested != true) triggerReport.error = "requestTriggerSensor=false"
            } catch (error: SecurityException) {
                triggerReport.error = "SecurityException"
            } catch (error: RuntimeException) {
                triggerReport.error = error.javaClass.simpleName
            }
        }

        probeHandler.postDelayed({
            manager.unregisterListener(listener)
            if (triggerSensor != null && triggerRequested == true) {
                runCatching { manager.cancelTriggerSensor(triggerListener, triggerSensor) }
            }

            val report = synchronized(lock) {
                buildString {
                    appendLine("probe_duration_ms=$PROBE_DURATION_MILLIS")
                    reports.values.forEach { row -> appendLine(format(row, sensorsByKey[row.key])) }
                    append(
                        format(triggerReport, triggerSensor) +
                            " trigger_requested=${triggerRequested ?: false} trigger_fired=${triggerFired.get()}"
                    )
                }
            }
            probeThread.quitSafely()
            mainHandler.post { onComplete(report) }
        }, PROBE_DURATION_MILLIS)
    }

    private fun format(row: MutableSensorProbe, sensor: Sensor?): String = buildString {
        append("sensor_${row.key.safeText()} type=${row.type}(${sensorLabel(row.type)})")
        append(" available=${row.available}")
        append(" registered=${row.registered}")
        append(" callbacks=${row.callbackCount}")
        row.valueChanged.takeIf { row.type == Sensor.TYPE_STEP_COUNTER || row.type == Sensor.TYPE_STEP_DETECTOR }?.let {
            append(" step_event_seen=$it")
        }
        if (row.lastValues.isNotEmpty()) append(" last_values=${row.lastValues.joinToString("/")}")
        row.lastAccuracy?.let { append(" last_accuracy=$it") }
        row.error?.let { append(" error=$it") }
        sensor?.let {
            append(" vendor=${it.vendor.safeText()}")
            append(" wake_up=${it.isWakeUpSensor}")
        }
    }

    private fun samplingPeriodFor(type: Int): Int = when (type) {
        Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> SensorManager.SENSOR_DELAY_NORMAL
        else -> SensorManager.SENSOR_DELAY_GAME
    }

    private fun sensorLabel(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accelerometer"
        Sensor.TYPE_GYROSCOPE -> "gyroscope"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "significant_motion"
        else -> "sensor"
    }

    private fun sensorKey(sensor: Sensor): String =
        "${sensor.type}_${sensor.name.safeText()}_${sensor.vendor.safeText()}_${sensor.isWakeUpSensor}"
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun Float.safeNumber(): String = if (isFinite()) "%.3f".format(Locale.US, this) else "invalid"
    private fun String.safeText(): String = replace(Regex("[\\r\\n\\t]"), " ").take(80)

    private data class MutableSensorProbe(
        val key: String,
        val type: Int,
        val available: Boolean,
        var registered: Boolean = false,
        var callbackCount: Int = 0,
        var valueChanged: Boolean = false,
        var lastValues: List<String> = emptyList(),
        var lastAccuracy: Int? = null,
        var error: String? = null
    )

    private val PROBED_SENSOR_TYPES = listOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR
    )
    private val STEP_SENSOR_TYPES = setOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)
    private val VENDOR_SENSOR_KEYWORDS = listOf("stationary", "car_motion", "act_recog")
    private const val PROBE_DURATION_MILLIS = 2_500L
    private const val MAX_REPORTED_VALUES = 3
}

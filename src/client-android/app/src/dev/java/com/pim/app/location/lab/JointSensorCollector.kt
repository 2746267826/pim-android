package com.pim.app.location.lab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import com.pim.locationtest.SensorWindow
import com.pim.locationtest.SensorWindowMonitor
import kotlin.math.sqrt

/** Runs for the entire service segment, including while the Activity is not visible. */
internal class JointSensorCollector(context: Context, private val handler: Handler, startedAt: Long) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val monitors = linkedMapOf(
        "accel" to SensorWindowMonitor(startedAt), "gyro" to SensorWindowMonitor(startedAt, 0.03),
        "linear" to SensorWindowMonitor(startedAt, 0.15)
    )
    private val registration = linkedMapOf<String, String>()

    fun start() {
        for ((type, name) in TYPES) {
            val sensor = manager.getDefaultSensor(type)
            registration[name] = if (sensor == null) "absent" else try {
                if (manager.registerListener(this, sensor, 20_000, 0, handler)) "registered" else "register_false"
            } catch (error: RuntimeException) { error.javaClass.simpleName }
        }
    }

    fun stop() = manager.unregisterListener(this)
    fun status(): String = registration.entries.joinToString(";") { "${it.key}:${it.value}" }
    fun snapshot(now: Long): Map<String, SensorWindow> = monitors.mapValues { it.value.snapshot(now) }

    override fun onSensorChanged(event: SensorEvent) {
        val name = TYPES[event.sensor.type] ?: return
        if (event.values.size < 3) return
        val magnitude = sqrt(event.values.take(3).sumOf { it.toDouble() * it })
        monitors.getValue(name).record(event.timestamp / 1_000_000L, SystemClock.elapsedRealtime(), magnitude)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private val TYPES = linkedMapOf(Sensor.TYPE_ACCELEROMETER to "accel", Sensor.TYPE_GYROSCOPE to "gyro", Sensor.TYPE_LINEAR_ACCELERATION to "linear")
    }
}

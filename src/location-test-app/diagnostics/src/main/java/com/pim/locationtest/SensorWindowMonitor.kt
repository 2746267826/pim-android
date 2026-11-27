package com.pim.locationtest

import kotlin.math.sqrt

enum class SensorEvidence { Unknown, Quiet, Varying }

data class SensorWindow(
    val count: Int = 0,
    val totalCount: Long = 0,
    val coveredMillis: Long = 0,
    val firstDelayMillis: Long? = null,
    val ageMillis: Long? = null,
    val maxGapMillis: Long = 0,
    val windowMaxGapMillis: Long = 0,
    val maxDeliveryDelayMillis: Long = 0,
    val standardDeviation: Double? = null,
    val rms: Double? = null,
    val evidence: SensorEvidence = SensorEvidence.Unknown
)

/** Time coverage rather than callback frequency determines whether a window is usable. */
class SensorWindowMonitor(
    private val startedAtMillis: Long,
    private val quietRmsLimit: Double = Double.POSITIVE_INFINITY
) {
    private data class Reading(val at: Long, val receivedAt: Long, val magnitude: Double)
    private val readings = ArrayDeque<Reading>()
    private var total = 0L
    private var firstAt: Long? = null
    private var lastAt: Long? = null
    private var largestGap = 0L
    private var largestDeliveryDelay = 0L

    @Synchronized
    fun record(eventAtMillis: Long, receivedAtMillis: Long, magnitude: Double) {
        if (!magnitude.isFinite() || eventAtMillis < startedAtMillis || eventAtMillis > receivedAtMillis ||
            lastAt?.let { eventAtMillis <= it } == true) return
        lastAt?.let { largestGap = maxOf(largestGap, eventAtMillis - it) }
        if (firstAt == null) firstAt = eventAtMillis
        lastAt = eventAtMillis
        total++
        largestDeliveryDelay = maxOf(largestDeliveryDelay, receivedAtMillis - eventAtMillis)
        readings.addLast(Reading(eventAtMillis, receivedAtMillis, magnitude))
        prune(receivedAtMillis)
        while (readings.size > 1_000) readings.removeFirst()
    }

    @Synchronized
    fun snapshot(nowMillis: Long): SensorWindow {
        prune(nowMillis)
        val points = readings.filter { it.at <= nowMillis }
        val gaps = points.zipWithNext { a, b -> b.at - a.at }
        val coverage = gaps.filter { it <= MAX_OBSERVABLE_GAP_MILLIS }.sum()
        val age = lastAt?.let { (nowMillis - it).coerceAtLeast(0) }
        val windowGap = maxOf(gaps.maxOrNull() ?: 0, age ?: 0)
        val mean = points.map { it.magnitude }.average()
        val std = points.takeIf { it.isNotEmpty() }?.let {
            sqrt(it.map { point -> (point.magnitude - mean) * (point.magnitude - mean) }.average())
        }
        val rms = points.takeIf { it.isNotEmpty() }?.let { sqrt(it.map { point -> point.magnitude * point.magnitude }.average()) }
        val deliveryDelay = points.maxOfOrNull { it.receivedAt - it.at } ?: 0
        val usable = coverage >= MIN_COVERAGE_MILLIS && windowGap <= FRESHNESS_MILLIS &&
            deliveryDelay <= FRESHNESS_MILLIS
        return SensorWindow(
            count = points.size, totalCount = total, coveredMillis = coverage,
            firstDelayMillis = firstAt?.minus(startedAtMillis), ageMillis = age,
            maxGapMillis = maxOf(largestGap, age ?: (nowMillis - startedAtMillis).coerceAtLeast(0)),
            windowMaxGapMillis = windowGap, maxDeliveryDelayMillis = largestDeliveryDelay,
            standardDeviation = std, rms = rms,
            evidence = when {
                !usable -> SensorEvidence.Unknown
                std!! < 0.1 && rms!! <= quietRmsLimit -> SensorEvidence.Quiet
                else -> SensorEvidence.Varying
            }
        )
    }

    private fun prune(now: Long) {
        while (readings.firstOrNull()?.let { now - it.at > WINDOW_MILLIS } == true) readings.removeFirst()
    }

    companion object {
        const val WINDOW_MILLIS = 5_000L
        const val MIN_COVERAGE_MILLIS = 4_000L
        const val MAX_OBSERVABLE_GAP_MILLIS = 250L
        const val FRESHNESS_MILLIS = 500L
    }
}

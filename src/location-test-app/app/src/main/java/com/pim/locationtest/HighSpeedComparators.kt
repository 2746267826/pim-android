package com.pim.locationtest

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationSample(
    val elapsedRealtimeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val provider: String = "unknown",
    val locationTimeMillis: Long? = null,
    val hasAltitude: Boolean = false,
    val altitudeMeters: Double? = null,
    val verticalAccuracyMeters: Float? = null,
    val hasBearing: Boolean = false,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val isMock: Boolean = false,
    val elapsedRealtimeUncertaintyMillis: Double? = null
)

enum class LegacyMode { Inactive, Accumulating, Active }
enum class ProposedMode { Inactive, Verifying, Active }

data class AlgorithmSnapshot(
    val legacyMode: LegacyMode,
    val proposedMode: ProposedMode,
    val proposedReason: String,
    val displacementMeters: Double,
    val derivedSpeedMetersPerSecond: Double?,
    val evidenceFixCount: Int
)

/** Mirrors the production speed thresholds and debounce periods. */
class LegacyHighSpeedTracker {
    var mode: LegacyMode = LegacyMode.Inactive
        private set
    private var fastSince: Long? = null
    private var lowSince: Long? = null
    private var slowSince: Long? = null

    fun reset() {
        mode = LegacyMode.Inactive
        fastSince = null
        lowSince = null
        slowSince = null
    }

    fun observe(speedMetersPerSecond: Float?, now: Long): LegacyMode {
        if (mode == LegacyMode.Active) {
            if (speedMetersPerSecond == null || speedMetersPerSecond < FALLBACK_SPEED_METERS_PER_SECOND) {
                val start = slowSince ?: now
                slowSince = start
                if (now - start >= FALLBACK_DEBOUNCE_MILLIS) reset()
            } else {
                slowSince = null
            }
            return mode
        }

        if (speedMetersPerSecond != null && speedMetersPerSecond >= TRIGGER_SPEED_METERS_PER_SECOND) {
            val lowSpeedPersisted = lowSince?.let { now - it >= LOW_SPEED_TOLERANCE_MILLIS } == true
            val start = if (lowSpeedPersisted) now else fastSince ?: now
            fastSince = start
            lowSince = null
            mode = if (now - start >= TRIGGER_DEBOUNCE_MILLIS) LegacyMode.Active else LegacyMode.Accumulating
        } else if (fastSince != null) {
            val start = lowSince ?: now
            lowSince = start
            if (now - start >= LOW_SPEED_TOLERANCE_MILLIS) {
                fastSince = null
                lowSince = null
                mode = LegacyMode.Inactive
            }
        }
        return mode
    }

    companion object {
        const val TRIGGER_SPEED_KMH = 8f
        const val FALLBACK_SPEED_KMH = 7f
        const val TRIGGER_SPEED_METERS_PER_SECOND = TRIGGER_SPEED_KMH / 3.6f
        const val FALLBACK_SPEED_METERS_PER_SECOND = FALLBACK_SPEED_KMH / 3.6f
        const val TRIGGER_DEBOUNCE_MILLIS = 10_000L
        const val FALLBACK_DEBOUNCE_MILLIS = 60_000L
        const val LOW_SPEED_TOLERANCE_MILLIS = 3_000L
    }
}

/** Candidate: a reported high speed needs high-speed displacement corroboration. */
class CorroboratedHighSpeedTracker {
    private val fixes = ArrayDeque<LocationSample>()
    var mode: ProposedMode = ProposedMode.Inactive
        private set
    var reason: String = "等待高速速度证据"
        private set
    var displacementMeters: Double = 0.0
        private set
    var derivedSpeedMetersPerSecond: Double? = null
        private set
    var evidenceFixCount: Int = 0
        private set

    private var candidateSince: Long? = null
    private var lastFastEvidenceAt: Long? = null
    private var lastMovementEvidenceAt: Long? = null
    private var slowSince: Long? = null

    fun reset() {
        fixes.clear()
        mode = ProposedMode.Inactive
        reason = "等待高速速度证据"
        displacementMeters = 0.0
        derivedSpeedMetersPerSecond = null
        evidenceFixCount = 0
        candidateSince = null
        lastFastEvidenceAt = null
        lastMovementEvidenceAt = null
        slowSince = null
    }

    fun observe(sample: LocationSample): ProposedMode {
        if (validSample(sample)) fixes.addLast(sample)
        return advance(sample.elapsedRealtimeMillis, sample)
    }

    fun advance(now: Long, sample: LocationSample? = null): ProposedMode {
        prune(now)
        val movement = movementEvidence(now)
        displacementMeters = movement.displacementMeters
        derivedSpeedMetersPerSecond = movement.speedMetersPerSecond
        evidenceFixCount = movement.fixCount

        if (mode == ProposedMode.Active) {
            if (movement.isStationary) {
                exit("位置连续 ${STATIONARY_WINDOW_MILLIS / 1_000}s 保持在静止簇内")
                return mode
            }
            val currentSpeed = sample?.let(::reportedSpeed)
            if (movement.isHighSpeedCoherent &&
                (currentSpeed == null || currentSpeed >= FALLBACK_SPEED_METERS_PER_SECOND)
            ) {
                lastMovementEvidenceAt = now
            }

            val reportedLowSpeed = sample?.let { reportedSpeed(it) }?.let {
                it < FALLBACK_SPEED_METERS_PER_SECOND
            } == true
            val lowSpeed = if (currentSpeed != null) {
                reportedLowSpeed && !movement.isHighSpeedCoherent
            } else {
                movement.speedMetersPerSecond != null &&
                    movement.speedMetersPerSecond < FALLBACK_SPEED_METERS_PER_SECOND
            }
            if (lowSpeed) {
                val start = slowSince ?: now
                slowSince = start
                if (now - start >= LOW_SPEED_EXIT_MILLIS) {
                    exit("可信速度低于 ${FALLBACK_SPEED_KMH.toInt()} km/h")
                    return mode
                }
            } else {
                slowSince = null
            }

            if (lastMovementEvidenceAt?.let { now - it >= ACTIVE_EVIDENCE_TIMEOUT_MILLIS } == true) {
                exit("可信位移证据已过期")
            } else {
                reason = if (movement.isHighSpeedCoherent &&
                    (currentSpeed == null || currentSpeed >= FALLBACK_SPEED_METERS_PER_SECOND)
                ) {
                    "高速档：持续速度与位移证据有效"
                } else {
                    "高速档：等待新的高速位移证据"
                }
            }
            return mode
        }

        if (movement.isStationary) {
            mode = ProposedMode.Inactive
            reason = "位置连续 ${STATIONARY_WINDOW_MILLIS / 1_000}s 保持在静止簇内"
            candidateSince = null
            lastFastEvidenceAt = null
            return mode
        }

        val currentSpeed = sample?.let(::reportedSpeed)
        val fastSpeed = currentSpeed?.let {
            it >= TRIGGER_SPEED_METERS_PER_SECOND
        } == true
        val fastDisplacement = sample != null && validSample(sample) &&
            sample.speedMetersPerSecond == null && movement.isHighSpeedCoherent
        val fastEvidence = fastSpeed || fastDisplacement

        if (!fastEvidence) {
            if (mode == ProposedMode.Verifying) {
                clearCandidate(
                    if (currentSpeed != null && currentSpeed < TRIGGER_SPEED_METERS_PER_SECOND && movement.isHighSpeedCoherent) {
                        "GPS 速度低于阈值，位置漂移不作为高速证据"
                    } else {
                        "高速速度证据中断，候选已清除"
                    }
                )
            }
            else reason = when {
                sample != null && !validSample(sample) -> "定位水平精度不足"
                currentSpeed != null && currentSpeed < TRIGGER_SPEED_METERS_PER_SECOND && movement.isHighSpeedCoherent ->
                    "GPS 速度低于阈值，位置漂移不作为高速证据"
                else -> "等待 ≥${TRIGGER_SPEED_KMH.toInt()} km/h 速度证据"
            }
            return mode
        }

        val priorFast = lastFastEvidenceAt
        if (candidateSince == null || (priorFast != null && now - priorFast > CANDIDATE_GAP_TIMEOUT_MILLIS)) {
            candidateSince = now
        }
        lastFastEvidenceAt = now

        val since = candidateSince ?: now
        if (movement.isHighSpeedCoherent && now - since >= ENTRY_CONFIRMATION_MILLIS) {
            mode = ProposedMode.Active
            reason = "速度与连续位移均已确认"
            lastMovementEvidenceAt = now
            slowSince = null
            return mode
        }
        if (now - since >= CANDIDATE_MAX_DURATION_MILLIS && !movement.isHighSpeedCoherent) {
            clearCandidate("持续高速速度未获得高速位移佐证")
            return mode
        }

        mode = ProposedMode.Verifying
        reason = if (movement.isHighSpeedCoherent) "高速位移已佐证，等待速度持续时间" else "高速速度已出现，等待高速位移佐证"
        return mode
    }

    private fun reportedSpeed(sample: LocationSample): Float? {
        if (!validSample(sample)) return null
        val speed = sample.speedMetersPerSecond ?: return null
        if (!speed.isFinite() || speed < 0f) return null
        return speed
    }

    private fun movementEvidence(now: Long): MovementEvidence {
        val points = fixes.filter { now - it.elapsedRealtimeMillis in 0..POSITION_WINDOW_MILLIS }
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        if (first == null || last == null || points.size < MIN_EVIDENCE_FIXES) {
            return MovementEvidence(false, false, 0.0, null, points.size)
        }
        val span = last.elapsedRealtimeMillis - first.elapsedRealtimeMillis
        if (span < MIN_EVIDENCE_SPAN_MILLIS) return MovementEvidence(false, false, 0.0, null, points.size)

        val distance = distanceMeters(first, last)
        val pathLength = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        val medianAccuracy = medianAccuracy(points)
        val speed = distance / (span / 1_000.0)
        val displacementFloor = maxOf(MIN_DISPLACEMENT_METERS, medianAccuracy * ACCURACY_DISPLACEMENT_MULTIPLIER)
        val highSpeedCoherent = distance >= displacementFloor &&
            speed >= TRIGGER_SPEED_METERS_PER_SECOND &&
            (pathLength == 0.0 || distance / pathLength >= MIN_PATH_COHERENCE)

        val stationary = fixes.filter { now - it.elapsedRealtimeMillis in 0..STATIONARY_WINDOW_MILLIS }
        val stationaryFirst = stationary.firstOrNull()
        val stationaryLast = stationary.lastOrNull()
        val stationarySpan = if (stationaryFirst != null && stationaryLast != null) {
            stationaryLast.elapsedRealtimeMillis - stationaryFirst.elapsedRealtimeMillis
        } else 0L
        val radiusLimit = maxOf(MIN_STATIONARY_RADIUS_METERS, medianAccuracy(stationary) * STATIONARY_ACCURACY_MULTIPLIER)
        val radius = stationaryFirst?.let { anchor ->
            stationary.maxOfOrNull { distanceMeters(anchor, it) } ?: 0.0
        } ?: Double.MAX_VALUE
        val isStationary = stationary.size >= MIN_STATIONARY_FIXES &&
            stationarySpan >= STATIONARY_MIN_SPAN_MILLIS && radius <= radiusLimit

        return MovementEvidence(highSpeedCoherent, isStationary, distance, speed, points.size)
    }

    private fun medianAccuracy(points: List<LocationSample>): Double {
        val values = points.mapNotNull { it.horizontalAccuracyMeters?.toDouble() }.sorted()
        return values.getOrNull(values.size / 2) ?: 0.0
    }

    private fun validSample(sample: LocationSample): Boolean {
        val accuracy = sample.horizontalAccuracyMeters ?: return false
        return accuracy.isFinite() && accuracy >= 0f && accuracy < MAX_HORIZONTAL_ACCURACY_METERS &&
            sample.latitude.isFinite() && sample.longitude.isFinite() &&
            sample.latitude in -90.0..90.0 && sample.longitude in -180.0..180.0
    }

    private fun prune(now: Long) {
        while (fixes.firstOrNull()?.let { now - it.elapsedRealtimeMillis > POSITION_WINDOW_MILLIS } == true) {
            fixes.removeFirst()
        }
    }

    private fun clearCandidate(message: String) {
        mode = ProposedMode.Inactive
        reason = message
        candidateSince = null
        lastFastEvidenceAt = null
    }

    private fun exit(message: String) {
        mode = ProposedMode.Inactive
        reason = message
        candidateSince = null
        lastFastEvidenceAt = null
        lastMovementEvidenceAt = null
        slowSince = null
    }

    private fun distanceMeters(a: LocationSample, b: LocationSample): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    private data class MovementEvidence(
        val isHighSpeedCoherent: Boolean,
        val isStationary: Boolean,
        val displacementMeters: Double,
        val speedMetersPerSecond: Double?,
        val fixCount: Int
    )

    companion object {
        const val TRIGGER_SPEED_KMH = 8f
        const val FALLBACK_SPEED_KMH = 7f
        const val TRIGGER_SPEED_METERS_PER_SECOND = TRIGGER_SPEED_KMH / 3.6f
        const val FALLBACK_SPEED_METERS_PER_SECOND = FALLBACK_SPEED_KMH / 3.6f
        const val MAX_HORIZONTAL_ACCURACY_METERS = 30f
        const val ENTRY_CONFIRMATION_MILLIS = 10_000L
        const val CANDIDATE_MAX_DURATION_MILLIS = 30_000L
        const val CANDIDATE_GAP_TIMEOUT_MILLIS = 7_500L
        const val ACTIVE_EVIDENCE_TIMEOUT_MILLIS = 25_000L
        const val LOW_SPEED_EXIT_MILLIS = 10_000L
        const val POSITION_WINDOW_MILLIS = 30_000L
        const val STATIONARY_WINDOW_MILLIS = 20_000L
        const val STATIONARY_MIN_SPAN_MILLIS = 15_000L
        const val MIN_EVIDENCE_SPAN_MILLIS = 8_000L
        const val MIN_EVIDENCE_FIXES = 4
        const val MIN_STATIONARY_FIXES = 5
        const val MIN_DISPLACEMENT_METERS = 20.0
        const val MIN_PATH_COHERENCE = 0.55
        const val MIN_STATIONARY_RADIUS_METERS = 15.0
        const val ACCURACY_DISPLACEMENT_MULTIPLIER = 2.0
        const val STATIONARY_ACCURACY_MULTIPLIER = 1.5
        const val NO_FIX_TIMEOUT_MILLIS = 30_000L
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

data class ComparisonRecord(
    val sample: LocationSample,
    val originalMode: LegacyMode,
    val proposedMode: ProposedMode,
    val proposedReason: String,
    val displacementMeters: Double,
    val derivedSpeedMetersPerSecond: Double?,
    val evidenceFixCount: Int
)

data class DemoSnapshot(
    val running: Boolean,
    val source: String,
    val issue: String?,
    val locationAvailable: Boolean?,
    val startedAtElapsedRealtimeMillis: Long?,
    val latest: ComparisonRecord?,
    val legacyMode: LegacyMode,
    val proposedMode: ProposedMode,
    val proposedReason: String,
    val displacementMeters: Double,
    val derivedSpeedMetersPerSecond: Double?,
    val evidenceFixCount: Int,
    val sampleCount: Int,
    val preTestSampleCount: Int,
    val droppedHistoryCount: Int,
    val records: List<ComparisonRecord>
)

object DemoRuntime {
    private val legacy = LegacyHighSpeedTracker()
    private val proposed = CorroboratedHighSpeedTracker()
    private val records = ArrayDeque<ComparisonRecord>()
    private var running = false
    private var source = "尚未开始"
    private var issue: String? = null
    private var locationAvailable: Boolean? = null
    private var startedAt: Long? = null
    private var latest: ComparisonRecord? = null
    private var lastFixAt: Long? = null
    private var lastLegacyNoFixAt: Long? = null
    private var sampleCount = 0
    private var preTestSampleCount = 0
    private var droppedHistoryCount = 0

    @Synchronized
    fun begin(sourceName: String, isRunning: Boolean) {
        legacy.reset()
        proposed.reset()
        records.clear()
        running = isRunning
        source = sourceName
        issue = null
        locationAvailable = null
        startedAt = android.os.SystemClock.elapsedRealtime()
        latest = null
        lastFixAt = null
        lastLegacyNoFixAt = null
        sampleCount = 0
        preTestSampleCount = 0
        droppedHistoryCount = 0
    }

    @Synchronized
    fun setRunning(value: Boolean) {
        running = value
    }

    @Synchronized
    fun setIssue(message: String?) {
        issue = message
    }

    @Synchronized
    fun setLocationAvailability(available: Boolean?) {
        locationAvailable = available
    }

    @Synchronized
    fun accept(sample: LocationSample) {
        if (startedAt?.let { sample.elapsedRealtimeMillis < it } == true) {
            preTestSampleCount++
            return
        }
        lastFixAt = sample.elapsedRealtimeMillis
        val speedForLegacy = sample.speedMetersPerSecond?.takeIf {
            it.isFinite() && it >= 0f &&
                sample.horizontalAccuracyMeters?.let { accuracy ->
                    accuracy.isFinite() && accuracy >= 0f && accuracy < CorroboratedHighSpeedTracker.MAX_HORIZONTAL_ACCURACY_METERS
                } == true
        }
        val legacyMode = legacy.observe(speedForLegacy, sample.elapsedRealtimeMillis)
        proposed.observe(sample)
        sampleCount++
        val record = ComparisonRecord(
            sample = sample,
            originalMode = legacyMode,
            proposedMode = proposed.mode,
            proposedReason = proposed.reason,
            displacementMeters = proposed.displacementMeters,
            derivedSpeedMetersPerSecond = proposed.derivedSpeedMetersPerSecond,
            evidenceFixCount = proposed.evidenceFixCount
        )
        latest = record
        records.addLast(record)
        while (records.size > MAX_RECORDS) {
            records.removeFirst()
            droppedHistoryCount++
        }
    }

    @Synchronized
    fun advance(nowElapsedRealtimeMillis: Long) {
        val lastFix = lastFixAt
        val previousNoFix = lastLegacyNoFixAt
        if (lastFix != null && nowElapsedRealtimeMillis - lastFix >= CorroboratedHighSpeedTracker.NO_FIX_TIMEOUT_MILLIS &&
            (previousNoFix == null || nowElapsedRealtimeMillis - previousNoFix >= CorroboratedHighSpeedTracker.NO_FIX_TIMEOUT_MILLIS)
        ) {
            legacy.observe(null, nowElapsedRealtimeMillis)
            lastLegacyNoFixAt = nowElapsedRealtimeMillis
        }
        proposed.advance(nowElapsedRealtimeMillis)
        latest?.let { old ->
            latest = old.copy(
                originalMode = legacy.mode,
                proposedMode = proposed.mode,
                proposedReason = proposed.reason,
                displacementMeters = proposed.displacementMeters,
                derivedSpeedMetersPerSecond = proposed.derivedSpeedMetersPerSecond,
                evidenceFixCount = proposed.evidenceFixCount
            )
        }
    }

    @Synchronized
    fun snapshot(): DemoSnapshot = DemoSnapshot(
        running = running,
        source = source,
        issue = issue,
        locationAvailable = locationAvailable,
        startedAtElapsedRealtimeMillis = startedAt,
        latest = latest,
        legacyMode = legacy.mode,
        proposedMode = proposed.mode,
        proposedReason = proposed.reason,
        displacementMeters = proposed.displacementMeters,
        derivedSpeedMetersPerSecond = proposed.derivedSpeedMetersPerSecond,
        evidenceFixCount = proposed.evidenceFixCount,
        sampleCount = sampleCount,
        preTestSampleCount = preTestSampleCount,
        droppedHistoryCount = droppedHistoryCount,
        records = records.toList()
    )

    @Synchronized
    fun csv(): String {
        val origin = startedAt ?: 0L
        return buildString {
            appendLine("elapsed_ms,provider,speed_kmh,speed_accuracy_mps,horizontal_accuracy_m,has_altitude,altitude_m,vertical_accuracy_m,has_bearing,bearing_deg,bearing_accuracy_deg,is_mock,displacement_m,derived_speed_kmh,position_evidence_fixes,original_mode,candidate_mode,candidate_reason")
            records.forEach { row ->
                val fix = row.sample
                append(fix.elapsedRealtimeMillis - origin).append(',')
                append(fix.provider.csvText()).append(',')
                append(fix.speedMetersPerSecond?.times(3.6f)?.csvNumber() ?: "").append(',')
                append(fix.speedAccuracyMetersPerSecond?.csvNumber() ?: "").append(',')
                append(fix.horizontalAccuracyMeters?.csvNumber() ?: "").append(',')
                append(fix.hasAltitude).append(',')
                append(fix.altitudeMeters?.csvNumber() ?: "").append(',')
                append(fix.verticalAccuracyMeters?.csvNumber() ?: "").append(',')
                append(fix.hasBearing).append(',')
                append(fix.bearingDegrees?.csvNumber() ?: "").append(',')
                append(fix.bearingAccuracyDegrees?.csvNumber() ?: "").append(',')
                append(fix.isMock).append(',')
                append(row.displacementMeters.csvNumber()).append(',')
                append(row.derivedSpeedMetersPerSecond?.times(3.6)?.csvNumber() ?: "").append(',')
                append(row.evidenceFixCount).append(',')
                append(row.originalMode).append(',')
                append(row.proposedMode).append(',')
                append('"').append(row.proposedReason.replace("\"", "\"\"")).append('"').appendLine()
            }
        }
    }

    private fun Float.csvNumber(): String = if (isFinite()) "%.3f".format(java.util.Locale.US, this) else ""
    private fun Double.csvNumber(): String = if (isFinite()) "%.3f".format(java.util.Locale.US, this) else ""
    private fun String.csvText(): String = replace(",", " ").replace("\n", " ").replace("\r", " ")

    private const val MAX_RECORDS = 1_200
}

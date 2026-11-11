package com.pim.app.location.highspeed

import com.pim.app.location.LocationSnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** High-speed tracking mode with fresh GPS-speed and continuous-position corroboration. */
enum class HighSpeedMode { Inactive, Accumulating, Active }

/**
 * Consumes new, fresh location events. Timer ticks only expire evidence and can
 * never replay a cached speed or renew a movement window. Coordinates are kept
 * in memory only for this short corroboration window.
 */
class HighSpeedTracker(
    private val nowElapsedRealtimeMillis: () -> Long
) {
    var mode: HighSpeedMode = HighSpeedMode.Inactive
        private set
    var activeSinceElapsedRealtimeMillis: Long? = null
        private set

    private data class TimedFix(val location: LocationSnapshot, val elapsedMillis: Long)
    private val fixes = ArrayDeque<TimedFix>()
    private var lastFixAtMillis: Long? = null
    private var candidateSinceMillis: Long? = null
    private var lastFastEvidenceAtMillis: Long? = null
    private var lastMovementEvidenceAtMillis: Long? = null
    private var slowSinceMillis: Long? = null
    private var candidateRetryAtMillis: Long = 0L

    fun observeLocation(location: LocationSnapshot) {
        val eventNanos = location.elapsedRealtimeNanos ?: return
        if (eventNanos <= 0L || !location.horizontalAccuracyMeters.isValidAccuracy() ||
            !location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        val eventAt = eventNanos / NANOS_PER_MILLI
        val now = nowElapsedRealtimeMillis()
        if (now - eventAt !in 0..MAX_FIX_AGE_MILLIS || lastFixAtMillis?.let { eventAt <= it } == true) return

        if (lastFixAtMillis?.let { eventAt - it > CANDIDATE_GAP_TIMEOUT_MILLIS } == true) {
            fixes.clear()
            resetCandidate()
            slowSinceMillis = null
        }
        if (mode == HighSpeedMode.Active &&
            lastMovementEvidenceAtMillis?.let { now - it >= ACTIVE_EVIDENCE_TIMEOUT_MILLIS } == true) {
            exitActive()
        }
        lastFixAtMillis = eventAt
        fixes.addLast(TimedFix(location, eventAt))
        pruneFixes(now)
        val movement = movementEvidence(now)
        val speed = location.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }
        val fastSpeed = speed != null && speed >= TRIGGER_SPEED_METERS_PER_SECOND
        val positionOnlyFast = location.speedMetersPerSecond == null && movement.isHighSpeedCoherent

        if (mode == HighSpeedMode.Active) {
            if (movement.isStationary) {
                exitActive()
                return
            }
            if (movement.isHighSpeedCoherent &&
                (location.speedMetersPerSecond == null || speed?.let { it >= FALLBACK_SPEED_METERS_PER_SECOND } == true)) {
                lastMovementEvidenceAtMillis = eventAt
            }
            val lowSpeed = if (speed != null) {
                speed < FALLBACK_SPEED_METERS_PER_SECOND && !movement.isHighSpeedCoherent
            } else {
                movement.derivedSpeedMetersPerSecond?.let { it < FALLBACK_SPEED_METERS_PER_SECOND } == true
            }
            if (lowSpeed) {
                val start = slowSinceMillis ?: now
                slowSinceMillis = start
                if (now - start >= LOW_SPEED_EXIT_MILLIS) {
                    exitActive()
                    return
                }
            } else {
                slowSinceMillis = null
            }
            if (lastMovementEvidenceAtMillis?.let { now - it >= ACTIVE_EVIDENCE_TIMEOUT_MILLIS } == true) {
                exitActive()
            }
            return
        }

        if (movement.isStationary) {
            resetCandidate()
            mode = HighSpeedMode.Inactive
            return
        }
        if (!fastSpeed && !positionOnlyFast) {
            resetCandidate()
            mode = HighSpeedMode.Inactive
            return
        }
        if (now < candidateRetryAtMillis && !movement.isHighSpeedCoherent) {
            mode = HighSpeedMode.Inactive
            return
        }

        val previousFast = lastFastEvidenceAtMillis
        if (candidateSinceMillis == null ||
            previousFast?.let { eventAt - it > CANDIDATE_GAP_TIMEOUT_MILLIS } == true
        ) {
            candidateSinceMillis = eventAt
        }
        lastFastEvidenceAtMillis = eventAt
        val started = candidateSinceMillis ?: eventAt
        if (movement.isHighSpeedCoherent && eventAt - started >= ENTRY_CONFIRMATION_MILLIS) {
            mode = HighSpeedMode.Active
            activeSinceElapsedRealtimeMillis = eventAt
            lastMovementEvidenceAtMillis = eventAt
            slowSinceMillis = null
            candidateRetryAtMillis = 0L
        } else if (eventAt - started >= CANDIDATE_MAX_DURATION_MILLIS && !movement.isHighSpeedCoherent) {
            resetCandidate()
            candidateRetryAtMillis = now + CANDIDATE_RETRY_DELAY_MILLIS
            mode = HighSpeedMode.Inactive
        } else {
            mode = HighSpeedMode.Accumulating
        }
    }

    /** Advance debounce/expiry using current time without treating time as a fix. */
    fun advance() {
        val now = nowElapsedRealtimeMillis()
        pruneFixes(now)
        if (lastFixAtMillis?.let { now - it > MAX_FIX_AGE_MILLIS } == true) {
            fixes.clear()
        }
        if (lastFixAtMillis?.let { now - it > CANDIDATE_GAP_TIMEOUT_MILLIS } == true) {
            slowSinceMillis = null
        }
        when (mode) {
            HighSpeedMode.Inactive -> Unit
            HighSpeedMode.Accumulating -> {
                if (lastFastEvidenceAtMillis?.let { now - it > CANDIDATE_GAP_TIMEOUT_MILLIS } == true) {
                    resetCandidate()
                    mode = HighSpeedMode.Inactive
                }
            }
            HighSpeedMode.Active -> {
                if (lastMovementEvidenceAtMillis?.let { now - it >= ACTIVE_EVIDENCE_TIMEOUT_MILLIS } == true) {
                    exitActive()
                }
            }
        }
    }

    fun reset() {
        mode = HighSpeedMode.Inactive
        activeSinceElapsedRealtimeMillis = null
        lastFixAtMillis = null
        fixes.clear()
        resetCandidate()
        lastMovementEvidenceAtMillis = null
        slowSinceMillis = null
        candidateRetryAtMillis = 0L
    }

    private fun movementEvidence(now: Long): MovementEvidence {
        val points = fixes.filter { now - it.elapsedMillis in 0..POSITION_WINDOW_MILLIS }
        val segments = points.zipWithNext()
        if (points.size < MIN_EVIDENCE_FIXES || segments.isEmpty()) return MovementEvidence.EMPTY.copy(fixCount = points.size)
        val first = points.first()
        val last = points.last()
        val span = last.elapsedMillis - first.elapsedMillis
        if (span < MIN_EVIDENCE_SPAN_MILLIS) return MovementEvidence.EMPTY.copy(fixCount = points.size)

        val net = distanceMeters(first.location, last.location)
        val path = segments.sumOf { (a, b) -> distanceMeters(a.location, b.location) }
        val derivedSpeed = net / (span / 1000.0)
        val floor = maxOf(MIN_DISPLACEMENT_METERS, medianAccuracy(points) * ACCURACY_DISPLACEMENT_MULTIPLIER)
        val continuous = segments.all { (a, b) ->
            b.elapsedMillis - a.elapsedMillis in 1..CANDIDATE_GAP_TIMEOUT_MILLIS
        }
        val movingSegments = segments.count { (a, b) ->
            val seconds = (b.elapsedMillis - a.elapsedMillis) / 1000.0
            seconds > 0.0 && distanceMeters(a.location, b.location) / seconds >=
                TRIGGER_SPEED_METERS_PER_SECOND * MIN_SEGMENT_SPEED_RATIO
        }
        val coherent = continuous && net >= floor && derivedSpeed >= TRIGGER_SPEED_METERS_PER_SECOND &&
            movingSegments >= MIN_MOVING_SEGMENTS && movingSegments.toDouble() / segments.size >= MIN_MOVING_SEGMENT_RATIO &&
            (path == 0.0 || net / path >= MIN_PATH_COHERENCE)

        val recent = fixes.filter { now - it.elapsedMillis in 0..STATIONARY_WINDOW_MILLIS }
        val recentSpan = recent.lastOrNull()?.elapsedMillis?.minus(recent.firstOrNull()?.elapsedMillis ?: 0L) ?: 0L
        val radiusLimit = maxOf(MIN_STATIONARY_RADIUS_METERS, medianAccuracy(recent) * STATIONARY_ACCURACY_MULTIPLIER)
        val radius = recent.firstOrNull()?.let { anchor ->
            recent.maxOfOrNull { distanceMeters(anchor.location, it.location) } ?: Double.MAX_VALUE
        } ?: Double.MAX_VALUE
        val recentNet = if (recent.size >= 2) distanceMeters(recent.first().location, recent.last().location) else 0.0
        val recentTravelSpeed = if (recentSpan > 0L) recentNet / (recentSpan / 1000.0) else 0.0
        val stationary = recent.size >= MIN_STATIONARY_FIXES && recentSpan >= STATIONARY_MIN_SPAN_MILLIS &&
            radius <= radiusLimit && recentTravelSpeed < FALLBACK_SPEED_METERS_PER_SECOND
        return MovementEvidence(coherent, stationary, derivedSpeed, points.size)
    }

    private fun medianAccuracy(points: List<TimedFix>): Double {
        val values = points.mapNotNull { it.location.horizontalAccuracyMeters?.toDouble() }.sorted()
        return values.getOrNull(values.size / 2) ?: 0.0
    }

    private fun pruneFixes(now: Long) {
        while (fixes.firstOrNull()?.let { now - it.elapsedMillis > POSITION_WINDOW_MILLIS } == true) fixes.removeFirst()
    }

    private fun resetCandidate() {
        candidateSinceMillis = null
        lastFastEvidenceAtMillis = null
    }

    private fun exitActive() {
        mode = HighSpeedMode.Inactive
        activeSinceElapsedRealtimeMillis = null
        resetCandidate()
        lastMovementEvidenceAtMillis = null
        slowSinceMillis = null
    }

    private fun Float?.isValidAccuracy() = this != null && isFinite() && this >= 0f && this < MAX_HORIZONTAL_ACCURACY_METERS

    private fun distanceMeters(a: LocationSnapshot, b: LocationSnapshot): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        val clamped = h.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(clamped), sqrt(1 - clamped))
    }

    private data class MovementEvidence(
        val isHighSpeedCoherent: Boolean,
        val isStationary: Boolean,
        val derivedSpeedMetersPerSecond: Double?,
        val fixCount: Int
    ) {
        companion object { val EMPTY = MovementEvidence(false, false, null, 0) }
    }

    companion object {
        const val TRIGGER_SPEED_KMH = 8f
        const val FALLBACK_SPEED_KMH = 7f
        const val TRIGGER_SPEED_METERS_PER_SECOND = TRIGGER_SPEED_KMH / 3.6f
        const val FALLBACK_SPEED_METERS_PER_SECOND = FALLBACK_SPEED_KMH / 3.6f
        const val MAX_HORIZONTAL_ACCURACY_METERS = com.pim.app.location.quality.LocationQualityGate.MAX_ACCURACY_METERS_EXCLUSIVE
        const val MAX_FIX_AGE_MILLIS = 5_000L
        const val CANDIDATE_GAP_TIMEOUT_MILLIS = 7_500L
        const val ACTIVE_EVIDENCE_TIMEOUT_MILLIS = 25_000L
        const val ENTRY_CONFIRMATION_MILLIS = 10_000L
        const val CANDIDATE_MAX_DURATION_MILLIS = 30_000L
        const val CANDIDATE_RETRY_DELAY_MILLIS = 30_000L
        const val LOW_SPEED_EXIT_MILLIS = 10_000L
        const val POSITION_WINDOW_MILLIS = 30_000L
        const val STATIONARY_WINDOW_MILLIS = 20_000L
        const val STATIONARY_MIN_SPAN_MILLIS = 15_000L
        const val MIN_EVIDENCE_SPAN_MILLIS = 8_000L
        const val MIN_EVIDENCE_FIXES = 4
        const val MIN_STATIONARY_FIXES = 5
        const val MIN_DISPLACEMENT_METERS = 20.0
        const val MIN_STATIONARY_RADIUS_METERS = 15.0
        const val ACCURACY_DISPLACEMENT_MULTIPLIER = 2.0
        const val MIN_PATH_COHERENCE = 0.55
        const val MIN_MOVING_SEGMENTS = 3
        const val MIN_MOVING_SEGMENT_RATIO = 0.6
        const val MIN_SEGMENT_SPEED_RATIO = 0.5
        const val STATIONARY_ACCURACY_MULTIPLIER = 1.5
        const val NANOS_PER_MILLI = 1_000_000L
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

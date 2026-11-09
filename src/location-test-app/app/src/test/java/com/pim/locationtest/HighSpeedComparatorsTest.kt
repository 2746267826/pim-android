package com.pim.locationtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSpeedComparatorsTest {
    @Test
    fun stationaryHighSpeedOutliersOnlyActivateTheLegacyAlgorithm() {
        val legacy = LegacyHighSpeedTracker()
        val candidate = CorroboratedHighSpeedTracker()

        repeat(24) { index ->
            val sample = sample(index * FIX_INTERVAL_MILLIS, eastMeters = 0.0, speed = 3.0f, speedAccuracy = 0.4f)
            legacy.observe(sample.speedMetersPerSecond, sample.elapsedRealtimeMillis)
            candidate.observe(sample)
        }

        assertEquals(LegacyMode.Active, legacy.mode)
        assertNotEquals(ProposedMode.Active, candidate.mode)
        assertTrue(candidate.reason.contains("等待") || candidate.reason.contains("静止簇"))
    }

    @Test
    fun coherentHighSpeedDisplacementActivatesBothAlgorithms() {
        val legacy = LegacyHighSpeedTracker()
        val candidate = CorroboratedHighSpeedTracker()

        repeat(20) { index ->
            val sample = sample(index * FIX_INTERVAL_MILLIS, index * 7.4, speed = 2.9f, speedAccuracy = 0.4f)
            legacy.observe(sample.speedMetersPerSecond, sample.elapsedRealtimeMillis)
            candidate.observe(sample)
        }

        assertEquals(LegacyMode.Active, legacy.mode)
        assertEquals(ProposedMode.Active, candidate.mode)
    }

    @Test
    fun stationaryClusterExitsCandidateWhileLegacyStillWaitsSixtySeconds() {
        val legacy = LegacyHighSpeedTracker()
        val candidate = CorroboratedHighSpeedTracker()

        val travel = (0 until 16).map { index ->
            sample(index * FIX_INTERVAL_MILLIS, index * 7.4, speed = 2.9f, speedAccuracy = 0.4f)
        }
        val stopAt = travel.last().longitude
        travel.forEach { fix ->
            legacy.observe(fix.speedMetersPerSecond, fix.elapsedRealtimeMillis)
            candidate.observe(fix)
        }
        assertEquals(ProposedMode.Active, candidate.mode)

        repeat(16) { index ->
            val at = (16 + index) * FIX_INTERVAL_MILLIS
            val stopped = LocationSample(
                elapsedRealtimeMillis = at,
                latitude = BASE_LATITUDE,
                longitude = stopAt,
                horizontalAccuracyMeters = 5f,
                speedMetersPerSecond = 3.0f,
                speedAccuracyMetersPerSecond = 0.4f
            )
            legacy.observe(stopped.speedMetersPerSecond, at)
            candidate.observe(stopped)
        }

        assertEquals(LegacyMode.Active, legacy.mode)
        assertEquals(ProposedMode.Inactive, candidate.mode)
        assertTrue(candidate.reason.contains("静止簇"))
    }

    @Test
    fun weakSpeedAccuracyFallsBackToCoherentPositionSpeed() {
        val candidate = CorroboratedHighSpeedTracker()
        val legacy = LegacyHighSpeedTracker()

        repeat(20) { index ->
            val sample = sample(index * FIX_INTERVAL_MILLIS, index * 7.4, speed = 2.9f, speedAccuracy = 3.0f)
            legacy.observe(sample.speedMetersPerSecond, sample.elapsedRealtimeMillis)
            candidate.observe(sample)
        }

        assertEquals(LegacyMode.Active, legacy.mode)
        assertEquals(ProposedMode.Active, candidate.mode)
    }

    @Test
    fun missingPositionEvidenceExpiresCandidateBeforeLegacyNullSpeedTimeout() {
        val candidate = CorroboratedHighSpeedTracker()
        val legacy = LegacyHighSpeedTracker()
        var lastAt = 0L

        repeat(20) { index ->
            lastAt = index * FIX_INTERVAL_MILLIS
            val sample = sample(lastAt, index * 7.4, speed = 2.9f, speedAccuracy = 0.4f)
            legacy.observe(sample.speedMetersPerSecond, sample.elapsedRealtimeMillis)
            candidate.observe(sample)
        }
        assertEquals(ProposedMode.Active, candidate.mode)

        candidate.advance(lastAt + CorroboratedHighSpeedTracker.ACTIVE_EVIDENCE_TIMEOUT_MILLIS)
        legacy.observe(null, lastAt + CorroboratedHighSpeedTracker.NO_FIX_TIMEOUT_MILLIS)

        assertEquals(ProposedMode.Inactive, candidate.mode)
        assertEquals(LegacyMode.Active, legacy.mode)
        assertEquals("可信位移证据已过期", candidate.reason)
    }

    private fun sample(at: Long, eastMeters: Double, speed: Float, speedAccuracy: Float?) = LocationSample(
        elapsedRealtimeMillis = at,
        latitude = BASE_LATITUDE,
        longitude = BASE_LONGITUDE + eastMeters / METERS_PER_DEGREE_LONGITUDE,
        horizontalAccuracyMeters = 5f,
        speedMetersPerSecond = speed,
        speedAccuracyMetersPerSecond = speedAccuracy
    )

    private companion object {
        const val BASE_LATITUDE = 31.2304
        const val BASE_LONGITUDE = 121.4737
        const val METERS_PER_DEGREE_LONGITUDE = 95_300.0
        const val FIX_INTERVAL_MILLIS = 2_500L
    }
}

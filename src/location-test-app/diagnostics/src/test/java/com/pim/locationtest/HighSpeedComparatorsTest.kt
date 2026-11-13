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
            val sample = sample(index * FIX_INTERVAL_MILLIS, eastMeters = 0.0, speed = 3.0f, speedAccuracy = 1.5f)
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
    fun poorSpeedAccuracyDoesNotBlockCoherentHighSpeedTrack() {
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
    fun lowReportedGpsSpeedCannotBeOverriddenByFastLookingPositionDrift() {
        val candidate = CorroboratedHighSpeedTracker()

        repeat(24) { index ->
            candidate.observe(
                sample(
                    index * FIX_INTERVAL_MILLIS,
                    eastMeters = index * 12.4,
                    speed = 0.8f,
                    speedAccuracy = 0.2f
                )
            )
        }

        assertEquals(ProposedMode.Inactive, candidate.mode)
        assertTrue(candidate.reason.contains("GPS 速度低于阈值"))
        assertTrue(candidate.derivedSpeedMetersPerSecond!! * 3.6 >= 8.0)
    }

    @Test
    fun reportedHighSpeedWithOnlyWalkingDisplacementNeverActivates() {
        val candidate = CorroboratedHighSpeedTracker()

        repeat(24) { index ->
            candidate.observe(
                sample(
                    index * FIX_INTERVAL_MILLIS,
                    eastMeters = index * 2.0,
                    speed = 2.9f,
                    speedAccuracy = 0.4f
                )
            )
            assertNotEquals(ProposedMode.Active, candidate.mode)
        }
    }

    @Test
    fun missingGpsSpeedMayUseSustainedHighSpeedPositionEvidence() {
        val candidate = CorroboratedHighSpeedTracker()

        repeat(20) { index ->
            candidate.observe(
                sample(
                    index * FIX_INTERVAL_MILLIS,
                    eastMeters = index * 7.4,
                    speed = null,
                    speedAccuracy = null
                )
            )
        }

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

    @Test
    fun heartbeatBetweenFixesDoesNotClearHighSpeedConfirmation() {
        val candidate = CorroboratedHighSpeedTracker()
        repeat(20) { index ->
            val at = index * FIX_INTERVAL_MILLIS
            candidate.observe(sample(at, index * 7.4, 2.9f, 0.4f))
            candidate.advance(at + 1_000L)
        }
        assertEquals("没有新定位的心跳不能每秒清除候选", ProposedMode.Active, candidate.mode)
    }

    @Test
    fun singlePositionJumpWithReportedHighSpeedNeverConfirmsTravel() {
        val candidate = CorroboratedHighSpeedTracker()
        repeat(10) { index ->
            candidate.observe(sample(index * 5_000L, if (index < 3) 0.0 else 150.0, 20f, 1.5f))
            assertNotEquals("单次跳点不能充当连续运动", ProposedMode.Active, candidate.mode)
        }
    }

    @Test
    fun oldTrackCannotRenewEvidenceOnEveryHeartbeatAfterSignalLoss() {
        val candidate = CorroboratedHighSpeedTracker()
        repeat(20) { index -> candidate.observe(sample(index * FIX_INTERVAL_MILLIS, index * 7.4, 2.9f, 0.4f)) }
        assertEquals(ProposedMode.Active, candidate.mode)
        val lastAt = 19 * FIX_INTERVAL_MILLIS
        for (delta in 1_000L..25_000L step 1_000L) candidate.advance(lastAt + delta)
        assertEquals("旧位置窗口不能被心跳当作新证据", ProposedMode.Inactive, candidate.mode)
    }

    @Test
    fun precisePositionsSeparatedByLongQualityGapsAreInsufficient() {
        val candidate = CorroboratedHighSpeedTracker()
        repeat(15) { index ->
            val at = index * 2_500L
            val accuracy = if (index % 4 == 0) 5f else 120f
            candidate.observe(sample(at, index * 25.0, 20f, 1.5f).copy(horizontalAccuracyMeters = accuracy))
            assertNotEquals(ProposedMode.Active, candidate.mode)
        }
    }

    @Test
    fun staticTrackWithAlternatingAccuracyAndExtremeSpeedNeverActivates() {
        val candidate = CorroboratedHighSpeedTracker()
        val accuracies = listOf(20f, 140f, 35f, 25f, 100f)
        repeat(80) { index ->
            val at = index * 5_000L
            candidate.observe(sample(at, (index % 3 - 1) * 2.0, 25f, 1.5f).copy(horizontalAccuracyMeters = accuracies[index % accuracies.size]))
            candidate.advance(at + 1_000L)
            assertNotEquals(ProposedMode.Active, candidate.mode)
        }
    }

    private fun sample(at: Long, eastMeters: Double, speed: Float?, speedAccuracy: Float?) = LocationSample(
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

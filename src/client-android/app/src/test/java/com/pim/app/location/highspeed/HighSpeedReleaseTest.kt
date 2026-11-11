package com.pim.app.location.highspeed

import com.pim.app.location.LocationSnapshot
import org.junit.Assert.*
import org.junit.Test

class HighSpeedReleaseTest {
    private var now = 100_000L
    private val tracker = HighSpeedTracker(nowElapsedRealtimeMillis = { now })

    private fun fix(at: Long, meters: Double, speed: Float? = 10f, accuracy: Float = 5f) = LocationSnapshot(
        latitude = 0.0, longitude = meters / 111_194.927, horizontalAccuracyMeters = accuracy,
        provider = "fused", source = "synthetic", altitudeMeters = null, speedMetersPerSecond = speed,
        bearingDegrees = null, timeMillis = 1_000_000L + at, elapsedRealtimeNanos = at * 1_000_000
    )

    private fun feed(index: Int, meters: Double = index * 25.0, speed: Float? = 10f, accuracy: Float = 5f) {
        now = 100_000L + index * 2_500L
        tracker.observeLocation(fix(now, meters, speed, accuracy))
    }

    @Test fun fixedCoordinatesWithContinuousHighSpeedNeverActivate() {
        repeat(40) { index -> feed(index, meters = 0.0, speed = 25f); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun singlePositionJumpIsNotContinuousTravel() {
        repeat(24) { index -> feed(index, meters = if (index < 4) 0.0 else 200.0); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun freshContinuousDisplacementActivatesAndHeartbeatsPreserveConfirmation() {
        repeat(12) { index -> feed(index); now += 1_000; tracker.advance() }
        assertEquals(HighSpeedMode.Active, tracker.mode)
        assertNotNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun freshLowSpeedCannotBeOverriddenByFastPositionDrift() {
        repeat(24) { index -> feed(index, speed = 0.5f); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun reportedHighSpeedWithWalkingDisplacementNeverActivates() {
        repeat(24) { index -> feed(index, meters = index * 3.0); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun missingReportedSpeedCanUseCoherentPositions() {
        repeat(24) { index -> feed(index, speed = null) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun staleBatchCannotStartOrActivateCurrentHighSpeed() {
        now = 475_000L
        repeat(40) { index ->
            tracker.observeLocation(fix(197_000L + index * 5_000L, index * 50.0))
            assertEquals(HighSpeedMode.Inactive, tracker.mode)
        }
    }

    @Test fun freshPointAfterLongGapStartsNewConfirmation() {
        feed(0)
        now += 100_000
        tracker.observeLocation(fix(now, 1000.0))
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun missingFutureAndDuplicateTimestampsCannotAccumulate() {
        repeat(20) {
            now += 2_500
            tracker.observeLocation(fix(now, it * 25.0).copy(elapsedRealtimeNanos = null))
            assertEquals(HighSpeedMode.Inactive, tracker.mode)
        }
        tracker.reset()
        repeat(20) {
            now += 2_500
            tracker.observeLocation(fix(now + 1, it * 25.0))
            assertEquals(HighSpeedMode.Inactive, tracker.mode)
        }
        tracker.reset()
        val repeated = fix(now, 0.0)
        repeat(20) { now += 1_000; tracker.observeLocation(repeated) }
        assertNotEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun newPositionsAfterEvidenceExpiresCannotKeepOldActiveState() {
        repeat(12) { feed(it) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
        now += 100_000
        tracker.observeLocation(fix(now, 2000.0))
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun oldEvidenceCannotBeRenewedByTimerTicks() {
        repeat(12) { feed(it) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
        repeat(25) { now += 1_000; tracker.advance() }
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun stationaryClusterKeepsThirtySecondGraceAndExitsAfterSixtySecondsOfEvidence() {
        repeat(12) { feed(it) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
        repeat(13) { index -> feed(12 + index, meters = 275.0, speed = 20f) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
        repeat(24) { index -> feed(25 + index, meters = 275.0, speed = 20f) }
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun changingPoorAccuracyCannotProveTravel() {
        repeat(24) { index -> feed(index, accuracy = if (index % 4 == 0) 5f else 120f); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun resetClearsConfirmationAndElapsedCounter() {
        repeat(12) { feed(it) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
        tracker.reset()
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun outOfOrderFreshPointsCannotBridgeANewConfirmation() {
        repeat(3) { feed(it) }
        now = 111_000
        repeat(10) { tracker.observeLocation(fix(104_000, it * 100.0)) }
        assertNotEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun justBelowThirtyMeterAccuracyStillAllowsCoherentTravelNearThreshold() {
        repeat(16) { index -> feed(index, meters = index * 6.0, speed = 2.4f, accuracy = 29f) }
        assertEquals("匀速移动不能被随精度放大的静止半径覆盖", HighSpeedMode.Active, tracker.mode)
    }

    @Test fun frequentHeartbeatsDoNotDeleteValidFiveSecondLocationWindows() {
        repeat(12) { index ->
            now = 100_000L + index * 5_000L
            tracker.observeLocation(fix(now, index * 50.0))
            repeat(4) { now += 1_000L; tracker.advance() }
        }
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun invalidReportedSpeedIsNotTheMissingSpeedFallback() {
        repeat(24) { index -> feed(index, speed = Float.NaN); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun repeatedPositionBouncesAreNotCoherentTravel() {
        repeat(24) { index -> feed(index, meters = if (index % 2 == 0) 0.0 else 200.0); assertNotEquals(HighSpeedMode.Active, tracker.mode) }
    }

    @Test fun uncorroboratedOscillationLeavesDenseConfirmationAndCanRecoverToRealTravel() {
        repeat(24) { index -> feed(index, meters = if (index % 2 == 0) -20.0 else 20.0) }
        assertEquals("无佐证速度不能无限保持2.5秒确认档", HighSpeedMode.Inactive, tracker.mode)
        repeat(24) { index -> feed(24 + index, meters = index * 25.0) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun lowSpeedCannotCountAnUnobservedGapAsContinuousEvidence() {
        repeat(6) { feed(it) }
        now += 2_500
        tracker.observeLocation(fix(now, 0.0, 0.1f))
        assertEquals(HighSpeedMode.Active, tracker.mode)
        now += 20_000
        tracker.observeLocation(fix(now, 0.0, 0.1f))
        assertEquals("独立25秒过期前，单个新低速点不能继承20秒缺口", HighSpeedMode.Active, tracker.mode)
    }
}

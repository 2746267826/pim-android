package com.pim.app.location.highspeed

import com.pim.app.location.LocationSnapshot
import org.junit.Assert.*
import org.junit.Test

class HighSpeedTrackerTest {
    private var now = 100_000L
    private val tracker = HighSpeedTracker { now }
    private fun fix(at: Long, east: Double, speed: Float? = 10f, accuracy: Float = 5f) = LocationSnapshot(
        latitude = 0.0, longitude = east / 111_194.927, horizontalAccuracyMeters = accuracy,
        provider = "fused", source = "synthetic", altitudeMeters = null, speedMetersPerSecond = speed,
        bearingDegrees = null, timeMillis = 1_000_000L + at, elapsedRealtimeNanos = at * 1_000_000L
    )
    private fun feed(at: Long, east: Double, speed: Float? = 10f, accuracy: Float = 5f) {
        now = at
        tracker.observeLocation(fix(at, east, speed, accuracy))
    }
    private fun activate() {
        repeat(6) { index -> feed(100_000L + index * 2_500L, index * 25.0) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun singleFastFixRequestsDenseConfirmation() {
        feed(now, 0.0)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun belowEightKmhDoesNotBeginConfirmation() {
        repeat(24) { index -> feed(100_000L + index * 2_500L, index * 25.0, 7.9f / 3.6f) }
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun confirmationRequiresTenSecondsAndFourFixes() {
        for (index in 0..3) {
            feed(100_000L + index * 2_500L, index * 25.0)
            assertNotEquals(HighSpeedMode.Active, tracker.mode)
        }
        feed(110_000, 100.0)
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun noFixExpiresConfirmationWithoutBecomingMovement() {
        feed(now, 0.0)
        now += 7_501
        tracker.advance()
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun lowReportedSpeedWithinToleranceKeepsConfirmation() {
        feed(now, 0.0)
        feed(now + 2_500, 25.0, 0.1f)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        feed(now + 500, 30.0)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
    }

    @Test fun oneSevenPointEightKmhFixDoesNotResetFastConfirmation() {
        feed(100_000, 0.0)
        feed(102_500, 25.0)
        feed(105_000, 30.4, 7.8f / 3.6f)
        feed(107_500, 55.4)
        feed(110_000, 80.4)

        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun thirtySecondsAtAStopDoesNotExitHighSpeed() {
        activate()
        repeat(13) { index ->
            feed(115_000L + index * 2_500L, 125.0, 0.5f)
        }

        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test fun sustainedLowSpeedExitsAfterSixtySeconds() {
        activate()
        repeat(24) { index ->
            feed(115_000L + index * 2_500L, 125.0 + index * 2.5, 0.5f)
            assertEquals(HighSpeedMode.Active, tracker.mode)
        }
        feed(175_000L, 185.0, 0.5f)

        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun coherentPositionsDoNotRenewEvidenceWhenReportedSpeedIsLow() {
        activate()
        // 位置一致度足以证明 36 km/h 的位移，但系统速度只报 5 km/h：以速度为准进入低速宽限。
        var at = 115_000L
        var east = 150.0
        repeat(12) {
            at += 2_500L
            east += 25.0
            feed(at, east, 5f / 3.6f)
            assertEquals(
                "系统速度 <7km/h 时位置一致度既不能续期也不能提前退出",
                HighSpeedMode.Active,
                tracker.mode
            )
        }
        repeat(13) {
            at += 2_500L
            east += 25.0
            feed(at, east, 5f / 3.6f)
        }

        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun highSpeedCannotBridgeAnUnobservedGap() {
        feed(100_000, 0.0)
        feed(105_000, 50.0)
        feed(125_000, 250.0)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun exactlyThirtyMeterAccuracyDoesNotEnterEvidence() {
        repeat(24) { index -> feed(100_000L + index * 2_500L, index * 25.0, accuracy = 30f) }
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test fun invalidCoordinatesAndNonFiniteSpeedsCannotConfirm() {
        repeat(20) { index ->
            now += 2_500
            tracker.observeLocation(fix(now, index * 25.0).copy(latitude = Double.NaN))
            assertNotEquals(HighSpeedMode.Active, tracker.mode)
        }
    }

    @Test fun activeEvidenceExpiresExactlyAtTwentyFiveSeconds() {
        activate()
        now += 24_999
        tracker.advance()
        assertEquals(HighSpeedMode.Active, tracker.mode)
        now++
        tracker.advance()
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)
    }

    @Test fun realTravelAfterStationaryEvidenceCanActivateAgain() {
        repeat(12) { index -> feed(100_000L + index * 2_500L, 0.0) }
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
        repeat(24) { index -> feed(130_000L + index * 2_500L, index * 25.0) }
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }
}

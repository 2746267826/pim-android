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

    @Test fun lowReportedSpeedInterruptsConfirmationImmediately() {
        feed(now, 0.0)
        feed(now + 2_500, 25.0, 0.1f)
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

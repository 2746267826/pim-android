package com.pim.app.location.highspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighSpeedTrackerTest {

    private var now = 0L

    private fun tracker() = HighSpeedTracker(nowElapsedRealtimeMillis = { now })

    private fun fast(): Float = HighSpeedTracker.TRIGGER_SPEED_METERS_PER_SECOND

    private fun slow(): Float = 0.1f

    private fun fallbackEdge(): Float = HighSpeedTracker.FALLBACK_SPEED_METERS_PER_SECOND

    @Test
    fun sustainedFastSamplesForTenSecondsActivate() {
        val tracker = tracker()

        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 4_000)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        tracker.observe(fast(), atMillis = 9_000)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        tracker.observe(fast(), atMillis = 10_000)

        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test
    fun belowThresholdNeverTriggers() {
        val tracker = tracker()

        val below = 7.9f / 3.6f
        for (elapsed in listOf(0L, 5_000L, 10_000L, 30_000L, 60_000L)) {
            tracker.observe(below, atMillis = elapsed)
        }

        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun singleFastSampleOnlyAccumulates() {
        val tracker = tracker()

        tracker.observe(fast(), atMillis = 0)

        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
    }

    @Test
    fun sustainedLowSampleDuringAccumulationResetsStreak() {
        val tracker = tracker()
        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 5_000)

        tracker.observe(slow(), atMillis = 9_000)
        tracker.observe(slow(), atMillis = 12_000)

        assertEquals(HighSpeedMode.Inactive, tracker.mode)
        tracker.observe(fast(), atMillis = 13_000)
        tracker.observe(fast(), atMillis = 23_000)
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test
    fun briefLowSampleWithinToleranceDoesNotResetFastStreak() {
        val tracker = tracker()
        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 5_000)
        tracker.observe(slow(), atMillis = 6_000)

        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        tracker.observe(fast(), atMillis = 8_000)
        assertEquals(HighSpeedMode.Accumulating, tracker.mode)
        tracker.observe(fast(), atMillis = 10_000)

        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test
    fun activeFallsBackAfterSixtySecondsOfSlowSamples() {
        val tracker = activate()

        tracker.observe(slow(), atMillis = 10_000)
        tracker.observe(slow(), atMillis = 40_000)
        tracker.observe(slow(), atMillis = 69_999)
        assertEquals(HighSpeedMode.Active, tracker.mode)

        tracker.observe(slow(), atMillis = 70_000)
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun redLightThirtySecondsDoesNotFallBack() {
        val tracker = activate()

        tracker.observe(slow(), atMillis = 10_000)
        tracker.observe(slow(), atMillis = 40_000)
        assertEquals(HighSpeedMode.Active, tracker.mode)

        tracker.observe(fast(), atMillis = 42_000)
        tracker.observe(fast(), atMillis = 50_000)
        assertEquals(HighSpeedMode.Active, tracker.mode)

        tracker.observe(slow(), atMillis = 80_000)
        tracker.observe(slow(), atMillis = 139_999)
        assertEquals(HighSpeedMode.Active, tracker.mode)
        tracker.observe(slow(), atMillis = 140_000)
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun triggerThenImmediateSlowSamplesStillRequireSixtySecondDebounce() {
        val tracker = activate()

        tracker.observe(slow(), atMillis = 10_001)
        tracker.observe(slow(), atMillis = 70_000)
        assertEquals(HighSpeedMode.Active, tracker.mode)
        tracker.observe(slow(), atMillis = 70_001)
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun isolatedSpeedFluctuationDoesNotResetAccumulation() {
        val tracker = tracker()

        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 3_000)
        tracker.observe(slow(), atMillis = 3_500)
        tracker.observe(fast(), atMillis = 6_000)
        tracker.observe(fast(), atMillis = 9_000)
        tracker.observe(slow(), atMillis = 9_500)
        tracker.observe(fast(), atMillis = 12_000)
        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test
    fun nullSpeedDoesNotTriggerButCountsAsSlowWhileActive() {
        val tracker = tracker()

        tracker.observe(null, atMillis = 0)
        tracker.observe(null, atMillis = 20_000)
        assertEquals(HighSpeedMode.Inactive, tracker.mode)

        val active = activate()
        active.observe(null, atMillis = 10_000)
        active.observe(null, atMillis = 69_999)
        assertEquals(HighSpeedMode.Active, active.mode)
        active.observe(null, atMillis = 70_000)
        assertEquals(HighSpeedMode.Inactive, active.mode)
    }

    @Test
    fun exactThresholdActivatesAtExactlyTenSeconds() {
        val tracker = tracker()

        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 10_000)

        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test
    fun fallbackRequiresStrictlyBelowSevenKmh() {
        val tracker = activate()

        tracker.observe(fallbackEdge(), atMillis = 10_000)
        tracker.observe(fallbackEdge(), atMillis = 40_000)
        tracker.observe(fallbackEdge(), atMillis = 70_000)
        assertEquals(
            "speed exactly at fallback threshold must not accumulate",
            HighSpeedMode.Active,
            tracker.mode
        )

        tracker.observe(0.99f / 3.6f, atMillis = 71_000)
        tracker.observe(0.99f / 3.6f, atMillis = 130_999)
        assertEquals(HighSpeedMode.Active, tracker.mode)
        tracker.observe(0.99f / 3.6f, atMillis = 131_000)
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun walkingWithOccasionalHighSpeedSpikesStillExitsDenseMode() {
        val tracker = activate()

        var atMillis = 10_000L
        while (atMillis < 70_000L) {
            val isIsolatedSpike = (atMillis - 10_000L) % 20_000L == 0L
            tracker.observe(
                speedMetersPerSecond = if (isIsolatedSpike) {
                    12f / 3.6f
                } else {
                    5.5f / 3.6f
                },
                freshWalkingOrStill = true,
                atMillis = atMillis
            )
            atMillis += 2_500L
        }
        tracker.observe(
            speedMetersPerSecond = 5.5f / 3.6f,
            freshWalkingOrStill = true,
            atMillis = 69_999L
        )
        assertEquals(HighSpeedMode.Active, tracker.mode)

        tracker.observe(
            speedMetersPerSecond = 5.5f / 3.6f,
            freshWalkingOrStill = true,
            atMillis = 70_000
        )
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun sustainedHighSpeedResetsEarlierWalkingEvidence() {
        val tracker = activate()

        for (atMillis in 10_000L..37_500L step 2_500) {
            tracker.observe(
                speedMetersPerSecond = 5.5f / 3.6f,
                freshWalkingOrStill = true,
                atMillis = atMillis
            )
        }
        for (atMillis in 40_000L..70_000L step 2_500) {
            tracker.observe(
                speedMetersPerSecond = 30f / 3.6f,
                freshWalkingOrStill = true,
                atMillis = atMillis
            )
        }

        assertEquals(
            "stale walking corroboration must not override a sustained return to high speed",
            HighSpeedMode.Active,
            tracker.mode
        )
    }

    @Test
    fun sustainedHighSpeedIgnoresWalkingWithoutSpeedCorroboration() {
        val tracker = activate()

        for (atMillis in 10_000L..70_000L step 2_500) {
            tracker.observe(
                speedMetersPerSecond = 30f / 3.6f,
                freshWalkingOrStill = true,
                atMillis = atMillis
            )
        }

        assertEquals(
            "a fresh AR label alone must not override corroborated high-speed fixes",
            HighSpeedMode.Active,
            tracker.mode
        )
    }

    @Test
    fun sustainedHighSpeedWithSparseLowSpeedGlidesStaysActive() {
        val tracker = activate()

        for (atMillis in 10_000L..70_000L step 2_500L) {
            val isSparseLowGlide = (atMillis - 10_000L) % 10_000L == 0L
            tracker.observe(
                speedMetersPerSecond = if (isSparseLowGlide) {
                    6.5f / 3.6f
                } else {
                    30f / 3.6f
                },
                freshWalkingOrStill = true,
                atMillis = atMillis
            )
        }

        assertEquals(
            "isolated low-speed glides must not outweigh sustained highway-speed evidence",
            HighSpeedMode.Active,
            tracker.mode
        )
    }

    @Test
    fun staleWalkingEvidenceAtWalkingSpeedDoesNotExitWithoutFreshMotion() {
        val tracker = activate()
        val justBelowHighSpeed = 7.5f / 3.6f

        tracker.observe(
            speedMetersPerSecond = justBelowHighSpeed,
            freshWalkingOrStill = true,
            atMillis = 10_000L
        )
        tracker.observe(
            speedMetersPerSecond = justBelowHighSpeed,
            freshWalkingOrStill = false,
            atMillis = 70_000L
        )

        assertEquals(HighSpeedMode.Active, tracker.mode)
    }

    @Test
    fun oneSubTriggerSpeedSampleDoesNotCorroborateSustainedWalking() {
        val tracker = activate()

        for (atMillis in 10_000L..70_000L step 2_500) {
            tracker.observe(
                speedMetersPerSecond = if (atMillis == 20_000L) {
                    5.5f / 3.6f
                } else {
                    12f / 3.6f
                },
                freshWalkingOrStill = true,
                atMillis = atMillis
            )
        }

        assertEquals(
            "one low-speed Doppler sample is not a stable walking corridor",
            HighSpeedMode.Active,
            tracker.mode
        )
    }

    @Test
    fun sustainedWalkingSpeedBelowExitThresholdLeavesDenseMode() {
        val tracker = activate()
        val walking = 5.5f / 3.6f

        tracker.observe(walking, atMillis = 10_000)
        tracker.observe(walking, atMillis = 69_999)
        assertEquals(HighSpeedMode.Active, tracker.mode)

        tracker.observe(walking, atMillis = 70_000)
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
    }

    @Test
    fun activeSinceExposedAndReset() {
        val tracker = tracker()
        assertNull(tracker.activeSinceElapsedRealtimeMillis)

        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 10_000)
        assertEquals(10_000L, tracker.activeSinceElapsedRealtimeMillis)

        tracker.reset()
        assertEquals(HighSpeedMode.Inactive, tracker.mode)
        assertNull(tracker.activeSinceElapsedRealtimeMillis)

        val second = activate()
        second.observe(slow(), atMillis = 11_000)
        second.observe(slow(), atMillis = 71_000)
        assertEquals(HighSpeedMode.Inactive, second.mode)
        assertNull(second.activeSinceElapsedRealtimeMillis)
    }

    private fun activate(): HighSpeedTracker {
        val tracker = tracker()
        tracker.observe(fast(), atMillis = 0)
        tracker.observe(fast(), atMillis = 5_000)
        tracker.observe(fast(), atMillis = 10_000)
        assertTrue("precondition: tracker must be active", tracker.mode == HighSpeedMode.Active)
        return tracker
    }

    private fun HighSpeedTracker.observe(speed: Float?, atMillis: Long) {
        now = atMillis
        observe(speed)
    }

    private fun HighSpeedTracker.observe(
        speedMetersPerSecond: Float?,
        freshWalkingOrStill: Boolean,
        atMillis: Long
    ) {
        now = atMillis
        observe(speedMetersPerSecond, freshWalkingOrStill)
    }
}

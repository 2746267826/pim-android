package com.pim.locationtest

import org.junit.Assert.*
import org.junit.Test

class SensorWindowMonitorTest {
    @Test fun lateShortBurstIsUnknownDespiteFiftyHertzIntervals() {
        val monitor = SensorWindowMonitor(0)
        repeat(88) { i -> val at = 3_200L + i * 20L; monitor.record(at, at, 9.8) }
        val window = monitor.snapshot(5_000)
        assertEquals(88, window.count)
        assertEquals(3_200L, window.firstDelayMillis)
        assertTrue(window.coveredMillis < 2_000)
        assertEquals(SensorEvidence.Unknown, window.evidence)
    }

    @Test fun completeQuietWindowReportsQuietButNeverClaimsStationary() {
        val monitor = SensorWindowMonitor(0)
        for (at in 0L..5_000L step 20L) monitor.record(at, at, 9.8)
        val window = monitor.snapshot(5_000)
        assertEquals(5_000L, window.coveredMillis)
        assertEquals(SensorEvidence.Quiet, window.evidence)
        assertEquals(0.0, window.standardDeviation!!, 0.000001)
    }

    @Test fun stalledCallbacksBecomeUnknownInsteadOfReusingOldQuietState() {
        val monitor = SensorWindowMonitor(0)
        for (at in 0L..5_000L step 20L) monitor.record(at, at, 9.8)
        assertEquals(SensorEvidence.Quiet, monitor.snapshot(5_000).evidence)
        assertEquals(SensorEvidence.Unknown, monitor.snapshot(6_000).evidence)
        assertEquals(1_000L, monitor.snapshot(6_000).ageMillis)
    }

    @Test fun longGapIsExcludedFromCoverageAndCannotCountAsQuietObservation() {
        val monitor = SensorWindowMonitor(0)
        for (at in 0L..1_000L step 20L) monitor.record(at, at, 9.8)
        for (at in 4_000L..5_000L step 20L) monitor.record(at, at, 9.8)
        val window = monitor.snapshot(5_000)
        assertEquals(3_000L, window.maxGapMillis)
        assertEquals(2_000L, window.coveredMillis)
        assertEquals(SensorEvidence.Unknown, window.evidence)
    }

    @Test fun delayedBatchIsNotFreshLiveEvidence() {
        val monitor = SensorWindowMonitor(0)
        for (at in 0L..5_000L step 20L) monitor.record(at, at + 3_000, 9.8)
        val window = monitor.snapshot(8_000)
        assertEquals(3_000L, window.maxDeliveryDelayMillis)
        assertEquals(SensorEvidence.Unknown, window.evidence)
    }

    @Test fun invalidAndOutOfOrderEventsCannotImproveCoverage() {
        val monitor = SensorWindowMonitor(0)
        monitor.record(-20, 0, 9.8)
        monitor.record(20, 20, 9.8)
        monitor.record(10, 30, 9.8)
        monitor.record(40, 30, Double.NaN)
        monitor.record(100, 30, 9.8)
        assertEquals(1L, monitor.snapshot(30).totalCount)
    }

    @Test fun freshlyDeliveredOldBatchIsUnknownEvenWhenLastEventIsCurrent() {
        val monitor = SensorWindowMonitor(0)
        for (at in 0L..5_000L step 20L) monitor.record(at, 5_000L, 9.8)
        assertEquals(0L, monitor.snapshot(5_000).ageMillis)
        assertEquals(SensorEvidence.Unknown, monitor.snapshot(5_000).evidence)
    }

    @Test fun normalSamplingRecoversAfterHistoricalGapWithoutErasingTheGap() {
        val monitor = SensorWindowMonitor(0)
        monitor.record(0, 0, 9.8)
        for (at in 4_000L..10_000L step 20L) monitor.record(at, at, 9.8)
        val window = monitor.snapshot(10_000)
        assertEquals(4_000L, window.maxGapMillis)
        assertEquals(20L, window.windowMaxGapMillis)
        assertEquals(SensorEvidence.Quiet, window.evidence)
    }
}
